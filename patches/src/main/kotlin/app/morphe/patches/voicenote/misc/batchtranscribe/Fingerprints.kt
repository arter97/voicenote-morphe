package app.morphe.patches.voicenote.misc.batchtranscribe

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal const val ADD_REMOVE_OPTION_MENU_HELPER_CLASS =
    "Lcom/sec/android/app/voicenote/ui/actionbar/AddRemoveOptionMenuHelper;"

internal const val FAST_CONVERT_CONTROLLER_CLASS =
    "Lcom/sec/android/app/voicenote/engine/FastConvertController;"

private const val ADD_REMOVE_OPTION_MENU_CLASS =
    "Lcom/sec/android/app/voicenote/ui/actionbar/AddRemoveOptionMenu;"

/**
 * Whether the selection's bottom menu has Transcribe. Only true for a single eligible
 * recording while nothing is transcribing.
 */
internal object NeedInflateTranscribeMenuFingerprint : Fingerprint(
    definingClass = ADD_REMOVE_OPTION_MENU_HELPER_CLASS,
    name = "needInflateTranscribeMenu",
    returnType = "Z",
    parameters = listOf()
)

/**
 * Updates the selection's bottom menu after the selection changed. Older app versions
 * have no trailing boolean parameter.
 */
internal object UpdateSelectMenuFingerprint : Fingerprint(
    definingClass = ADD_REMOVE_OPTION_MENU_CLASS,
    name = "updateSelectMenu",
    returnType = "V"
)

/**
 * Selection menu of short landscape windows, where Transcribe runs the single recording
 * re-transcribe action.
 */
internal object AddSelectionMenuForLandscapeFingerprint : Fingerprint(
    definingClass = ADD_REMOVE_OPTION_MENU_CLASS,
    name = "addSelectionMenuForLandscape",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = ADD_REMOVE_OPTION_MENU_HELPER_CLASS,
            name = "needInflateTranscribeMenu",
            returnType = "Z"
        )
    )
)

/**
 * Bundle for the Transcribe button of the selection, holding every checked recording.
 */
internal object BundleHandleTranscribeFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/actionbar/RunOptionMenuHelper;",
    name = "getBundleHandleTranscribe",
    returnType = "Landroid/os/Bundle;",
    parameters = listOf()
)

private const val TRANSCRIBE_EXECUTOR_CLASS =
    "Lcom/sec/android/app/voicenote/engine/TranscribeExecutor;"

/**
 * Starts transcribing with auto-detected language, or shows the language dialog.
 */
internal object ExecuteTranscribeFingerprint : Fingerprint(
    definingClass = TRANSCRIBE_EXECUTOR_CLASS,
    name = "executeTranscribe",
    returnType = "V"
)

/**
 * Queues the recording for transcription with auto-detected or the given language.
 */
internal object ExecuteTranscribeWithAutoDetectFingerprint : Fingerprint(
    definingClass = TRANSCRIBE_EXECUTOR_CLASS,
    name = "executeTranscribeWithAutoDetect",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = FAST_CONVERT_CONTROLLER_CLASS,
            name = "addFastConvertItem",
            parameters = listOf("J", "Ljava/lang/String;", "Z", "Z", "Z"),
            returnType = "V"
        )
    )
)

internal object AddFastConvertItemFingerprint : Fingerprint(
    definingClass = FAST_CONVERT_CONTROLLER_CLASS,
    name = "addFastConvertItem",
    returnType = "V",
    parameters = listOf("J", "Ljava/lang/String;", "Z", "Z", "Z")
)

/**
 * Starts transcribing a recording. Recordings that waited in the queue are started
 * without the force-language and auto-summary flags.
 */
internal object StartFastConvertFingerprint : Fingerprint(
    definingClass = FAST_CONVERT_CONTROLLER_CLASS,
    name = "startFastConvert",
    returnType = "V",
    parameters = listOf(
        "J", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z"
    )
)

internal object CancelFastConvertFingerprint : Fingerprint(
    definingClass = FAST_CONVERT_CONTROLLER_CLASS,
    name = "cancelFastConvert",
    returnType = "V",
    parameters = listOf("Ljava/lang/Long;")
)

/**
 * Cancel button of the ongoing transcription notification.
 */
internal object FastConvertServiceCancelFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/service/FastConvertService;",
    name = "cancelFastConvert",
    returnType = "V",
    parameters = listOf()
)

/**
 * Progress text of a transcribing recording in the list.
 */
internal object ListProgressTextFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/list/ListTranscribeItemBinder;",
    name = "getProgressText",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;", "J", "Ljava/util/Map;")
)

/**
 * [ListProgressTextFingerprint] of older app versions.
 */
internal object LegacyListProgressTextFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/list/ListAdapter;",
    name = "getProgressText",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/Long;")
)

/**
 * Error handling of the recordings list, which opens the language dialog or language
 * pack download for the failed recording.
 */
internal object HandleFastConvertErrorFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/list/ListFastConvertHandler;",
    name = "handleFastConvertError",
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/String;")
)
