package androidx.preference;

import android.content.Context;

public class Preference {
    public Preference(Context context) {
        throw new UnsupportedOperationException("Stub");
    }

    public Context getContext() {
        throw new UnsupportedOperationException("Stub");
    }

    public String getKey() {
        throw new UnsupportedOperationException("Stub");
    }

    public void onBindViewHolder(PreferenceViewHolder holder) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setIconSpaceReserved(boolean iconSpaceReserved) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setKey(String key) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setOnPreferenceClickListener(OnPreferenceClickListener listener) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setOrder(int order) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setPersistent(boolean persistent) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setSummary(CharSequence summary) {
        throw new UnsupportedOperationException("Stub");
    }

    public void setTitle(CharSequence title) {
        throw new UnsupportedOperationException("Stub");
    }

    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }
}
