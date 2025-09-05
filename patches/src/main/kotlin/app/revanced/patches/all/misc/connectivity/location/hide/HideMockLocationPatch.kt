@file:Suppress("unused")

package app.revanced.patches.all.misc.connectivity.location.hide

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.transformation.IMethodCall
import app.revanced.patches.all.misc.transformation.fromMethodReference
import app.revanced.patches.all.misc.transformation.transformInstructionsPatch
import app.revanced.util.getReference

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val hideMockLocationPatch = bytecodePatch(
    name = "Hide mock location",
    description = "Prevents the app from knowing the device location is being mocked by a third party app.",
    use = false,
) {
    dependsOn(
        transformInstructionsPatch(
            filterMap = filter@{ _, _, instruction, instructionIndex ->
                val ref = instruction.getReference() as? MethodReference ?: return@filter null
                val target = fromMethodReference(ref)
                if (target == null) return@filter null

                when (instruction.opcode) {
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_VIRTUAL,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_INTERFACE,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_DIRECT,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_SUPER,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_VIRTUAL_RANGE,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_INTERFACE_RANGE,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_DIRECT_RANGE,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC_RANGE,
                    com.android.tools.smali.dexlib2.Opcode.INVOKE_SUPER_RANGE -> (instruction to instructionIndex)
                    else -> return@filter null
                }
            },
            transform = { method, entry ->
                val (invokeInsn, index) = entry

                val targetReg: Int? = when (invokeInsn) {
                    is FiveRegisterInstruction -> invokeInsn.registerC
                    is RegisterRangeInstruction -> invokeInsn.startRegister
                    else -> null
                }
                if (targetReg == null) return@transform

                val impl = method.implementation ?: return@transform
                val nextIndex = index + 1
                if (nextIndex >= impl.instructions.size) return@transform

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
