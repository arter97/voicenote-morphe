package app.morphe.patches.voicenote.misc.batchtranscribe

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.resource.resourceId
import app.morphe.patches.voicenote.shared.Constants.COMPATIBILITY_VOICENOTE
import app.morphe.patches.voicenote.shared.getFreeRegisterAtStart
import app.morphe.patches.voicenote.shared.moveBodyAndWrap
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/voicenote/patches/BatchTranscribePatch;"

private const val CHECKED_ITEM_PROVIDER_CLASS =
    "Lcom/sec/android/app/voicenote/data/CheckedItemProvider;"

/**
 * Name of the stock body of AddRemoveOptionMenuHelper.needInflateTranscribeMenu(), which
 * the extension calls once per checked recording.
 */
private const val NEED_INFLATE_TRANSCRIBE_MENU_BODY = "needInflateTranscribeMenu\$morphe"

/**
 * Calls an extension method at the start of the method, and returns early if it returns
 * true. The method's return type must be void.
 *
 * @param parameterRegisters Registers passed to the extension method, such as "p1 .. p2".
 */
private fun MutableMethod.returnEarlyIfTrue(parameterRegisters: String, extensionMethod: String) {
    val register = getFreeRegisterAtStart()

    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $parameterRegisters }, $extensionMethod
            move-result v$register
            if-eqz v$register, :stock
            return-void
            :stock
            nop
        """
    )
}

/**
 * Calls an extension method at the start of the method, and returns its result unless it
 * is null.
 *
 * @param parameterRegisters Registers passed to the extension method, such as "p1 .. p2".
 */
private fun MutableMethod.returnEarlyIfNotNull(parameterRegisters: String, extensionMethod: String) {
    val register = getFreeRegisterAtStart()

    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $parameterRegisters }, $extensionMethod
            move-result-object v$register
            if-eqz v$register, :stock
            return-object v$register
            :stock
            nop
        """
    )
}

