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

private const val PATCH = "Bluetooth microphone for LIVE"
internal const val LIVE_AUDIO_DEVICE_MODULE = "Lcom/ss/lyrax/live_core/core/engine/AudioDeviceModule;"
internal const val LYRAX_RECORD = "Lcom/ss/lyrax/audio/device/webrtc/WebRtcAudioRecord;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveBluetoothMicrophone;"

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
    val result = if (returnType == "I") "move-result v1" else ""
    val returnValue = if (returnType == "I") "return v1" else "return-void"
    val begin = "invoke-static {p0}, $EXTENSION->begin(Ljava/lang/Object;)Z"
    val beginCode = if (start) "$begin\nmove-result v0" else ""
    val afterCall = if (start) "if-eqz v1, :done\nif-eqz v0, :done" else ""
    val afterThrow = if (start) "if-eqz v0, :throw" else ""
    addInstructions(
        0,
        """
            $beginCode
            $invoke {p0}, ${this@bridge.descriptor()}
            $result
            $afterCall
            $finish
            :done
            $returnValue
            move-exception v1
            $afterThrow
            $finish
            :throw
            throw v1
        """,
    )
    val body = implementation!!
    val nativeCall = if (start) 2 else 0
    val handler = body.instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
    body.addCatch(
        body.newLabelForIndex(nativeCall),
        body.newLabelForIndex(nativeCall + if (returnType == "I") 2 else 1),
        body.newLabelForIndex(handler),
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

@Suppress("unused")
val liveBluetoothMicrophonePatch = configurableBytecodePatch(
    name = PATCH,
    description = "Prefers a connected Bluetooth headset microphone when you broadcast a LIVE on Android 12 " +
        "or newer. Starts off. Turn it on in Hushfeed settings > App.",
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

        val recorder = mutableClassDefBy(LYRAX_RECORD).methods.singleOrNull {
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

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveBluetoothMicrophone()V",
        )
    }
}
