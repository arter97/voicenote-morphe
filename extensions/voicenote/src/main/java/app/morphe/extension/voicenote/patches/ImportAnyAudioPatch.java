package app.morphe.extension.voicenote.patches;

import android.content.Intent;
import android.os.Process;

import androidx.preference.PreferenceFragmentCompat;

import com.sec.android.app.voicenote.common.util.AppResources;
import com.sec.android.app.voicenote.common.util.Settings;
import com.sec.android.app.voicenote.data.ExtractorSound;
import com.sec.android.app.voicenote.data.MetadataProvider;
import com.sec.android.app.voicenote.data.VNDatabase;
import com.sec.android.app.voicenote.data.WaveMaker;
import com.sec.android.app.voicenote.data.db.RecordingItemDAO;
import com.sec.android.app.voicenote.data.entity.RecordingItem;
import com.sec.android.app.voicenote.engine.Engine;
import com.sec.android.app.voicenote.main.NavigationController;
import com.sec.android.app.voicenote.ui.fragment.wave.AbsAsyncLoadWave;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class ImportAnyAudioPatch {

    /**
     * Ogg audio extension.
     */
    private static final String OGA_EXTENSION = ".oga";

    /**
     * MediaStore selection for any audio file inside the top-level Recordings directory
     * of any storage volume, or any .oga file. The additional folder clauses are appended
     * to this OR chain.
     */
    private static final String IMPORTED_FILES_SCOPE_SELECTION =
            "relative_path LIKE 'Recordings/%' OR _data LIKE '%" + OGA_EXTENSION + "'";

    /**
     * The temp folder exclusion and empty file check mirror the stock base query. Files
     * without a duration are skipped as the app cannot use them.
     */
    private static final String IMPORTED_FILES_CONDITIONS =
            " AND _data NOT LIKE '%/.393857/%'"
                    + " AND _size != '0'"
                    + " AND duration > 0";

    /**
     * Recordings folder scope of {@link #IMPORTED_FILES_SCOPE_SELECTION}, applied to paths
     * such as /storage/emulated/0/Recordings/... or /storage/1234-ABCD/Recordings/...
     */
    private static final Pattern RECORDINGS_FOLDER_PATH = Pattern.compile(
            "^/storage/(?:emulated/\\d+|[^/]+)/Recordings/",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Value of recording_items.recordingtype for files recorded by the app itself.
     */
    private static final int RECORDING_TYPE_VOICE_RECORDER = 1;

    /**
     * Recording mode of a plain voice recording (no interview or speech-to-text data).
     */
    private static final int RECORDING_MODE_NORMAL = 1;

    /**
     * Category id of the first additional folder in the left pane. Further folders count
     * down from it. Negative ids are never offered as targets for moving recordings, and
     * this range is unused by the app's own filters (-3 to 10, 99, 100 and up).
     */
    private static final int FOLDER_CATEGORY_ID_START = -1000;
    private static final int FOLDER_CATEGORY_ID_END = -1999;

    /**
     * App setting holding the category selected in the left pane.
     */
    private static final String KEY_LIST_MODE = "last_selected_category_in_list";
    private static final int CATEGORY_ALL_RECORDINGS = -1;

    /**
     * Injection point.
     * <p>
     * The stock MediaStore selection only matches .3ga/.amr/.m4a files. It gates both
     * the sync into the app database and every list query, so OR it with any audio file
     * in the Recordings folder or an additional folder and any .oga file. The whole
     * selection is parenthesized because callers append further " and ..." clauses.
     */
    public static void widenBaseQuery(StringBuilder query) {
        query.insert(0, "((")
                .append(") OR ((")
                .append(IMPORTED_FILES_SCOPE_SELECTION)
                .append(AdditionalFolders.getSelection())
                .append(")")
                .append(IMPORTED_FILES_CONDITIONS)
                .append("))");
    }

    /**
     * Injection point.
     * <p>
     * Called with every value M4aReader.readRecordingTypeAndRecordingMode() returns.
     * Stock returns null unless the file's moov/udta box holds Samsung's smta/saut atom,
     * so files from other recorders are imported as "other recordings". For existing
     * .m4a files in the Recordings folder or an additional folder, report a normal voice
     * recording instead.
     *
     * @return {recording type, recording mode, has bookmarks, is watch recording}
     */
    public static int[] overrideRecordingTypeAndRecordingMode(int[] result, String path) {
        if (result != null
                || !(isInRecordingsFolder(path) || AdditionalFolders.contains(path))
                || !path.toLowerCase(Locale.ROOT).endsWith(".m4a")
                || !new File(path).exists()) {
            return result;
        }

        return new int[]{RECORDING_TYPE_VOICE_RECORDER, RECORDING_MODE_NORMAL, 0, 0};
    }

    /**
     * Injection point.
     * <p>
     * Replaces M4aReader.isM4A(path) where it only gates Transcribe and the AI actions.
     * The app decodes other formats with MediaTool or MediaCodec and stores transcripts in
     * its database. AMR (.amr, .3ga) stays excluded as MediaTool cannot decode it and the
     * app's own .3ga recordings get no MediaCodec fallback.
     */
    public static boolean isTranscribable(String path) {
        if (path == null) {
            return false;
        }

        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        return !lowerCasePath.endsWith(".amr") && !lowerCasePath.endsWith(".3ga");
    }

    private static final ExtractorSound.Factory DECODED_AUDIO_EXTRACTOR_FACTORY = DecodedAudioExtractor::new;

    /**
     * Extensions handled by the app's own waveform extractors (ExtractorSound), which only
     * parse the container and finish quickly.
     */
    private static final String[] STOCK_WAVE_EXTENSIONS = {"aac", "m4a", "3ga", "3gpp", "3gp", "amr"};

    /**
     * Runs waveform generation that has to decode the whole file. One thread per file, so
     * a long recording does not hold up the waveform of the next file opened.
     */
    private static final ExecutorService WAVE_DECODE_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            runnable.run();
        }, "MorpheWaveDecode");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Injection point.
     * <p>
     * Replaces WaveMaker.decode(path). Stock runs it synchronously while the file's
     * metadata is read, so opening the player waits for it. That is fine for the app's
     * container parsers, but decoding a long recording takes a while. Run those in the
     * background instead. The waveform loaders wait for and redraw the result.
     */
    public static void decodeWave(WaveMaker waveMaker, String path) {
        if (path == null || isStockWaveFormat(path)) {
            waveMaker.decode$morphe(path);
            return;
        }

        PENDING_WAVE_PATHS.add(path);
        WAVE_DECODE_EXECUTOR.execute(() -> {
            try {
                waveMaker.decode$morphe(path);
            } finally {
                PENDING_WAVE_PATHS.remove(path);
            }
        });
    }

    /**
     * Files whose waveform is being decoded in the background.
     */
    private static final Set<String> PENDING_WAVE_PATHS = ConcurrentHashMap.newKeySet();

    /**
     * Version marker the app stores in the first element of waveform data.
     */
    private static final int WAVE_DATA_VERSION = 2;

    /**
     * Milliseconds per waveform data point, as generated by WaveMaker.
     */
    private static final int MS_PER_WAVE_POINT = 35;

    /**
     * Injection point.
     * <p>
     * Called after MetadataWaveHelper starts generating a waveform, with the waveform data
     * it holds. While a background decode is running there is none, and the waveform
     * view would stay empty. Return a flat waveform of the right length instead, so the
     * recording can already be scrolled to seek. It is replaced when decoding finishes.
     */
    public static int[] getWaveDataWhileDecoding(int[] waveData, String path, long durationMs) {
        if (waveData != null || path == null || !PENDING_WAVE_PATHS.contains(path)) {
            return waveData;
        }

        int[] placeholder = new int[(int) Math.max(1, durationMs / MS_PER_WAVE_POINT)];
        placeholder[0] = WAVE_DATA_VERSION;
        return placeholder;
    }

    /**
     * Injection point.
     * <p>
     * Start of a waveform loader's wait check. Stock waits for a waveform being generated
     * and shows nothing until then. For a background decode, register the same reload on
     * completion that stock uses, and let the loader draw the flat placeholder right away.
     *
     * @return If the loader should skip waiting.
     */
    public static boolean drawWhileWaveDecodes(AbsAsyncLoadWave loader, String path) {
        if (path == null || !PENDING_WAVE_PATHS.contains(path)) {
            return false;
        }

        MetadataProvider.setMetadataListener(path, (waveDataSize, unused) -> {
            // Stock reloads the current file, which may have changed in the meantime.
            if (path.equals(Engine.getInstance().getPath())) {
                loader.start(path);
            }
        });
        return true;
    }

    private static boolean isStockWaveFormat(String path) {
        String name = new File(path).getName().toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        for (String stockExtension : STOCK_WAVE_EXTENSIONS) {
            if (stockExtension.equals(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Return value of ExtractorSound.getFactoryForExtension(extension), which picks the
     * waveform source and only knows MP4/AAC and AMR. Without one the waveform is empty.
     */
    public static ExtractorSound.Factory getWaveExtractorFactory(ExtractorSound.Factory stockFactory) {
        return stockFactory != null ? stockFactory : DECODED_AUDIO_EXTRACTOR_FACTORY;
    }

    /**
     * Injection point.
     * <p>
     * Replaces path.endsWith(".m4a") in the waveform loaders, which only wait for the
     * waveform being generated in the background for .m4a, .amr and .3ga files. Other
     * formats would be drawn from empty data and never refreshed. Whether generation is
     * actually running is checked right after.
     */
    public static boolean isWaveGeneratedFile(String path, String m4aExtension) {
        return path != null;
    }

    /**
     * Injection point.
     * <p>
     * Replaces the "All recordings" list source, which stock limits to files recorded by
     * this app. Non-.m4a files the app did not record keep their "other recording" type,
     * so the app stores their AI data and bookmarks in its database.
     */
    public static List<RecordingItem> getAllRecordings(RecordingItemDAO dao) {
        List<RecordingItem> items = dao.getVRFiles();
        if (items == null) {
            return null;
        }

        List<RecordingItem> result = new ArrayList<>(items.size());
        for (RecordingItem item : items) {
            Integer recordingType = item.getRecordingType();
            String path = item.getPath();
            if ((recordingType != null && recordingType == RECORDING_TYPE_VOICE_RECORDER)
                    || isInRecordingsFolder(path) || isOgaFile(path)
                    || AdditionalFolders.contains(path)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Injection point.
     * <p>
     * Called on a background thread with the left pane entries right before they are
     * shown. Appends one entry per additional folder, labeled with the folder name.
     */
    public static void addFolderDrawerItems(List<Object> drawerItems) {
        List<String> folders = AdditionalFolders.get();

        // A folder removed from the setting may still be the selected category.
        int selectedCategoryId = Settings.getIntSettings(KEY_LIST_MODE, CATEGORY_ALL_RECORDINGS);
        if (getFolder(selectedCategoryId) == null && isFolderCategoryId(selectedCategoryId)) {
            Settings.setSettings(KEY_LIST_MODE, CATEGORY_ALL_RECORDINGS);
        }

        if (folders.isEmpty()) {
            return;
        }

        int[] counts = new int[folders.size()];
        for (RecordingItem item : getRecordingItemDAO().getVRFiles()) {
            int index = AdditionalFolders.indexOf(item.getPath());
            if (index >= 0) {
                counts[index]++;
            }
        }

        for (int i = 0, size = folders.size(); i < size; i++) {
            drawerItems.add(new NavigationController.DrawerItem(
                    getFolderCategoryId(i), AdditionalFolders.getName(folders.get(i)), counts[i]));
        }
    }

    /**
     * Injection point.
     * <p>
     * Start of RecordingItemRepository.getRecordingItemListByCategory(categoryId).
     *
     * @return Recordings in the additional folder, or null if the id is not a folder.
     */
    public static List<RecordingItem> getFolderRecordings(int categoryId) {
        if (!isFolderCategoryId(categoryId)) {
            return null;
        }

        RecordingItemDAO dao = getRecordingItemDAO();
        String folder = getFolder(categoryId);
        if (folder == null) {
            // Removed folder still selected. Its title falls back to "All recordings".
            return getAllRecordings(dao);
        }

        List<RecordingItem> items = dao.getVRFiles();
        List<RecordingItem> result = new ArrayList<>();
        String prefix = folder + "/";
        for (RecordingItem item : items) {
            String path = item.getPath();
            if (path != null && path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Injection point.
     * <p>
     * Start of RecordingItemRepository.getFileCountByCategory(categoryId). The app reloads
     * the list whenever this differs from the list size, so both must agree.
     *
     * @return Number of recordings in the additional folder, or -1 if not a folder.
     */
    public static int getFolderRecordingCount(int categoryId) {
        List<RecordingItem> recordings = getFolderRecordings(categoryId);
        return recordings == null ? -1 : recordings.size();
    }

    /**
     * Injection point.
     * <p>
     * Start of CategoryRepository.getCategoryName(categoryId, context), used for the title.
     *
     * @return Folder name, or null if the id is not a configured folder.
     */
    public static String getFolderCategoryName(int categoryId) {
        String folder = getFolder(categoryId);
        return folder == null ? null : AdditionalFolders.getName(folder);
    }

    /**
     * Injection point.
     * <p>
     * End of the settings screen's onCreatePreferences(). Fills in the additional folders
     * list.
     */
    public static void onSettingsCreated(PreferenceFragmentCompat fragment) {
        AdditionalFoldersSettings.bind(fragment);
    }

    /**
     * Injection point.
     * <p>
     * Settings screen's onActivityResult(), which receives the folder chosen in the system
     * folder chooser.
     */
    public static void onSettingsActivityResult(PreferenceFragmentCompat fragment, int requestCode,
                                                int resultCode, Intent data) {
        AdditionalFoldersSettings.onActivityResult(fragment, requestCode, resultCode, data);
    }

    private static RecordingItemDAO getRecordingItemDAO() {
        return VNDatabase.getInstance(AppResources.getAppContext()).mRecordingItemDAO();
    }

    private static boolean isFolderCategoryId(int categoryId) {
        return categoryId <= FOLDER_CATEGORY_ID_START && categoryId >= FOLDER_CATEGORY_ID_END;
    }

    private static int getFolderCategoryId(int index) {
        return FOLDER_CATEGORY_ID_START - index;
    }

    /**
     * @return The configured folder for the category id, or null.
     */
    private static String getFolder(int categoryId) {
        if (!isFolderCategoryId(categoryId)) {
            return null;
        }

        List<String> folders = AdditionalFolders.get();
        int index = FOLDER_CATEGORY_ID_START - categoryId;
        return index < folders.size() ? folders.get(index) : null;
    }

    private static boolean isInRecordingsFolder(String path) {
        return path != null && RECORDINGS_FOLDER_PATH.matcher(path).find();
    }

    private static boolean isOgaFile(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(OGA_EXTENSION);
    }
}
