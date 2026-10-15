package androidx.preference;

import android.content.Context;
import android.content.Intent;

public abstract class PreferenceFragmentCompat {
    public Preference findPreference(CharSequence key) {
        throw new UnsupportedOperationException("Stub");
    }

    public Context getContext() {
        throw new UnsupportedOperationException("Stub");
    }

    public void startActivityForResult(Intent intent, int requestCode) {
        throw new UnsupportedOperationException("Stub");
    }
}
