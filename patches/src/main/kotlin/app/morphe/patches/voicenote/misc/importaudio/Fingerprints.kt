package app.morphe.patches.voicenote.misc.importaudio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val RECORDING_ITEM_DAO_CLASS =
    "Lcom/sec/android/app/voicenote/data/db/RecordingItemDAO;"

/**
 * MediaStore selection shared by the media sync and every recordings query.
 */
internal object BaseQueryFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/common/util/QueryArguments;",
    name = "getBaseQuery",
    returnType = "Ljava/lang/StringBuilder;",
    parameters = listOf()
)

internal const val M4A_READER_CLASS = "Lcom/sec/android/app/voicenote/helper/M4aReader;"

/**
 * Reads Samsung's moov/udta/smta/saut atom of an .m4a file. Returns null if the atom is
 * missing, which makes the importer and other callers treat the file as an "other
 * recording" (recordingtype = -1) instead of one recorded by the app (recordingtype = 1).
 */
internal object ReadRecordingTypeAndRecordingModeFingerprint : Fingerprint(
    definingClass = M4A_READER_CLASS,
    name = "readRecordingTypeAndRecordingMode",
    returnType = "[I",
    parameters = listOf()
)

/**
 * Classes that only show Transcribe and the AI actions for files named .m4a. Every
 * other caller of M4aReader.isM4A(String) decides whether to parse or write Samsung's
 * atoms in the file, so it must stay untouched.
 */
private val TRANSCRIBE_GATE_CLASSES = setOf(
    "Lcom/sec/android/app/voicenote/main/FragmentLayoutSceneOrganizer;",
    "Lcom/sec/android/app/voicenote/ui/actionbar/AddRemoveOptionMenuHelper;",
    "Lcom/sec/android/app/voicenote/ui/helper/DisplayHelper;",
    "Lcom/sec/android/app/voicenote/ui/pager/AiOperationExecutor;",
    "Lcom/sec/android/app/voicenote/bixby/helper/BixbyHelper;"
)

internal object TranscribeM4aCheckFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = M4A_READER_CLASS,
            name = "isM4A",
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Z"
        )
    ),
    custom = { _, classDef -> classDef.type in TRANSCRIBE_GATE_CLASSES }
)

/**
 * Decides whether a failed MediaTool decode is retried with the MediaCodec decoder.
 * Only error -5 qualifies, but a file MediaTool cannot open (such as Ogg Opus) reports
 * 0, so it fails instead. Not present in older app versions, which have no fallback.
 */
internal object DecoderFallbackFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/engine/FastDecoder\$FastDecoderThread;",
    name = "isNeedDecoderFallback",
    returnType = "Z",
    parameters = listOf("I")
)

internal const val WAVE_MAKER_CLASS = "Lcom/sec/android/app/voicenote/data/WaveMaker;"

/**
 * Generates the waveform of a file without stored amplitude data. Runs synchronously
 * while the file's metadata is read for the player.
 */
internal object WaveMakerDecodeFingerprint : Fingerprint(
    definingClass = WAVE_MAKER_CLASS,
    name = "decode",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;")
)

internal const val METADATA_WAVE_HELPER_CLASS =
    "Lcom/sec/android/app/voicenote/data/MetadataWaveHelper;"

/**
 * Starts WaveMaker for a file without waveform data and keeps the result in mWaveData.
 */
internal object UpdateWaveDataFromWaveMakerFingerprint : Fingerprint(
    definingClass = METADATA_WAVE_HELPER_CLASS,
    name = "updateWaveDataFromWaveMaker",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "I", "J")
)

internal const val EXTRACTOR_FACTORY_CLASS =
    "Lcom/sec/android/app/voicenote/data/ExtractorSound\$Factory;"

/**
 * Picks the waveform source by file extension: MP4/AAC parser or AMR parser, else null.
 */
internal object WaveExtractorFactoryFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/data/ExtractorSound;",
    name = "getFactoryForExtension",
    returnType = EXTRACTOR_FACTORY_CLASS,
    parameters = listOf("Ljava/lang/String;")
)

/**
 * True for files not recorded by the app. The waveform view then ignores the amplitude
 * data and draws a fixed placeholder pattern (every fifth bar tall) in a dimmer color.
 */
internal object WaveForOtherRecordingFileFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/wave/WaveProvider;",
    name = "isWaveForOtherRecordingFile",
    returnType = "Z",
    parameters = listOf()
)

/**
 * path.endsWith(".m4a") check in a waveform loader's start(path), which only waits for
 * background waveform generation for .m4a, .amr and .3ga files.
 */
private fun waveLoaderStartFingerprint(loaderClass: String) = Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/wave/$loaderClass;",
    name = "start",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string(".m4a"),
        methodCall(
            smali = "Ljava/lang/String;->endsWith(Ljava/lang/String;)Z",
            location = MatchAfterImmediately()
        )
    )
)

internal val waveLoaderStartFingerprints = listOf(
    waveLoaderStartFingerprint("AsyncLoadWave"),
    waveLoaderStartFingerprint("SimpleAsyncLoadWave")
)

internal const val NAVIGATION_CONTROLLER_CLASS =
    "Lcom/sec/android/app/voicenote/main/NavigationController;"

/**
 * Background task that builds the left pane entries (All recordings, Voice recordings,
 * Call recordings, ..., Trash, user categories) and posts them to the main thread.
 */
internal object DrawerItemsLoaderFingerprint : Fingerprint(
    definingClass = NAVIGATION_CONTROLLER_CLASS,
    returnType = "V",
    parameters = listOf(NAVIGATION_CONTROLLER_CLASS),
    filters = listOf(
        newInstance("Lcom/sec/android/app/voicenote/main/NavigationController\$DrawerItem;"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z"
        )
    )
)

internal object FileCountByCategoryFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/data/RecordingItemRepository;",
    name = "getFileCountByCategory",
    returnType = "I",
    parameters = listOf("I")
)

/**
 * Title of the selected category in the toolbar.
 */
internal object CategoryNameFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/data/CategoryRepository;",
    name = "getCategoryName",
    returnType = "Ljava/lang/String;",
    parameters = listOf("I", "Landroid/content/Context;")
)

internal object SettingsCreatePreferencesFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/ui/fragment/PreferenceSettingFragment;",
    name = "onCreatePreferences",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;")
)

/**
 * Source of each list filter. Category -1 ("All recordings") calls getSSVRFiles(),
 * which only returns files with recordingtype = 1 (recorded by the app itself).
 */
internal object RecordingItemListByCategoryFingerprint : Fingerprint(
    definingClass = "Lcom/sec/android/app/voicenote/data/RecordingItemRepository;",
    name = "getRecordingItemListByCategory",
    returnType = "Ljava/util/List;",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            definingClass = RECORDING_ITEM_DAO_CLASS,
            name = "getSSVRFiles",
            returnType = "Ljava/util/List;"
        )
    )
)
