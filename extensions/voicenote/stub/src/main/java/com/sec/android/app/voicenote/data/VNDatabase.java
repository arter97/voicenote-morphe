package com.sec.android.app.voicenote.data;

import android.content.Context;

import com.sec.android.app.voicenote.data.db.RecordingItemDAO;

public abstract class VNDatabase {
    public static VNDatabase getInstance(Context context) {
        throw new UnsupportedOperationException("Stub");
    }

    public abstract RecordingItemDAO mRecordingItemDAO();
}
