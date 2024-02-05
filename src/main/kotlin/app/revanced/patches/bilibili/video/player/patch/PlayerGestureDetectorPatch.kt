package app.revanced.patches.bilibili.video.player.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.bilibili.utils.cloneMutable
import app.revanced.patches.bilibili.utils.isInterface
import app.revanced.patches.bilibili.video.player.fingerprints.PlayerGestureListenerFingerprint
import app.revanced.patches.bilibili.video.player.fingerprints.PlayerGestureRotateFingerprint
import app.revanced.util.exception
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Patch(
    name = "Player gesture detector hook",
    description = "播放器 GestureDetector hook",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object PlayerGestureDetectorPatch : BytecodePatch(
    setOf(
        PlayerGestureListenerFingerprint,
        PlayerGestureRotateFingerprint
    )
) {
    override fun execute(context: BytecodeContext) {
        val patchClass = context.findClass("Lapp/revanced/bilibili/patches/PlayerGestureDetectorPatch;")!!.mutableClass
        context.classes.firstNotNullOfOrNull { cl ->
            if (cl.fields.count() == 3 && cl.fields.any {
                    it.type == "Landroid/view/GestureDetector;"
                } && cl.fields.any { f ->
                    context.classes.find { it.type == f.type }?.let { c ->
                        c.accessFlags.isInterface() && c.methods.singleOrNull()?.let {
                            it.returnType == "V" && it.parameterTypes == listOf("Landroid/view/MotionEvent;")
                        } == true
                    } == true
                }) {
                cl.fields.firstNotNullOfOrNull { f ->
                    context.classes.find { it.type == f.type }?.takeIf {
                        it.superclass == "Landroid/view/GestureDetector\$SimpleOnGestureListener;"
                    }
                }?.let { context.proxy(it).mutableClass }
            } else null
        }?.methods?.firstOrNull {
            it.name == "onLongPress" && it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        }?.addInstructionsWithLabels(
            0, """
            invoke-static {}, $patchClass->disableLongPress()Z
            move-result v0
            if-eqz v0, :jump
            return-void
            :jump
            nop
        """.trimIndent()
        ) ?: throw PatchException("not found PlayerGestureDetector class")
        PlayerGestureListenerFingerprint.result?.mutableClass?.run {
            fun MutableMethod.disablePatch() = addInstructionsWithLabels(
                0, """
                invoke-static {}, $patchClass->scaleToSwitchRadio()Z
                move-result v0
                if-eqz v0, :jump
                return v0
                :jump
                nop
            """.trimIndent()
            )
            // disable translate
            methods.first { it.name == "onScroll" }.disablePatch()
            // disable rotate
            PlayerGestureRotateFingerprint.result?.mutableMethod
                ?.disablePatch() ?: throw PlayerGestureRotateFingerprint.exception
            val onScaleMethod = methods.first { it.name == "onScale" }
            onScaleMethod.addInstruction(
                0, """
                invoke-static {p1}, $patchClass->onScale(Landroid/view/ScaleGestureDetector;)V
            """.trimIndent()
            )
            methods.first { it.name == "onScaleBegin" }.addInstruction(
                0, """
                invoke-static {p1}, $patchClass->onScaleBegin(Landroid/view/ScaleGestureDetector;)V
            """.trimIndent()
            )
            methods.first { it.name == "onScaleEnd" }.addInstruction(
                0, """
                invoke-static {p0, p1}, $patchClass->onScaleEnd(Ljava/lang/Object;Landroid/view/ScaleGestureDetector;)V
            """.trimIndent()
            )
            val onScaleMethodInstructions = onScaleMethod.implementation!!.instructions
            val gestureServiceFieldName = onScaleMethodInstructions.firstNotNullOf {
                if (it.opcode == Opcode.IGET_OBJECT) {
                    ((it as BuilderInstruction22c).reference as FieldReference).name
                } else null
            }
            val getPlayerMethodName = onScaleMethodInstructions.withIndex().firstNotNullOf { (index, inst) ->
                if (inst.opcode == Opcode.INVOKE_STATIC && index != 0) {
                    ((inst as BuilderInstruction35c).reference as MethodReference).name
                } else null
            }
            val (getRenderServiceMethodName, renderServiceType) = onScaleMethodInstructions.firstNotNullOf {
                if (it.opcode == Opcode.INVOKE_INTERFACE) {
                    ((it as BuilderInstruction35c).reference as MethodReference).let { ref ->
                        ref.name to ref.returnType
                    }
                } else null
            }
            val renderServiceClass = context.classes.first { it.type == renderServiceType }
            val setAspectRatioMethodName = renderServiceClass.methods.first {
                it.parameterTypes == listOf("Ltv/danmaku/videoplayer/core/videoview/AspectRatio;")
            }.name
            val restoreMethodName = renderServiceClass.methods.first {
                it.parameterTypes == listOf("Z", "Landroid/animation/AnimatorListenerAdapter;")
            }.name
            val gestureServiceFieldNameField = patchClass.fields.first { it.name == "gestureServiceFieldName" }
            val getPlayerMethodNameField = patchClass.fields.first { it.name == "getPlayerMethodName" }
            val getRenderServiceMethodNameField = patchClass.fields.first { it.name == "getRenderServiceMethodName" }
            val setAspectRatioMethodNameField = patchClass.fields.first { it.name == "setAspectRatioMethodName" }
            val restoreMethodNameField = patchClass.fields.first { it.name == "restoreMethodName" }
            patchClass.methods.first { it.name == "init" }.also { patchClass.methods.remove(it) }
                .cloneMutable(registerCount = 1, clearImplementation = true).apply {
                    addInstructions(
                        0, """
                        const-string v0, "$gestureServiceFieldName"
                        sput-object v0, $gestureServiceFieldNameField
                        const-string v0, "$getPlayerMethodName"
                        sput-object v0, $getPlayerMethodNameField
                        const-string v0, "$getRenderServiceMethodName"
                        sput-object v0, $getRenderServiceMethodNameField
                        const-string v0, "$setAspectRatioMethodName"
                        sput-object v0, $setAspectRatioMethodNameField
                        const-string v0, "$restoreMethodName"
                        sput-object v0, $restoreMethodNameField
                        return-void
                    """.trimIndent()
                    )
                }.also { patchClass.methods.add(it) }
        } ?: throw PlayerGestureListenerFingerprint.exception
    }
}
