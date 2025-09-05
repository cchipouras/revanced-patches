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
                // EXPLICIT generic to avoid Nothing
                val ref: MethodReference = instruction.getReference<MethodReference>() ?: return@filter null
                // EXPLICIT enum type parameter; keep the value as MethodCall?
                val target: MethodCall? = fromMethodReference<MethodCall>(ref)
                if (target == null) return@filter null

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
                    Opcode.INVOKE_SUPER_RANGE -> instruction to instructionIndex
                    else -> return@filter null
                }
            },
            transform = transform@{ method, entry ->
                val (invokeInsn, index) = entry

                val targetReg: Int = when (invokeInsn) {
                    is FiveRegisterInstruction -> invokeInsn.registerC
                    is RegisterRangeInstruction -> invokeInsn.startRegister
                    else -> return@transform
                }

                val impl = method.implementation ?: return@transform
                val instructions = impl.instructions

                // Only patch when a MOVE_RESULT* is actually present. Otherwise skip.
                val maxLookahead = 12
                var moveResultIndex: Int? = null
                var i = index + 1
                while (i < instructions.size && i <= index + maxLookahead) {
                    when (instructions[i].opcode) {
                        Opcode.MOVE_RESULT,
                        Opcode.MOVE_RESULT_WIDE,
                        Opcode.MOVE_RESULT_OBJECT -> {
                            moveResultIndex = i
                            break
                        }
                        else -> { /* keep scanning */ }
                    }
                    i++
                }

                if (moveResultIndex == null) return@transform

                method.replaceInstruction(moveResultIndex, "const/4 v$targetReg, 0x0")
            }
        )
    )
}

// IMPORTANT: This enum is the concrete type used for fromMethodReference<T>()
// Make sure the method names (including case) match your target.
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    IsMock("Landroid/location/Location;", "isMock", emptyArray(), "Z"),
    IsFromMockProvider("Landroid/location/Location;", "isFromMockProvider", emptyArray(), "Z"),
}
