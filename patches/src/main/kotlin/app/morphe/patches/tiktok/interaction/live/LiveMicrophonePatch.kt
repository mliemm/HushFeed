/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "LIVE microphone"
internal const val LIVE_AUDIO_DEVICE_MODULE = "Lcom/ss/lyrax/live_core/core/engine/AudioDeviceModule;"
internal const val LYRAX_RECORD = "Lcom/ss/lyrax/audio/device/webrtc/WebRtcAudioRecord;"
private const val LYRAX_RECORD_THREAD =
    "Lcom/ss/lyrax/audio/device/webrtc/WebRtcAudioRecord\$AudioRecordThread;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveMicrophone;"

private enum class CaptureCall(val nativeName: String, val returnType: String) {
    START("nativeStartRecording", "I"),
    STOP("nativeStopRecording", "I"),
    RELEASE("nativeRelease", "V"),
}

private fun CaptureCall.descriptor() = "$LIVE_AUDIO_DEVICE_MODULE->$nativeName()$returnType"

private fun CaptureCall.bridgeName() = "hushfeed\$${nativeName}"

// Follow the native capture calls, including the stops and releases Lyrax schedules.
private fun CaptureCall.bridge() = MutableMethod(
    ImmutableMethod(
        LIVE_AUDIO_DEVICE_MODULE, bridgeName(), emptyList(), returnType, AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
    ),
).apply {
    val start = this@bridge == CaptureCall.START
    val invoke = if (this@bridge == CaptureCall.RELEASE) "invoke-virtual" else "invoke-direct"
    val finish = "invoke-static {p0}, $EXTENSION->end(Ljava/lang/Object;)V"
    val started = "invoke-static {p0}, $EXTENSION->started(Ljava/lang/Object;)V"
    val begin = "invoke-static {p0}, $EXTENSION->begin(Ljava/lang/Object;)Z"
    val result = if (returnType == "I") "move-result v1" else ""
    val returnValue = if (returnType == "I") "return v1" else "return-void"
    val body = if (start) """
        $begin
        move-result v0
        $invoke {p0}, ${this@bridge.descriptor()}
        $result
        if-nez v1, :started
        if-eqz v0, :done
        $finish
        goto :done
        :started
        $started
        :done
        $returnValue
        move-exception v1
        if-eqz v0, :throw
        $finish
        :throw
        throw v1
    """ else """
        $invoke {p0}, ${this@bridge.descriptor()}
        $result
        $finish
        $returnValue
        move-exception v1
        $finish
        throw v1
    """
    addInstructions(0, body)
    val implementation = implementation!!
    val nativeCall = if (start) 2 else 0
    val handler = implementation.instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
    implementation.addCatch(
        implementation.newLabelForIndex(nativeCall),
        implementation.newLabelForIndex(nativeCall + if (returnType == "I") 2 else 1),
        implementation.newLabelForIndex(handler),
    )
}

private fun MutableClass.routeCaptureCalls(vararg nativeCalls: CaptureCall): Set<CaptureCall> {
    val replaced = mutableSetOf<CaptureCall>()
    for (method in methods) {
        val instructions = method.implementation?.instructions ?: continue
        instructions.forEachIndexed { index, instruction ->
            val reference = instruction.getReference<MethodReference>() ?: return@forEachIndexed
            val call = nativeCalls.firstOrNull { it.descriptor() == reference.toString() }
                ?: return@forEachIndexed
            val receiver = when (instruction) {
                is FiveRegisterInstruction -> instruction.registerC
                is RegisterRangeInstruction -> instruction.startRegister
                else -> throw PatchException("$PATCH: unexpected recorder invoke in $method")
            }
            method.replaceInstruction(
                index,
                "invoke-virtual/range {v$receiver .. v$receiver}, $LIVE_AUDIO_DEVICE_MODULE->${call.bridgeName()}()${call.returnType}",
            )
            replaced += call
        }
    }
    return replaced
}

private fun MutableClass.requireControl(name: String) {
    if (methods.none {
            it.name == name
                && it.parameters.map { parameter -> parameter.type } == listOf("Z")
                && it.returnType == "V"
        }
    ) {
        throw PatchException("$PATCH: the LIVE audio control $name was not found")
    }
}

@Suppress("unused")
val liveMicrophonePatch = configurableBytecodePatch(
    name = PATCH,
    description = "Lets a connected Bluetooth headset capture your LIVE, adds TikTok's own noise " +
        "suppression, and puts a hold to talk button over the room. Each starts off and has its " +
        "own switch in Hushfeed settings > App.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val audio = mutableClassDefBy(LIVE_AUDIO_DEVICE_MODULE)
        val releaseTask = mutableClassDefBy("Lcom/ss/lyrax/live_core/core/engine/AudioDeviceModule\$1;")
        val calls = CaptureCall.entries.toTypedArray()
        if (audio.routeCaptureCalls(*calls) != calls.toSet()
            || releaseTask.routeCaptureCalls(CaptureCall.RELEASE) != setOf(CaptureCall.RELEASE)
        ) {
            throw PatchException("$PATCH: Lyrax's LIVE recording lifecycle has changed")
        }
        calls.forEach { audio.methods.add(it.bridge()) }
        audio.requireControl("enableANS")
        audio.requireControl("enableAudioNoiseDetectionCfg")

        val record = mutableClassDefBy(LYRAX_RECORD)
        if (record.fields.none { it.name == "byteBuffer" }) {
            throw PatchException("$PATCH: Lyrax's LIVE record buffer was not found")
        }
        val recorder = record.methods.singleOrNull {
            it.name == "cvtNativeSetAudioSource" && it.parameters.map { parameter -> parameter.type } == listOf("I", "Z")
                && it.returnType == "I"
        } ?: throw PatchException("$PATCH: Lyrax's microphone source selector was not found")
        val returns = recorder.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }.asReversed()
        if (returns.isEmpty()) {
            throw PatchException("$PATCH: Lyrax's microphone source selector has no return")
        }
        for ((index, instruction) in returns) {
            val register = "v${(instruction as OneRegisterInstruction).registerA}"
            recorder.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range {$register .. $register}, $EXTENSION->captureSource(I)I
                    move-result $register
                """,
            )
        }

        val capture = mutableClassDefBy(LYRAX_RECORD_THREAD).methods.singleOrNull { method ->
            method.implementation?.instructions?.any {
                it.getReference<MethodReference>()?.name == "nativeDataIsRecorded"
            } == true
        } ?: throw PatchException("$PATCH: the LIVE capture thread was not found")
        val instructions = capture.implementation!!.instructions
        val nativeCall = instructions.indexOfFirst {
            it.getReference<MethodReference>()?.name == "nativeDataIsRecorded"
        }
        val muteCheck = (0 until nativeCall).lastOrNull {
            instructions[it].opcode == Opcode.SGET_BOOLEAN
        } ?: throw PatchException("$PATCH: the LIVE microphone mute check was not found")
        capture.addInstructions(
            muteCheck,
            """
                iget-object v0, p0, $LYRAX_RECORD_THREAD->this${'$'}0:$LYRAX_RECORD
                iget-object v0, v0, $LYRAX_RECORD->byteBuffer:Ljava/nio/ByteBuffer;
                invoke-static {v0}, $EXTENSION->processFrame(Ljava/nio/ByteBuffer;)V
            """,
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveMicrophone()V",
        )
    }
}
