package app.morphe.extension.voicenote.patches;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.sec.android.app.voicenote.common.util.AppResources;
import com.sec.android.app.voicenote.data.ExtractorSound;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Waveform source for formats the app's own extractors cannot read. Those parse the MP4
 * (AAC) or AMR container and estimate loudness from frame sizes without decoding. This
 * decodes with the platform decoders instead, so it works for anything Android plays
 * (Ogg Opus/Vorbis, MP3, FLAC, WAV, ...). The app's Extractor smooths and scales the
 * frame gains like it does for AAC frames.
 * <p>
 * The platform extractor and decoder run in system processes, so the per-packet IPC
 * dominates for small packets such as 20 ms Opus frames. Long files are therefore split
 * into time segments decoded in parallel, each with its own extractor and decoder.
 * <p>
 * The app does not store waveforms of files it did not record, so the gains are cached
 * per file to avoid decoding long recordings again on every open.
 */
final class DecodedAudioExtractor extends ExtractorSound {
    private static final String TAG = "DecodedAudioExtractor";

    /**
     * Same frame length as an AAC frame, which the app's waveform code was tuned for.
     */
    private static final int SAMPLES_PER_FRAME = 1024;

    private static final long CODEC_TIMEOUT_US = 10_000;

    /**
     * Shortest segment worth a separate decoder. Shorter files decode in one pass.
     */
    private static final long MIN_SEGMENT_US = 30_000_000;
    private static final int MAX_SEGMENTS = 8;

    /**
     * Decoding starts this long before a segment, so the decoder state settles (Opus
     * needs 80 ms) and seeking to an earlier sync point cannot skip audio.
     */
    private static final long SEGMENT_PREROLL_US = 250_000;

    /**
     * Input is fed this long past the segment end, as decoders may hold back output.
     */
    private static final long SEGMENT_INPUT_MARGIN_US = 250_000;

    private static final String CACHE_DIRECTORY = "morphe_waveforms";
    /**
     * Change when the gain calculation changes, so stale cache files are ignored.
     */
    private static final int CACHE_VERSION = 1;
    private static final int CACHE_MAGIC = 0x4D575631; // "MWV1"

    private static final double DB_PER_GAIN_STEP = 1.5;
    private static final int MAX_GAIN = 255;

    private int[] frameGains = new int[0];
    private int numFrames;
    private int sampleRate;

    @Override
    public void readFile(File file) {
        super.readFile(file);

        File cacheFile = getCacheFile(file);
        String cacheKey = getCacheKey(file);
        if (cacheFile != null && readCache(cacheFile, cacheKey)) {
            return;
        }

        long start = System.currentTimeMillis();
        try {
            decode(file);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode " + file, e);
            frameGains = new int[0];
            numFrames = 0;
            return;
        }
        Log.i(TAG, "Decoded " + numFrames + " frames in "
                + (System.currentTimeMillis() - start) + " ms: " + file.getName());

        if (cacheFile != null && numFrames > 0) {
            writeCache(cacheFile, cacheKey);
        }
    }

    @Override
    public int[] getFrameGains() {
        return frameGains;
    }

    @Override
    public int getNumFrames() {
        return numFrames;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getSamplesPerFrame() {
        return SAMPLES_PER_FRAME;
    }

    private void decode(File file) throws Exception {
        String path = file.getAbsolutePath();

        long durationUs;
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(path);
            MediaFormat format = selectAudioTrack(probe);
            if (format == null) {
                Log.w(TAG, "No audio track in " + file);
                return;
            }
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION)
                    : -1;
        } finally {
            probe.release();
        }

        int segmentCount = getSegmentCount(durationUs);
        List<FrameEnergy> results;
        if (segmentCount == 1) {
            results = List.of(new Segment(path, sampleRate, 0, Long.MAX_VALUE).call());
        } else {
            try {
                results = decodeInParallel(path, durationUs, segmentCount);
            } catch (Exception e) {
                Log.w(TAG, "Parallel decoding failed, decoding in one pass: " + file, e);
                results = List.of(new Segment(path, sampleRate, 0, Long.MAX_VALUE).call());
            }
        }

