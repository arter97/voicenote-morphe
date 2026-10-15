package app.morphe.patches.voicenote.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_VOICENOTE = Compatibility(
        name = "Samsung Voice Recorder",
        packageName = "com.sec.android.app.voicenote",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF0473D,
        signatures = setOf(
            "34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42"
        ),
        targets = listOf(
            AppTarget(
                version = "21.5.91.30",
                minSdk = 35
            ),
            AppTarget(
                version = "21.5.85.69",
                minSdk = 35
            )
        )
    )
}
