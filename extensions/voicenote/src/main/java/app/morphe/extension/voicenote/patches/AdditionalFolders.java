package app.morphe.extension.voicenote.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;

import com.sec.android.app.voicenote.common.util.AppResources;
import com.sec.android.app.voicenote.data.VRDatabaseUpdater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User chosen folders to import audio files from, in addition to the Recordings folder.
 * Managed from the "Additional folders" list on the app settings screen.
 */
final class AdditionalFolders {

    /**
     * Preference key in the app's default "&lt;package&gt;_preferences" SharedPreferences,
     * which the app's own Settings class also uses.
     */
    static final String PREFERENCE_KEY = "morphe_import_additional_folders";

    /**
     * Folders are stored one per line, each followed by a line break. Values without any
     * line break are from the earlier comma-separated text field.
     */
    private static final String SEPARATOR = "\n";
    private static final String LEGACY_SEPARATOR = ",";

    /**
     * Prefixes that MediaStore reports as /storage/emulated/0.
     */
    private static final String[] PRIMARY_STORAGE_ALIASES = {"/sdcard", "/storage/self/primary"};
    private static final String PRIMARY_STORAGE = "/storage/emulated/0";

    private static final Config EMPTY_CONFIG = new Config("", Collections.emptyList());

    private static SharedPreferences preferences;

    private static volatile Config config = EMPTY_CONFIG;

    private AdditionalFolders() {
    }

    /**
     * @return The configured folders, without trailing slash. Parsed once per setting value.
     */
    static List<String> get() {
        return getConfig().folders;
    }

    /**
     * Adds a folder and rescans the media library.
     *
     * @return If the folder was not configured yet.
     */
    static boolean add(String folder) {
        String normalized = normalize(folder);
        List<String> folders = new ArrayList<>(get());
        if (normalized == null || folders.contains(normalized)) {
            return false;
        }

        folders.add(normalized);
        save(folders);
        scan(normalized);
        return true;
    }

    /**
     * Asks the media library to rescan a folder, then imports what it found. The media
     * library may hold files there as non-audio without a duration, for example if the
     * folder was once hidden with a .nomedia file, and such files cannot be imported.
     * Scanning a folder scans everything inside it.
     */
    private static void scan(String folder) {
        Context context = AppResources.getAppContext();
        if (context == null) {
            return;
        }

        MediaScannerConnection.scanFile(context, new String[]{folder}, null, (path, uri) ->
                VRDatabaseUpdater.getInstance().syncVRProviderWithMediaProvider());
    }

    /**
     * Removes a folder and rescans the media library, which drops its recordings.
     */
    static void remove(String folder) {
        List<String> folders = new ArrayList<>(get());
        if (folders.remove(folder)) {
            save(folders);
        }
    }

    /**
     * @return MediaStore selection matching files in any configured folder, as
     * " OR ..." clauses to append to an OR chain, or an empty string.
     */
    static String getSelection() {
        return getConfig().selection;
    }

    /**
     * @return Index of the configured folder containing the path, or -1.
     */
    static int indexOf(String path) {
        if (path == null) {
            return -1;
        }

        List<String> prefixes = getConfig().prefixes;
        for (int i = 0, size = prefixes.size(); i < size; i++) {
            String prefix = prefixes.get(i);
            if (path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return i;
            }
        }
        return -1;
    }

    static boolean contains(String path) {
        return indexOf(path) >= 0;
    }

    static String getName(String folder) {
        return folder.substring(folder.lastIndexOf('/') + 1);
    }

    /**
     * @return The SharedPreferences holding the setting, or null if the app context is
     * not available yet.
     */
    static SharedPreferences getPreferences() {
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            Context context = AppResources.getAppContext();
            if (context == null) {
                return null;
            }
            prefs = context.getSharedPreferences(
                    context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            preferences = prefs;
        }
        return prefs;
    }

    private static void save(List<String> folders) {
        SharedPreferences prefs = getPreferences();
        if (prefs == null) {
            return;
        }

        StringBuilder value = new StringBuilder();
        for (String folder : folders) {
            value.append(folder).append(SEPARATOR);
        }
        prefs.edit().putString(PREFERENCE_KEY, value.toString()).apply();

        VRDatabaseUpdater.getInstance().syncVRProviderWithMediaProvider();
    }

    private static List<String> parse(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }

        String separator = value.contains(SEPARATOR) ? SEPARATOR : LEGACY_SEPARATOR;
        List<String> folders = new ArrayList<>();
        for (String entry : value.split(separator)) {
            String folder = normalize(entry.trim());
            if (folder != null && !folders.contains(folder)) {
                folders.add(folder);
            }
        }
        return folders;
    }

    private static Config getConfig() {
        SharedPreferences prefs = getPreferences();
        String value = prefs == null ? "" : prefs.getString(PREFERENCE_KEY, "");
        if (value == null) {
            value = "";
        }

        Config current = config;
        if (!current.value.equals(value)) {
            current = new Config(value, parse(value));
            config = current;
        }
        return current;
    }

    /**
     * @return Absolute folder path without trailing slash, or null if not usable.
     */
    private static String normalize(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }

        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) {
            return null;
        }

        for (String alias : PRIMARY_STORAGE_ALIASES) {
            if (path.equals(alias) || path.startsWith(alias + "/")) {
                return PRIMARY_STORAGE + path.substring(alias.length());
            }
        }
        return path;
    }

    /**
     * Escapes a value for a single-quoted SQL LIKE pattern with ESCAPE '\'.
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .replace("'", "''");
    }

    private static final class Config {
        final String value;
        final List<String> folders;
        final List<String> prefixes;
        final String selection;

        Config(String value, List<String> folders) {
            this.value = value;
            this.folders = Collections.unmodifiableList(folders);

            List<String> prefixes = new ArrayList<>(folders.size());
            StringBuilder selection = new StringBuilder();
            for (String folder : folders) {
                prefixes.add(folder + "/");
                selection.append(" OR _data LIKE '")
                        .append(escapeLike(folder))
                        .append("/%' ESCAPE '\\'");
            }
            this.prefixes = Collections.unmodifiableList(prefixes);
            this.selection = selection.toString();
        }
    }
}
