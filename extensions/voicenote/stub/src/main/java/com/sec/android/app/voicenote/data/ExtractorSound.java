package com.sec.android.app.voicenote.data;

import java.io.File;

public class ExtractorSound {
    public interface Factory {
        ExtractorSound create();
    }

    public int[] getFrameGains() {
        throw new UnsupportedOperationException("Stub");
    }

    public int getNumFrames() {
        throw new UnsupportedOperationException("Stub");
    }

    public int getSampleRate() {
        throw new UnsupportedOperationException("Stub");
    }

    public int getSamplesPerFrame() {
        throw new UnsupportedOperationException("Stub");
    }

    public void readFile(File file) {
        throw new UnsupportedOperationException("Stub");
    }
}
