package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths

/**
 * Test to correlate $2002 reads with instruction execution.
 * Uses the debug logging added to PpuAddressedMemory to track
 * every $2002 read and correlate with the instruction trace.
 */
class VBlankPollingTest {

    @Test
    fun trace2002ReadsWithInstructionContext() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Enable both instruction trace and $2002 read logging
        val trace = nestlin.cpu.enableInstructionTrace(5000)
        nestlin.memory.ppuAddressedMemory.enableDebugLogging()

        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount >= 5) nestlin.stop()
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Analyze the results
        System.err.println("\n=== $2002 Read Analysis (First 5 Frames) ===")
        val statusReads = nestlin.memory.ppuAddressedMemory.statusReadLog

        System.err.println("Total $2002 reads: ${statusReads.size}")

        // Group by frame
        val byFrame = statusReads.groupBy { it.frame }
        for ((frame, reads) in byFrame.toSortedMap()) {
            System.err.println("\nFrame $frame: ${reads.size} $2002 reads")
            val bit7True = reads.count { it.bit7 }
            val bit7False = reads.count { !it.bit7 }
            System.err.println("  Bit 7 = 1: $bit7True times")
            System.err.println("  Bit 7 = 0: $bit7False times")

            // Show first few reads with values
            if (reads.isNotEmpty()) {
                System.err.println("  First 5 reads:")
                reads.take(5).forEach { read ->
                    System.err.println("    Frame ${read.frame}, value=$${String.format("%02X", read.value)} bit7=${read.bit7}")
                }
            }
        }

        // Find $2002 reads in instruction trace
        System.err.println("\n=== Looking for $2002 reads in instruction trace ===")
        // LDA $2002 is opcode $AD (absolute addressing)
        val reads2002 = trace.filter { (pc, op) -> op == 0xAD }
        System.err.println("Found ${reads2002.size} LDA $2002 instructions in trace")

        // Show first few
        reads2002.take(10).forEach { (pc, op) ->
            System.err.println("  PC=$${String.format("%04X", pc)} opcode=$${String.format("%02X", op)}")
        }

        // Look for reads from $C02D and $C037 specifically
        System.err.println("\n=== Looking for reads from \$C02D/\$C037 area ===")
        // The instruction trace only shows the opcode PC, not operand bytes
        // But we can look for instructions in that address range
        val readsNearPoll = trace.filter { (pc, _) -> pc in 0xC020..0xC040 }
        System.err.println("Instructions in \$C020-\$C040 range: ${readsNearPoll.size}")
        readsNearPoll.take(20).forEach { (pc, op) ->
            System.err.println("  PC=$${String.format("%04X", pc)} opcode=$${String.format("%02X", op)}")
        }

        System.err.println("\n=== Instruction trace summary ===")
        System.err.println("Total instructions traced: ${trace.size}")

        nestlin.stop()
    }

    @Test
    fun findNmiHandlerAndPollLoop() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Enable instruction trace
        val trace = nestlin.cpu.enableInstructionTrace(10000)

        var nmiCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                nmiCount++
                if (nmiCount >= 3) nestlin.stop()
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Find NMI handler entries - look for the JSR/JSF pattern after NMI
        // NMI typically does JSR to handler
        System.err.println("\n=== Looking for NMI-related instructions ===")

        // Find instructions in the NMI vector area ($FFFA-$FFFB points to handler)
        // After NMI fires, PC should be at the handler address
        // Let's look for instructions after NMI vectors are read

        // Check what PC values appear right after NMI vector reads
        // NMI vector is at $FFFA-$FFFB
        System.err.println("Instructions after PC reaches \$C000+ area (NMI handler area):")
        val nmiHandlerArea = trace.filter { (pc, _) -> pc >= 0xC000 && pc < 0xC100 }
        nmiHandlerArea.take(30).forEach { (pc, op) ->
            System.err.println("  PC=$${String.format("%04X", pc)} opcode=$${String.format("%02X", op)}")
        }

        System.err.println("\nTotal NMI frames observed: $nmiCount")
        System.err.println("Total instructions: ${trace.size}")

        nestlin.stop()
    }
}
