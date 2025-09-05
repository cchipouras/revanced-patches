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
    description = "Prevents apps from detecting mock/fake GPS locations by forcing isMock()/isFromMockProvider() to false.",
    use = false,
) {
    dependsOn(
        transformInstructionsPatch(
            filterMap = filter@{ _, _, instruction, instructionIndex ->
                // Explicit type to avoid inference -> Nothing
                val ref: MethodReference = instruction.getReference<MethodReference>() ?: return@filter null
                // Keep as concrete enum type to avoid Enum<E> vs interface variance issues
                val match: MethodCall? = fromMethodReference<MethodCall>(ref)
                if (match == null) return@filter null

                // Only consider invoke opcodes
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

                val impl = method.implementation ?: return@transform
                val insns = impl.instructions
                if (insns.isEmpty()) return@transform

                // Determine the candidate register holding the result
                val resultReg: Int = when (invokeInsn) {
                    is FiveRegisterInstruction -> invokeInsn.registerC
                    is RegisterRangeInstruction -> invokeInsn.startRegister
                    else -> return@transform
                }

                // Scan forward for a true move-result target, but stop on early exits
                val maxLookahead = 24
                var patchIndex: Int? = null
                var i = index + 1
                while (i < insns.size && i <= index + maxLookahead) {
                    when (val op = insns[i].opcode) {
                        // Any immediate exit or control break — don't cross it, skip site
                        Opcode.RETURN_VOID,
                        Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT,
                        Opcode.THROW,
                        // Conservative: don’t cross label-boundary patterns (compiler specifics not visible here)
                        // If you know how to detect labels in your API, add that check here.
                        -> { patchIndex = null; break }

                        // Found our safe target
                        Opcode.MOVE_RESULT,
                        Opcode.MOVE_RESULT_OBJECT -> { patchIndex = i; break }

                        // Wide move-result not expected for boolean-return methods — skip for safety
                        Opcode.MOVE_RESULT_WIDE -> { patchIndex = null; break }

                        // If we hit a branch right after invoke, we won't touch it (no insert support)
                        Opcode.IF_EQ, Opcode.IF_NE,
                        Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_LE, Opcode.IF_GT,
                        Opcode.IF_EQZ, Opcode.IF_NEZ,
                        Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_LEZ, Opcode.IF_GTZ -> { patchIndex = null; break }

                        else -> { /* keep scanning */ }
                    }
                    i++
                }

                // If we did not find a legal MOVE_RESULT* within a safe window, skip site
                val targetIndex = patchIndex ?: return@transform

                // Choose an instruction form that matches the register number
                // const/4 supports v0..v15, for >= 16 use const/16
                val smaliLine = if (resultReg < 16) {
                    "const/4 v$resultReg, 0x0"
                } else {
                    "const/16 v$resultReg, 0x0"
                }

                // Final safety: ensure index within bounds
                if (targetIndex < 0 || targetIndex >= insns.size) return@transform

                // Replace the move-result* with a constant false into the same register slot
                // This guarantees the downstream boolean test sees 'false'
                method.replaceInstruction(targetIndex, smaliLine)
            }
        )
    )
}

// IMPORTANT: exact method signatures; adjust case if your app differs
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    IsMock("Landroid/location/Location;", "isMock", emptyArray(), "Z"),
    IsFromMockProvider("Landroid/location/Location;", "isFromMockProvider", emptyArray(), "Z"),
}
