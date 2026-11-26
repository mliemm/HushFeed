package app.morphe.patches.tiktok.interaction.live

import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMicrophoneBridgeTest {
    @Test
    fun `native success starts controls and failure releases the route`() {
        val instructions = CaptureCall.START.bridge().implementation!!.instructions.toList()
        val branch = instructions[4]
        assertEquals(Opcode.IF_EQZ, branch.opcode)

        val branchAddress = instructions.take(4).sumOf { it.codeUnits }
        val targetAddress = branchAddress + (branch as OffsetInstruction).codeOffset
        var address = 0
        val target = instructions.first { instruction ->
            val matches = address == targetAddress
            address += instruction.codeUnits
            matches
        }
        assertEquals("started", target.getReference<MethodReference>()!!.name)
        assertEquals("end", instructions[6].getReference<MethodReference>()!!.name)
    }
}
