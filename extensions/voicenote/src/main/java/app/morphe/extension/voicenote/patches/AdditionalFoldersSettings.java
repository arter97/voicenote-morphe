package app.morphe.extension.voicenote.patches;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * "Additional folders" list on the app settings screen: one row per folder with its name,
 * full path and a remove button, followed by an "Add folder" row that opens the system
 * folder chooser. The category and the "Add folder" row come from the settings XML.
 */
final class AdditionalFoldersSettings {
    /**
     * Must match the keys in the patched settings XML.
     */
    private static final String CATEGORY_KEY = "morphe_preference_category_additional_folders";
    private static final String ADD_FOLDER_KEY = "morphe_import_add_folder";

    private static final String FOLDER_KEY_PREFIX = "morphe_import_folder:";

    private static final String TAG = "AdditionalFolders";

    /**
     * Fragment request codes must fit in 16 bits.
     */
    private static final int REQUEST_CHOOSE_FOLDER = 0x4D46;

    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String PRIMARY_VOLUME = "primary";

    /**
     * Rebuilds the rows of the shown settings screen when the folders change. Kept
     * strongly, as SharedPreferences only holds listeners weakly.
     */
    private static SharedPreferences.OnSharedPreferenceChangeListener foldersListener;

    private static int deleteIconId;

    private AdditionalFoldersSettings() {
    }

    static void bind(PreferenceFragmentCompat fragment) {
        Preference category = fragment.findPreference(CATEGORY_KEY);
        Preference addFolder = fragment.findPreference(ADD_FOLDER_KEY);
        if (!(category instanceof PreferenceGroup) || addFolder == null) {
            return;
        }

        // Started from the settings screen, as patched installs that keep the original
        // package registration cannot declare an activity of their own.
        addFolder.setOnPreferenceClickListener(preference -> {
            try {
                fragment.startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CHOOSE_FOLDER);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No folder chooser", e);
            }
            return true;
        });

        PreferenceGroup group = (PreferenceGroup) category;
        showFolders(group);

        SharedPreferences preferences = AdditionalFolders.getPreferences();
        if (preferences == null) {
            return;
        }
        if (foldersListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(foldersListener);
        }
        WeakReference<PreferenceGroup> groupReference = new WeakReference<>(group);
        foldersListener = (changedPreferences, key) -> {
            PreferenceGroup shownGroup = groupReference.get();
            if (shownGroup != null && AdditionalFolders.PREFERENCE_KEY.equals(key)) {
                showFolders(shownGroup);
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(foldersListener);
    }

    /**
     * Result of the settings screen's activities, forwarded by the patch.
     */
    static void onActivityResult(PreferenceFragmentCompat fragment, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CHOOSE_FOLDER || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        Context context = fragment.getContext();
        if (context == null) {
            return;
        }

        String path = toPath(data.getData());
        if (path == null) {
            Toast.makeText(context, "This folder can't be used. Choose a folder on device storage.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!AdditionalFolders.add(path)) {
            Toast.makeText(context, "Folder already added", Toast.LENGTH_SHORT).show();
            return;
        }

        // Needed to transcribe, rename or delete files the app did not create.
        if (!Environment.isExternalStorageManager()) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + context.getPackageName())));
            } catch (ActivityNotFoundException e) {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    /**
     * Converts a folder chosen in the system chooser to an absolute path, such as
     * tree document "primary:BCR" to /storage/emulated/0/BCR or "1234-ABCD:Calls" to
     * /storage/1234-ABCD/Calls.
     *
     * @return The path, or null if the folder is not on local storage.
     */
    private static String toPath(Uri treeUri) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) {
            return null;
        }

        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return null;
        }

        int separator = documentId.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        String volume = documentId.substring(0, separator);
        String relativePath = documentId.substring(separator + 1);

        String volumeRoot = PRIMARY_VOLUME.equals(volume)
                ? Environment.getExternalStorageDirectory().getAbsolutePath()
                : "/storage/" + volume;
        return relativePath.isEmpty() ? volumeRoot : volumeRoot + "/" + relativePath;
    }

    private static void showFolders(PreferenceGroup group) {
        for (int i = group.getPreferenceCount() - 1; i >= 0; i--) {
            Preference preference = group.getPreference(i);
            String key = preference.getKey();
            if (key != null && key.startsWith(FOLDER_KEY_PREFIX)) {
                group.removePreference(preference);
            }
        }

        List<String> folders = AdditionalFolders.get();
        for (int i = 0, size = folders.size(); i < size; i++) {
            FolderPreference preference = new FolderPreference(group.getContext(), folders.get(i));
            preference.setOrder(i);
            group.addPreference(preference);
        }
    }

    /**
     * Folder name as title, full path as summary, and a remove button on the right.
     */
    private static final class FolderPreference extends Preference {
        private final String folder;

        FolderPreference(Context context, String folder) {
            super(context);
            this.folder = folder;
            setKey(FOLDER_KEY_PREFIX + folder);
            setPersistent(false);
            setTitle(AdditionalFolders.getName(folder));
            setSummary(folder);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);

            View widgetFrame = holder.findViewById(android.R.id.widget_frame);
            if (!(widgetFrame instanceof ViewGroup)) {
                return;
            }
            ViewGroup frame = (ViewGroup) widgetFrame;
            frame.removeAllViews();
            frame.addView(createRemoveButton(frame.getContext()));
            frame.setVisibility(View.VISIBLE);
        }

        private ImageButton createRemoveButton(Context context) {
            ImageButton button = new ImageButton(context);
            int iconId = getDeleteIconId(context);
            if (iconId != 0) {
                button.setImageResource(iconId);
            } else {
                button.setImageResource(android.R.drawable.ic_menu_delete);
            }
            button.setContentDescription("Remove " + AdditionalFolders.getName(folder));
            button.setImageTintList(resolveColorStateList(context, android.R.attr.textColorSecondary));

            TypedValue background = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, background, true)) {
                button.setBackgroundResource(background.resourceId);
            } else {
                button.setBackground(null);
            }

            // Not focusable, so the row keeps its own touch feedback.
            button.setFocusable(false);
            button.setOnClickListener(view -> AdditionalFolders.remove(folder));
            return button;
        }

        /**
         * The app's own delete icon, resolved by name so it does not depend on resource ids.
         */
        private static int getDeleteIconId(Context context) {
            if (deleteIconId == 0) {
                deleteIconId = context.getResources().getIdentifier(
                        "ic_delete", "drawable", context.getPackageName());
            }
            return deleteIconId;
        }

        private static android.content.res.ColorStateList resolveColorStateList(Context context, int attribute) {
            TypedArray array = context.obtainStyledAttributes(new int[]{attribute});
            try {
                return array.getColorStateList(0);
            } finally {
                array.recycle();
            }
        }
    }
}
