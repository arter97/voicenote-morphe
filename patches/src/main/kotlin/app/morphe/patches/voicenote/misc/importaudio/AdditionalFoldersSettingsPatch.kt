package app.morphe.patches.voicenote.misc.importaudio

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Must match AdditionalFoldersSettings in the extension.
 */
private const val CATEGORY_KEY = "morphe_preference_category_additional_folders"
private const val ADD_FOLDER_KEY = "morphe_import_add_folder"

/**
 * Stock category the new one is inserted before. Present in all supported versions.
 */
private const val PRIVACY_CATEGORY_KEY = "setting_preference_category_privacy"

/**
 * Adds an "Additional folders" category to the app settings screen. The extension fills
 * it with one row per folder above the "Add folder" row, which opens the system folder
 * chooser from the settings screen. No manifest entries are added, as installs that mount
 * the patched APK over the original keep the original manifest.
 */
internal val additionalFoldersSettingsPatch = resourcePatch {
    execute {
        // Phone and tablet layouts of the same screen.
        arrayOf("settings_preference.xml", "settings_preference_tablet.xml").forEach { file ->
            document("res/xml/$file").use { document ->
                val screen = document.documentElement

                val category = document.createElement("androidx.preference.PreferenceCategory").apply {
                    setAttribute("android:key", CATEGORY_KEY)
                    setAttribute("android:title", "Additional folders")
                }
                category.appendChild(
                    document.createElement("androidx.preference.Preference").apply {
                        setAttribute("android:key", ADD_FOLDER_KEY)
                        setAttribute("android:title", "Add folder")
                        setAttribute(
                            "android:summary",
                            "Import audio files from another folder. Each folder appears " +
                                    "in the recordings list menu."
                        )
                        setAttribute("android:persistent", "false")
                        // Below the folder rows, which are added in list order.
                        setAttribute("android:order", "10000")
                    }
                )

                // Direct child of the screen, so the app's runtime removal and re-adding of
                // items in its own categories does not affect it.
                val privacyCategory = (0 until screen.childNodes.length)
                    .map { screen.childNodes.item(it) }
                    .firstOrNull { node ->
                        node is Element && node.getAttribute("android:key") == PRIVACY_CATEGORY_KEY
                    }
                screen.insertBefore(category, privacyCategory)
            }
        }
    }
}
