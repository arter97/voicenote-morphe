package com.sec.android.app.voicenote.data.db;

import com.sec.android.app.voicenote.data.entity.RecordingItem;

import java.util.List;

public interface RecordingItemDAO {
    /**
     * Room query: {@code SELECT * FROM recording_items WHERE is_trash = 0}
     */
    List<RecordingItem> getVRFiles();
}
