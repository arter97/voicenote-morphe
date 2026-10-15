package app.morphe.patches.voicenote.misc.importaudio

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.voicenote.shared.Constants.COMPATIBILITY_VOICENOTE
import app.morphe.patches.voicenote.shared.getFreeRegisterAtStart
import app.morphe.patches.voicenote.shared.moveBodyAndWrap
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/voicenote/patches/ImportAnyAudioPatch;"

private const val ABS_ASYNC_LOAD_WAVE_CLASS =
    "Lcom/sec/android/app/voicenote/ui/fragment/wave/AbsAsyncLoadWave;"

/**
 * Calls an extension method taking the first int parameter (p1) at the start of the
 * method, and returns its result unless it is null.
 */
private fun MutableMethod.returnEarlyIfNotNull(extensionMethod: String) {
    val register = getFreeRegisterAtStart()

    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $extensionMethod
            move-result-object v$register
            if-eqz v$register, :stock
            return-object v$register
            :stock
            nop
        """
    )
}

@Suppress("unused")
val importAnyAudioPatch = bytecodePatch(
    name = "Import any audio files",
    description = "Imports audio files of any format in the Recordings folder and .oga files " +
            "anywhere, including files not recorded by this app, and shows them in All recordings. " +
            ".m4a files there are treated as recorded by this app even without its metadata, " +
            "and transcription and AI features work on all formats except .amr and .3ga. " +
            "Adds a setting for additional folders to import, each shown in the recordings list menu."
) {
    compatibleWith(COMPATIBILITY_VOICENOTE)

    dependsOn(additionalFoldersSettingsPatch)

    extendWith("extensions/voicenote.mpe")

    execute {
        // Widen the MediaStore selection beyond .3ga/.amr/.m4a. The StringBuilder is
        // modified in place right before it is returned.
        BaseQueryFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->widenBaseQuery(Ljava/lang/StringBuilder;)V"
                )
            }
        }

        // "All recordings" only lists files recorded by the app. Route it through the
        // extension, which also returns other files in the Recordings folder.
        RecordingItemListByCategoryFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val daoRegister = when (val instruction = getInstruction(index)) {
                    is FiveRegisterInstruction -> instruction.registerC
                    is RegisterRangeInstruction -> instruction.startRegister
                    else -> throw PatchException("Unexpected instruction: $instruction")
                }

                replaceInstruction(
                    index,
                    "invoke-static/range { v$daoRegister .. v$daoRegister }, " +
                            "$EXTENSION_CLASS->getAllRecordings($RECORDING_ITEM_DAO_CLASS)Ljava/util/List;"
                )
            }
        }

        // Additional folders appear in the left pane as virtual categories with negative
        // ids. Answer the list, count and title for those ids before the stock lookups,
        // which would treat them as empty user categories titled "All recordings".
        // Added after the replacement above, which relies on the matched index.
        RecordingItemListByCategoryFingerprint.method.returnEarlyIfNotNull(
            "$EXTENSION_CLASS->getFolderRecordings(I)Ljava/util/List;"
        )
        CategoryNameFingerprint.method.returnEarlyIfNotNull(
            "$EXTENSION_CLASS->getFolderCategoryName(I)Ljava/lang/String;"
        )
        FileCountByCategoryFingerprint.method.apply {
            val register = getFreeRegisterAtStart()

            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->getFolderRecordingCount(I)I
                    move-result v$register
                    if-ltz v$register, :stock
                    return v$register
                    :stock
                    nop
                """
            )
        }

        DrawerItemsLoaderFingerprint.let {
            it.method.apply {
                val postIndex = it.instructionMatches.last().index

                // The entry list is the receiver of the last ArrayList.add() call and is
                // handed to the Runnable posted to the main thread.
                val addIndex = indexOfFirstInstructionReversedOrThrow(postIndex) {
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            getReference<MethodReference>()?.let { reference ->
                                reference.definingClass == "Ljava/util/ArrayList;" && reference.name == "add"
                            } == true
                }
                val listRegister = getInstruction<FiveRegisterInstruction>(addIndex).registerC

                val runnableInitIndex = indexOfFirstInstructionReversedOrThrow(postIndex) {
                    opcode == Opcode.INVOKE_DIRECT && getReference<MethodReference>()?.name == "<init>"
                }
                val runnableArguments = with(getInstruction<FiveRegisterInstruction>(runnableInitIndex)) {
                    listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
                }
                if (listRegister !in runnableArguments) {
                    throw PatchException("Could not find the left pane entry list in $this")
                }

                addInstruction(
                    postIndex,
                    "invoke-static/range { v$listRegister .. v$listRegister }, " +
                            "$EXTENSION_CLASS->addFolderDrawerItems(Ljava/util/List;)V"
                )
            }
        }

        // The waveform of formats other than MP4/AAC and AMR comes out empty, as the stock
        // extractors parse those containers instead of decoding. Fall back to a decoder.
        WaveExtractorFactoryFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->getWaveExtractorFactory($EXTRACTOR_FACTORY_CLASS)$EXTRACTOR_FACTORY_CLASS
                        move-result-object v$register
                    """
                )
            }
        }

        // Draw the real waveform instead of a placeholder pattern for files not recorded
        // by the app. Their waveform is generated by the decoder fallback above.
        WaveForOtherRecordingFileFingerprint.method.returnEarly(false)

        // Let the waveform loaders wait for the generated waveform of any format.
        waveLoaderStartFingerprints.forEach { fingerprint ->
            fingerprint.method.apply {
                val index = fingerprint.instructionMatches.last().index
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                replaceInstruction(
                    index,
                    "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                            "$EXTENSION_CLASS->isWaveGeneratedFile(Ljava/lang/String;Ljava/lang/String;)Z"
                )

                // While a background decode runs, draw the flat placeholder instead of
                // waiting. Decided before the stock wait branch, which reuses registers
                // that the drawing code after it still needs.
                val workingIndex = indexOfFirstInstructionOrThrow(index) {
                    opcode == Opcode.INVOKE_STATIC &&
                            getReference<MethodReference>()?.name == "isWaveMakerWorking"
                }
                val workingRegister = getInstruction<OneRegisterInstruction>(workingIndex + 1).registerA
                val waitCheckIndex = indexOfFirstInstructionOrThrow(workingIndex, Opcode.IF_EQZ)
                val drawInstruction = getInstruction<BuilderOffsetInstruction>(waitCheckIndex)
                    .target.location.instruction
                    ?: throw PatchException("Could not find the waveform drawing code in $this")

                addInstructionsAtControlFlowLabel(
                    workingIndex,
                    """
                        invoke-static { p0, p1 }, $EXTENSION_CLASS->drawWhileWaveDecodes(${ABS_ASYNC_LOAD_WAVE_CLASS}Ljava/lang/String;)Z
                        move-result v$workingRegister
                        if-nez v$workingRegister, :draw_while_decoding
                    """,
                    ExternalLabel("draw_while_decoding", drawInstruction)
                )
            }
        }

        // Keep a flat placeholder waveform while the real one is decoded in the background.
        moveBodyAndWrap(
            UpdateWaveDataFromWaveMakerFingerprint.method,
            bodyAccessFlags = AccessFlags.PRIVATE.value,
            // v0, then p0 (this), p1 (path), p2 (record mode), p3-p4 (duration)
            wrapperRegisters = 6
        ) { body ->
            """
                invoke-direct/range { p0 .. p4 }, $body
                iget-object v0, p0, $METADATA_WAVE_HELPER_CLASS->mWaveData:[I
                invoke-static { v0, p1, p3, p4 }, $EXTENSION_CLASS->getWaveDataWhileDecoding([ILjava/lang/String;J)[I
                move-result-object v0
                iput-object v0, p0, $METADATA_WAVE_HELPER_CLASS->mWaveData:[I
                return-void
            """
        }

        // Forward the settings screen's activity results, which carry the folder chosen in
        // the system folder chooser. The fragment does not override onActivityResult.
        SettingsCreatePreferencesFingerprint.method.definingClass.let { fragmentClass ->
            val forwardResult = "invoke-static/range { p0 .. p3 }, $EXTENSION_CLASS->" +
                    "onSettingsActivityResult(Landroidx/preference/PreferenceFragmentCompat;IILandroid/content/Intent;)V"
            val resultParameters = listOf("I", "I", "Landroid/content/Intent;")

            mutableClassDefBy(fragmentClass).methods.apply {
                val existing = firstOrNull { method ->
                    method.name == "onActivityResult" && method.returnType == "V" &&
                            method.parameterTypes.map { it.toString() } == resultParameters
                }

                if (existing != null) {
                    existing.addInstructions(0, forwardResult)
                } else {
                    add(
                        ImmutableMethod(
                            fragmentClass,
                            "onActivityResult",
                            resultParameters.map { ImmutableMethodParameter(it, null, null) },
                            "V",
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(4),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    invoke-super { p0, p1, p2, p3 }, Landroidx/preference/PreferenceFragmentCompat;->onActivityResult(IILandroid/content/Intent;)V
                                    $forwardResult
                                    return-void
                                """
                            )
                        }
                    )
                }
            }
        }

        // Fill in the additional folders list of the settings screen.
        SettingsCreatePreferencesFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { p0 .. p0 }, " +
                            "$EXTENSION_CLASS->onSettingsCreated(Landroidx/preference/PreferenceFragmentCompat;)V"
                )
            }
        }

        // Transcripts and AI data of any format are stored in the app database, and the
        // decoders are not limited to .m4a, so only the file name checks need replacing.
        TranscribeM4aCheckFingerprint.matchAllMethodIndicesForEach { index ->
            val register = when (val instruction = getInstruction(index)) {
                is FiveRegisterInstruction -> instruction.registerC
                is RegisterRangeInstruction -> instruction.startRegister
                else -> throw PatchException("Unexpected instruction: $instruction")
            }

            replaceInstruction(
                index,
                "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_CLASS->isTranscribable(Ljava/lang/String;)Z"
            )
        }

        // MediaTool cannot open Ogg Opus, and its open errors never match the error code
        // that triggers the MediaCodec fallback. Pretend every error does, which is only
        // used for files not recorded by the app and only once per decode.
        DecoderFallbackFingerprint.methodOrNull?.addInstruction(0, "const/16 p1, -0x5")
            ?: Logger.getLogger(this::class.java.name)
                .warning("No decoder fallback in this app version, Ogg Opus files cannot be transcribed")

        // Treat .m4a files without Samsung's recording metadata atom as recorded by the app.
        // The original body moves to a private copy and the method becomes a wrapper that
        // passes its result through the extension. Hooking the returns in place would
        // clobber registers that the method's exception handlers still read.
        moveBodyAndWrap(
            ReadRecordingTypeAndRecordingModeFingerprint.method,
            bodyAccessFlags = AccessFlags.PRIVATE.value,
            wrapperRegisters = 3
        ) { body ->
            """
                invoke-direct { p0 }, $body
                move-result-object v0
                iget-object v1, p0, $M4A_READER_CLASS->path:Ljava/lang/String;
                invoke-static { v0, v1 }, $EXTENSION_CLASS->overrideRecordingTypeAndRecordingMode([ILjava/lang/String;)[I
                move-result-object v0
                return-object v0
            """
        }

        // Generate waveforms that need decoding in the background, so a long recording
        // does not hold up opening the player. The extension calls the original body.
        moveBodyAndWrap(
            WaveMakerDecodeFingerprint.method,
            bodyAccessFlags = AccessFlags.PUBLIC.value,
            wrapperRegisters = 2
        ) {
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->decodeWave(${WAVE_MAKER_CLASS}Ljava/lang/String;)V
                return-void
            """
        }
    }
}
