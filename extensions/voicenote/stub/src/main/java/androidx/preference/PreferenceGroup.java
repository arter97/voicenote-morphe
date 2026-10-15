package androidx.preference;

import android.content.Context;

public abstract class PreferenceGroup extends Preference {
    public PreferenceGroup(Context context) {
        super(context);
    }

    public boolean addPreference(Preference preference) {
        throw new UnsupportedOperationException("Stub");
    }

    public Preference getPreference(int index) {
        throw new UnsupportedOperationException("Stub");
    }

    public int getPreferenceCount() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean removePreference(Preference preference) {
        throw new UnsupportedOperationException("Stub");
    }
}
