package com.sec.android.app.voicenote.engine;

import java.util.Map;

public class FastConvertController {
    public static FastConvertController getInstance() {
        throw new UnsupportedOperationException("Stub");
    }

    public void addFastConvertItem(long id, String language, boolean isForceTranscribeWithLanguage,
                                   boolean isReTranscribe, boolean isAutoSummary) {
        throw new UnsupportedOperationException("Stub");
    }

    public void cancelFastConvert(Long id) {
        throw new UnsupportedOperationException("Stub");
    }

    public long getCurrentFastConvertId() {
        throw new UnsupportedOperationException("Stub");
    }

    public String getCurrentFastConvertName() {
        throw new UnsupportedOperationException("Stub");
    }

    public Map<Long, FastConvertItem> getFastConvertList() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean isFileFastConvertingOrWaiting(long id) {
        throw new UnsupportedOperationException("Stub");
    }

    public Boolean isFileInWaitingList(Long id) {
        throw new UnsupportedOperationException("Stub");
    }

    public int numberFilesConverting() {
        throw new UnsupportedOperationException("Stub");
    }

    public void registerFastConvertStateChangeListener(FastConvertStateChangeListener listener) {
        throw new UnsupportedOperationException("Stub");
    }

    public static class FastConvertItem {
        public int getConvertState() {
            throw new UnsupportedOperationException("Stub");
        }
    }

    /**
     * The other methods of the real interface also have default implementations.
     */
    public interface FastConvertStateChangeListener {
        void notifyFastConvertStatusChanged(Long id, Integer state, String error);

        default void notifyFastConvertProgressUpdated(long id, int percent) {
        }
    }
}