@Suppress("unused")
val batchTranscribePatch = bytecodePatch(
    name = "Batch transcribe",
    description = "Shows Transcribe when several recordings are selected and transcribes them " +
            "one after another, in list order. Recordings that can't be transcribed are skipped, " +
            "and recordings can be added while others are still transcribing. Queued recordings " +
            "show as waiting, a failed recording does not interrupt the batch, a notification shows " +
            "the batch's progress and can cancel it, and a summary is shown when the batch ends."
) {
    compatibleWith(COMPATIBILITY_VOICENOTE)

    extendWith("extensions/voicenote.mpe")

    execute {
        val logger = Logger.getLogger(this::class.java.name)

        // The stock check of the Transcribe button only passes for a single recording while
        // nothing is transcribing. Move it to a public copy that the extension runs for each
        // checked recording, with the selection and queue size it reads replaced, so the
        // check stays exactly the stock one, including changes by other patches.
        val needInflateTranscribeMenuBody = moveBodyAndWrap(
            NeedInflateTranscribeMenuFingerprint.method,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            wrapperRegisters = 2
        ) {
            """
                invoke-static { p0 }, $EXTENSION_CLASS->needInflateTranscribeMenu($ADD_REMOVE_OPTION_MENU_HELPER_CLASS)Z
                move-result v0
                return v0
            """
        }

        needInflateTranscribeMenuBody.apply {
            var replacedCheckedItems = false

            instructions.withIndex().toList().forEach { (index, instruction) ->
                val reference = instruction.getReference<MethodReference>() ?: return@forEach

                when {
                    reference.definingClass == CHECKED_ITEM_PROVIDER_CLASS &&
                            reference.name == "getCheckedItems" -> {
                        replaceInstruction(
                            index,
                            "invoke-static { }, $EXTENSION_CLASS->getCheckedItems()Ljava/util/ArrayList;"
                        )
                        replacedCheckedItems = true
                    }

                    reference.definingClass == CHECKED_ITEM_PROVIDER_CLASS &&
                            reference.name == "getCheckedItemCount" -> replaceInstruction(
                        index,
                        "invoke-static { }, $EXTENSION_CLASS->getCheckedItemCount()I"
                    )

                    reference.definingClass == FAST_CONVERT_CONTROLLER_CLASS &&
                            reference.name == "numberFilesConverting" -> {
                        val register = (instruction as FiveRegisterInstruction).registerC
                        replaceInstruction(
                            index,
                            "invoke-static { v$register }, $EXTENSION_CLASS->" +
                                    "numberFilesConverting($FAST_CONVERT_CONTROLLER_CLASS)I"
                        )
                    }
                }
            }

            if (!replacedCheckedItems) {
                throw PatchException("No checked items lookup in $this")
            }
        }

        // In short landscape windows the button runs the single recording re-transcribe
        // action, so keep it to the stock single recording case there.
        AddSelectionMenuForLandscapeFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(index).registerC

                replaceInstruction(
                    index,
                    "invoke-virtual { v$register }, $ADD_REMOVE_OPTION_MENU_HELPER_CLASS->" +
                            "$NEED_INFLATE_TRANSCRIBE_MENU_BODY()Z"
                )
            }
        }

        // With several recordings checked, the bottom menu is only rebuilt if it still has
        // single selection items. Let the extension also rebuild it when Transcribe must
        // appear or disappear, and keep the stock rebuild whenever Transcribe is present.
        UpdateSelectMenuFingerprint.method.apply {
            data class FindItem(val menuRegister: Int, val resultIndex: Int, val resultRegister: Int)

            fun findItem(idName: String): FindItem {
                val literalIndex = indexOfFirstLiteralInstructionOrThrow(
                    resourceId(ResourceType.ID, idName)
                )
                val findItemIndex = indexOfFirstInstructionOrThrow(literalIndex) {
                    opcode == Opcode.INVOKE_INTERFACE &&
                            getReference<MethodReference>()?.let { reference ->
                                reference.definingClass == "Landroid/view/Menu;" &&
                                        reference.name == "findItem"
                            } == true
                }
                val resultIndex = findItemIndex + 1
                if (getInstruction(resultIndex).opcode != Opcode.MOVE_RESULT_OBJECT) {
                    throw PatchException("Unexpected findItem() result in $this")
                }

                return FindItem(
                    getInstruction<FiveRegisterInstruction>(findItemIndex).registerC,
                    resultIndex,
                    getInstruction<OneRegisterInstruction>(resultIndex).registerA
                )
            }

            val transcribe = findItem("action_transcribe")
            val reTranscribe = findItem("action_re_transcribe")
            val rename = findItem("action_rename")

            val registers = listOf(
                transcribe.menuRegister,
                transcribe.resultRegister,
                reTranscribe.resultRegister,
                rename.resultRegister
            )
            // The lookups must be consecutive on the same menu, so their results are all
            // still in place after the last one.
            if (transcribe.resultIndex >= reTranscribe.resultIndex ||
                reTranscribe.resultIndex >= rename.resultIndex ||
                rename.resultIndex - transcribe.resultIndex > 8 ||
                reTranscribe.menuRegister != transcribe.menuRegister ||
                rename.menuRegister != transcribe.menuRegister ||
                registers.toSet().size != registers.size ||
                registers.any { it > 15 }
            ) {
                throw PatchException("Unexpected selection menu lookups in $this")
            }

            val (menuRegister, transcribeRegister, reTranscribeRegister, renameRegister) = registers
            addInstructions(
                rename.resultIndex + 1,
                """
                    invoke-static { v$menuRegister, v$transcribeRegister, v$reTranscribeRegister, v$renameRegister }, $EXTENSION_CLASS->getRenameItem(Landroid/view/Menu;Landroid/view/MenuItem;Landroid/view/MenuItem;Landroid/view/MenuItem;)Landroid/view/MenuItem;
                    move-result-object v$renameRegister
                    invoke-static { v$transcribeRegister }, $EXTENSION_CLASS->getTranscribeItem(Landroid/view/MenuItem;)Landroid/view/MenuItem;
                    move-result-object v$transcribeRegister
                    invoke-static { v$reTranscribeRegister }, $EXTENSION_CLASS->getTranscribeItem(Landroid/view/MenuItem;)Landroid/view/MenuItem;
                    move-result-object v$reTranscribeRegister
                """
            )
        }

        // Keep the eligible recordings in list order. The bundle is returned in place.
        BundleHandleTranscribeFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->prepareBatch(Landroid/os/Bundle;)Landroid/os/Bundle;
                        move-result-object v$register
                    """
                )
            }
        }

        ExecuteTranscribeFingerprint.method.apply {
            if (parameterTypes.getOrNull(1)?.toString() != "Landroid/os/Bundle;") {
                throw PatchException("Unexpected parameters of $this")
            }

            addInstructions(
                0,
                "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->onExecuteTranscribe(Landroid/os/Bundle;)V"
            )
        }

        // With auto-detected language, only the first recording is queued.
        ExecuteTranscribeWithAutoDetectFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val instruction = getInstruction<RegisterRangeInstruction>(index)
                val firstRegister = instruction.startRegister
                val lastRegister = firstRegister + instruction.registerCount - 1

                replaceInstruction(
                    index,
                    "invoke-static/range { v$firstRegister .. v$lastRegister }, $EXTENSION_CLASS->" +
                            "addFastConvertItems($FAST_CONVERT_CONTROLLER_CLASS" +
                            "JLjava/lang/String;ZZZ)V"
                )
            }
        }

        AddFastConvertItemFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p6 }, $EXTENSION_CLASS->" +
                    "onAddFastConvertItem(JLjava/lang/String;ZZZ)V"
        )

        // Recordings that waited in the queue are started without their flags.
        StartFastConvertFingerprint.method.apply {
            val signature = "(Ljava/lang/Object;JLjava/lang/String;Ljava/lang/String;" +
                    "Ljava/lang/String;ZZ)Z"

            addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p7 }, $EXTENSION_CLASS->getForceTranscribeWithLanguage$signature
                    move-result p6
                    invoke-static/range { p0 .. p7 }, $EXTENSION_CLASS->getAutoSummary$signature
                    move-result p7
                """
            )
        }

        CancelFastConvertFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onCancelFastConvert(Ljava/lang/Long;)V"
        )

        FastConvertServiceCancelFingerprint.method.addInstructions(
            0,
            "invoke-static { }, $EXTENSION_CLASS->cancelWaitingItems()V"
        )

        ListProgressTextFingerprint.methodOrNull?.returnEarlyIfNotNull(
            "p2 .. p3",
            "$EXTENSION_CLASS->getProgressText(J)Ljava/lang/String;"
        ) ?: LegacyListProgressTextFingerprint.methodOrNull?.returnEarlyIfNotNull(
            "p1 .. p1",
            "$EXTENSION_CLASS->getProgressText(Ljava/lang/Long;)Ljava/lang/String;"
        ) ?: logger.warning("No list progress text in this app version, queued recordings show as transcribing")

        HandleFastConvertErrorFingerprint.method.returnEarlyIfTrue(
            "p1 .. p2",
            "$EXTENSION_CLASS->skipErrorHandling(ILjava/lang/String;)Z"
        )
    }
}
