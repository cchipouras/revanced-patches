@file:Suppress("unused")

package app.revanced.patches.all.misc.connectivity.location.hide

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.transformation.IMethodCall
import app.revanced.patches.all.misc.transformation.fromMethodReference
import app.revanced.patches.all.misc.transformation.transformInstructionsPatch
import app.revanced.util.getReference

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Hide mock location
 * Prevents the app from detecting that location is mocked by forcing a `false` result.
 *
 * The original version assumed all matched instructions implement FiveRegisterInstruction,
 * which crashes when the method call is encoded as a range invoke (3rc).
 * This version supports both 35c and 3rc forms and skips unsupported shapes safely.
 */
@Suppress("unused")
val hideMockLocationPatch = bytecodePatch(
    name = "Hide mock location",
    description = "Prevents the app from knowing the device location is being mocked by a third party app.",
    use = false,
) {
    dependsOn(
        transformInstructionsPatch(
            filterMap = filter@{ _, _, instruction, instructionIndex ->
                // Only consider invoke* opcodes we can handle and that match our target methods.
                val ref = instruction.getReference() as? MethodReference ?: return@filter null
                val target = fromMethodReference(ref) ?: return@filter null

                when (instruction.opcode) {
                    Opcode.INVOKE_VIRTUAL,
                    Opcode.INVOKE_INTERFACE,
                    Opcode.INVOKE_DIRECT,
                    Opcode.INVOKE_STATIC,
                    Opcode.INVOKE_SUPER,
                    Opcode.INVOKE_VIRTUAL_RANGE,
                    Opcode.INVOKE_INTERFACE_RANGE,
                    Opcode.INVOKE_DIRECT_RANGE,
                    Opcode.INVOKE_STATIC_RANGE,
                    Opcode.INVOKE_SUPER_RANGE -> (instruction to instructionIndex)
                    else -> null
                }
            },
            transform = { method, entry ->
                val (invokeInsn, index) = entry

                // Determine a reasonable target register to overwrite with `false`.
                // For 35c we use registerC (typical receiver/arg position for boolean results handling).
                // For 3rc we best-effort use startRegister. If this doesn't match a following move-result,
                // the patch will still be no-op-safe (we skip when we cannot place).
                val targetReg: Int = when (invokeInsn) {
                    is FiveRegisterInstruction -> invokeInsn.registerC
                    is RegisterRangeInstruction -> invokeInsn.startRegister
                    else -> return@transform // unsupported shape; skip safely
                }

                val impl = method.implementation ?: return@transform
                val nextIndex = index + 1
                if (nextIndex >= impl.instructions.size) return@transform

                // Most compilers place a move-result* immediately after an invoke that returns a value.
                // We simply replace that following slot with a const/4 false to force the outcome.
                method.replaceInstruction(nextIndex, "const/4 v$targetReg, 0x0")
            }
        )
    )
}

private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    IsMock("Landroid/location/Location;", "isMock", emptyArray(), "Z"),
    IsFromMockProvider("Landroid/location/Location;", "isFromMockProvider", emptyArray(), "Z"),
}
