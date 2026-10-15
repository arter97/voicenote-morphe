package app.morphe.patches.voicenote.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * @return A register that is not a parameter, so it is free at the start of the method.
 */
internal fun MutableMethod.getFreeRegisterAtStart(): Int {
    val parameterRegisterCount = parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    } + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1

    if (implementation!!.registerCount <= parameterRegisterCount) {
        throw PatchException("No free register in $this")
    }
    return 0
}

/**
 * Moves the method body to a copy named "<name>$morphe" and replaces the method with a
 * small wrapper. The wrapper gets its own few registers, so unlike hooking inside the
 * original it cannot clobber registers the original's exception handlers rely on.
 *
 * @param wrapperRegisters Registers of the wrapper, including its parameter registers.
 * @param wrapperSmali Wrapper body, given the copy's method reference.
 * @return The copy holding the original body.
 */
internal fun BytecodePatchContext.moveBodyAndWrap(
    original: MutableMethod,
    bodyAccessFlags: Int,
    wrapperRegisters: Int,
    wrapperSmali: (bodyReference: String) -> String
): MutableMethod {
    val body = original.cloneMutable(
        name = original.name + "\$morphe",
        accessFlags = bodyAccessFlags
    )
    val bodyReference = "${original.definingClass}->${body.name}(" +
            original.parameterTypes.joinToString("") + ")${original.returnType}"

    val wrapper = ImmutableMethod(
        original.definingClass,
        original.name,
        original.parameters,
        original.returnType,
        original.accessFlags,
        original.annotations,
        null,
        MutableMethodImplementation(wrapperRegisters),
    ).toMutable().apply {
        addInstructions(0, wrapperSmali(bodyReference))
    }

    mutableClassDefBy(original.definingClass).methods.apply {
        remove(original)
        add(body)
        add(wrapper)
    }

    return body
}