        frameGains = merge(results);
        numFrames = frameGains.length;
    }

    private List<FrameEnergy> decodeInParallel(String path, long durationUs, int segmentCount)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(segmentCount,
                runnable -> new Thread(runnable, "MorpheWaveSegment"));
        try {
            List<Future<FrameEnergy>> futures = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                long startUs = durationUs * i / segmentCount;
                // The duration may be inexact, so the last segment runs to the end.
                long endUs = i == segmentCount - 1
                        ? Long.MAX_VALUE
                        : durationUs * (i + 1) / segmentCount;
                futures.add(executor.submit(new Segment(path, sampleRate, startUs, endUs)));
            }

            List<FrameEnergy> results = new ArrayList<>(segmentCount);
            for (Future<FrameEnergy> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof Exception ? (Exception) cause : e;
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static int getSegmentCount(long durationUs) {
        if (durationUs <= 0) {
            return 1;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        long bySegmentLength = durationUs / MIN_SEGMENT_US;
        return (int) Math.max(1, Math.min(Math.min(cores, MAX_SEGMENTS), bySegmentLength));
    }

    /**
     * Sums the per-segment frame energies. Frames at segment boundaries get samples from
     * both neighbors.
     */
    private static int[] merge(List<FrameEnergy> segments) {
        int frameCount = 0;
        for (FrameEnergy segment : segments) {
            frameCount = Math.max(frameCount, segment.getEndFrame());
        }

        double[] energy = new double[frameCount];
        int[] values = new int[frameCount];
        for (FrameEnergy segment : segments) {
            segment.addTo(energy, values);
        }

        int[] gains = new int[frameCount];
        for (int i = 0; i < frameCount; i++) {
            gains[i] = values[i] == 0 ? 0 : toGain(Math.sqrt(energy[i] / values[i]));
        }
        return gains;
    }

    /**
     * Loudness in the units of the AAC global_gain field that the app's own extractor
     * reports: logarithmic, 1.5 dB per step. The app normalizes gains between low and high
     * percentiles and squares them, which assumes this logarithmic scale. Linear levels
     * would leave all but the loudest frames near zero.
     *
     * @param rms RMS level in 16-bit sample units.
     */
    private static int toGain(double rms) {
        // dB relative to one 16-bit step, 0 to about 90 dB.
        double decibels = rms > 1 ? 20 * Math.log10(rms) : 0;
        return (int) Math.min(MAX_GAIN, Math.round(decibels / DB_PER_GAIN_STEP));
    }

    private static MediaFormat selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0, count = extractor.getTrackCount(); i < count; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    /**
     * Decodes the samples of one time range with its own extractor and decoder.
     * Samples are placed by output timestamp, so seeking to an earlier sync point and
     * the decoding margins do not count any sample twice.
     */
    private static final class Segment implements java.util.concurrent.Callable<FrameEnergy> {
        private final String path;
        private final long startUs;
        private final long endUs;
        private final long startSample;
        private final long endSample;

        private int channels = 1;
        private int outputSampleRate;
        private boolean floatPcm;

        private short[] shortSamples = new short[0];
        private float[] floatSamples = new float[0];

        Segment(String path, int sampleRate, long startUs, long endUs) {
            this.path = path;
            this.startUs = startUs;
            this.endUs = endUs;
            this.outputSampleRate = sampleRate;
            this.startSample = toSample(startUs, sampleRate);
            this.endSample = endUs == Long.MAX_VALUE ? Long.MAX_VALUE : toSample(endUs, sampleRate);
        }

        @Override
        public FrameEnergy call() throws Exception {
            FrameEnergy result = new FrameEnergy((int) (startSample / SAMPLES_PER_FRAME));
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try {
                extractor.setDataSource(path);
                MediaFormat format = selectAudioTrack(extractor);
                if (format == null) {
                    return result;
                }
                channels = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                if (startUs > 0) {
                    extractor.seekTo(Math.max(0, startUs - SEGMENT_PREROLL_US),
                            MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                }

                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.configure(format, null, null, 0);
                codec.start();

                long inputEndUs = endUs == Long.MAX_VALUE
                        ? Long.MAX_VALUE
                        : endUs + SEGMENT_INPUT_MARGIN_US;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;

                while (!outputDone) {
                    // Queue every free input buffer, then take every ready output buffer,
                    // and only wait when neither made progress.
                    boolean progressed = false;

                    while (!inputDone) {
                        int inputIndex = codec.dequeueInputBuffer(0);
                        if (inputIndex < 0) {
                            break;
                        }
                        progressed = true;

                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        long sampleTimeUs = extractor.getSampleTime();
                        int size = input == null || sampleTimeUs < 0 || sampleTimeUs >= inputEndUs
                                ? -1
                                : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0);
                            extractor.advance();
                        }
                    }

                    while (!outputDone) {
                        int outputIndex = codec.dequeueOutputBuffer(info, progressed ? 0 : CODEC_TIMEOUT_US);
                        if (outputIndex >= 0) {
                            progressed = true;
                            ByteBuffer output = codec.getOutputBuffer(outputIndex);
                            if (output != null && info.size > 0) {
                                output.position(info.offset);
                                output.limit(info.offset + info.size);
                                add(output.order(ByteOrder.nativeOrder()),
                                        toSample(info.presentationTimeUs, outputSampleRate), result);
                            }
                            codec.releaseOutputBuffer(outputIndex, false);
                            outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat outputFormat = codec.getOutputFormat();
                            channels = Math.max(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                            outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            floatPcm = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    == AudioFormat.ENCODING_PCM_FLOAT;
                        } else {
                            // No output ready yet. Feed more input first.
                            break;
                        }
                    }
                }
                return result;
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    codec.release();
                }
                extractor.release();
            }
        }

        /**
         * @param firstSample Index of the buffer's first sample (per channel) in the file.
         */
        private void add(ByteBuffer pcm, long firstSample, FrameEnergy result) {
            int length;
            if (floatPcm) {
                FloatBuffer buffer = pcm.asFloatBuffer();
                length = buffer.remaining();
                if (floatSamples.length < length) {
                    floatSamples = new float[length];
                }
                buffer.get(floatSamples, 0, length);
            } else {
                ShortBuffer buffer = pcm.asShortBuffer();
                length = buffer.remaining();
                if (shortSamples.length < length) {
                    shortSamples = new short[length];
                }
                buffer.get(shortSamples, 0, length);
            }

            int sampleCount = length / channels;
            // Only the samples inside this segment.
            long from = Math.max(firstSample, startSample);
            long to = Math.min(firstSample + sampleCount, endSample);

            for (long sample = from; sample < to; ) {
                long frame = sample / SAMPLES_PER_FRAME;
                long frameEnd = Math.min(to, (frame + 1) * SAMPLES_PER_FRAME);

                double energy = 0;
                int valueStart = (int) (sample - firstSample) * channels;
                int valueEnd = (int) (frameEnd - firstSample) * channels;
                if (floatPcm) {
                    for (int i = valueStart; i < valueEnd; i++) {
                        double value = floatSamples[i] * Short.MAX_VALUE;
                        energy += value * value;
                    }
                } else {
                    for (int i = valueStart; i < valueEnd; i++) {
                        int value = shortSamples[i];
                        energy += value * value;
                    }
                }
                result.add((int) frame, energy, valueEnd - valueStart);

                sample = frameEnd;
            }
        }

        private static long toSample(long timeUs, int sampleRate) {
            return Math.round(timeUs * (double) sampleRate / 1_000_000);
        }
    }

    /**
     * Summed squared sample values per frame, for the frames of one segment.
     */
    private static final class FrameEnergy {
        private final int firstFrame;
        private double[] energy = new double[1024];
        private int[] values = new int[1024];
        private int size;

        FrameEnergy(int firstFrame) {
            this.firstFrame = firstFrame;
        }

        void add(int frame, double frameEnergy, int valueCount) {
            int index = frame - firstFrame;
            if (index < 0) {
                return;
            }
            if (index >= energy.length) {
                int capacity = Math.max(energy.length * 2, index + 1);
                energy = Arrays.copyOf(energy, capacity);
                values = Arrays.copyOf(values, capacity);
            }
            energy[index] += frameEnergy;
            values[index] += valueCount;
            size = Math.max(size, index + 1);
        }

        int getEndFrame() {
            return firstFrame + size;
        }

        void addTo(double[] totalEnergy, int[] totalValues) {
            for (int i = 0; i < size; i++) {
                totalEnergy[firstFrame + i] += energy[i];
                totalValues[firstFrame + i] += values[i];
            }
        }
    }

    /**
     * @return Cache file for the audio file, or null if the app context is unavailable.
     */
    private static File getCacheFile(File file) {
        Context context = AppResources.getAppContext();
        if (context == null) {
            return null;
        }

        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return null;
        }
        return new File(directory, Integer.toHexString(file.getAbsolutePath().hashCode()) + ".bin");
    }

    /**
     * Identifies the file contents. A changed size or modification time means new audio.
     */
    private static String getCacheKey(File file) {
        return file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified();
    }

    private boolean readCache(File cacheFile, String cacheKey) {
        if (!cacheFile.isFile()) {
            return false;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile)))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION
                    || !in.readUTF().equals(cacheKey)) {
                return false;
            }

            int cachedSampleRate = in.readInt();
            int count = in.readInt();
            byte[] bytes = new byte[count];
            in.readFully(bytes);

            int[] gains = new int[count];
            for (int i = 0; i < count; i++) {
                gains[i] = bytes[i] & 0xFF;
            }
            frameGains = gains;
            numFrames = count;
            sampleRate = cachedSampleRate;
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Could not read waveform cache " + cacheFile, e);
            return false;
        }
    }

    private void writeCache(File cacheFile, String cacheKey) {
        File temporaryFile = new File(cacheFile.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporaryFile)))) {
            out.writeInt(CACHE_MAGIC);
            out.writeInt(CACHE_VERSION);
            out.writeUTF(cacheKey);
            out.writeInt(sampleRate);
            out.writeInt(numFrames);

            // Gains are 0-255.
            byte[] bytes = new byte[numFrames];
            for (int i = 0; i < numFrames; i++) {
                bytes[i] = (byte) frameGains[i];
            }
            out.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "Could not write waveform cache " + cacheFile, e);
            //noinspection ResultOfMethodCallIgnored
            temporaryFile.delete();
            return;
        }

        if (!temporaryFile.renameTo(cacheFile)) {
            //noinspection ResultOfMethodCallIgnored
            temporaryFile.delete();
        }
    }
}
