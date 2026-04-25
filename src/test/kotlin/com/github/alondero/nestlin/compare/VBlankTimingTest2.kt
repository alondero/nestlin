package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths

/**
 * Test to understand timing between VBlank set and $2002 reads.
 */
class VBlankTimingTest2 {

    @Test
    fun traceStatusAroundVBlankSet() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Get PPU private fields for monitoring
        val ppuClass = nestlin.ppu.javaClass
        val cycleField = ppuClass.getDeclaredField("cycle")
        cycleField.isAccessible = true
        val scanlineField = ppuClass.getDeclaredField("scanline")
        scanlineField.isAccessible = true

        nestlin.powerReset()

        // Set running = true via reflection
        val runningField = nestlin.javaClass.getDeclaredField("running")
        runningField.isAccessible = true
        runningField.setBoolean(nestlin, true)

        // Enable instruction trace to see what CPU is doing
        nestlin.cpu.enableInstructionTrace(5000)

        var cycles = 0
        val maxCycles = 100000
        var vBlankSeen = false

        while (cycles < maxCycles) {
            for (i in 0 until 3) nestlin.ppu.tick()
            nestlin.apu.tick()
            nestlin.cpu.tick()
            cycles++

            val scanline = scanlineField.getInt(nestlin.ppu)
            val cycle = cycleField.getInt(nestlin.ppu)
            val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()

            // Track when VBlank first appears
            if ((status and 0x80) != 0 && !vBlankSeen) {
                vBlankSeen = true
                System.err.println("\n=== VBlank FIRST SET at scanline=$scanline cycle=$cycle ===")
                System.err.println("CPU PC at this moment: \$${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
            }

            // Check every cycle from VBlank set through next few cycles
            if (vBlankSeen && cycles < 100000) {
                if (cycles % 100 == 0) { // Log every 100 cycles around this area
                    val cpuPc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                    System.err.println("After VBlank set: cycle=$cycles scanline=$scanline cycle=$cycle status=\$${String.format("%02X", status)} CPU PC=\$${String.format("%04X", cpuPc)}")
                }
            }
        }

        nestlin.stop()

        // Print instruction trace
        val cpu = nestlin.cpu
        val traceField = cpu.javaClass.getDeclaredField("instructionTrace")
        traceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val trace = traceField.get(cpu) as? MutableList<Pair<Int, Int>>

        System.err.println("\n=== Instructions around VBlank (first 200 after VBlank set) ===")
        val vBlankSetIndex = trace?.indexOfFirst { (pc, op) -> false } ?: -1 // Find by looking at when PC is near the VBlank set point

        // Just print first 200 instructions total
        trace?.take(200)?.forEachIndexed { idx, (pc, opcode) ->
            System.err.println("${idx.toString().padStart(4)}: PC=\$${String.format("%04X", pc)} op=\$${String.format("%02X", opcode)}")
        }
    }

    @Test
    fun findVBlankAndFirstRead() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        val ppuClass = nestlin.ppu.javaClass
        val cycleField = ppuClass.getDeclaredField("cycle")
        cycleField.isAccessible = true
        val scanlineField = ppuClass.getDeclaredField("scanline")
        scanlineField.isAccessible = true

        nestlin.powerReset()

        val runningField = nestlin.javaClass.getDeclaredField("running")
        runningField.isAccessible = true
        runningField.setBoolean(nestlin, true)

        // Track VBlank set vs CPU reads to $2002
        var vBlankSetCycle = 0L
        var vBlankSetScanline = 0
        var vBlankSetPpuCycle = 0
        var cpuRead2002Count = 0

        // Patch cpu tick to count $2002 reads
        // Actually we can check memory accesses via instruction trace
        nestlin.cpu.enableInstructionTrace(10000)

        var cycles = 0L
        val maxCycles = 100000L

        while (cycles < maxCycles) {
            for (i in 0 until 3) nestlin.ppu.tick()
            nestlin.apu.tick()
            nestlin.cpu.tick()
            cycles++

            val scanline = scanlineField.getInt(nestlin.ppu)
            val cycle = cycleField.getInt(nestlin.ppu)
            val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()

            // VBlank set
            if ((status and 0x80) != 0 && vBlankSetCycle == 0L) {
                vBlankSetCycle = cycles
                vBlankSetScanline = scanline
                vBlankSetPpuCycle = cycle
            }

            // CPU is executing - check if reading $2002
            val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
            val opcode = nestlin.memory[pc]
            // LDA absolute $2002 = opcode $AD, bytes $2D $20 $00
            if (opcode.toUnsignedInt() == 0xAD) {
                val low = nestlin.memory[pc + 1].toUnsignedInt()
                val high = nestlin.memory[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                if (addr == 0x2002) {
                    cpuRead2002Count++
                    if (cpuRead2002Count <= 5) {
                        System.err.println("CPU READ $2002 at scanline=$scanline cycle=$cycle (status=\$${String.format("%02X", status)})")
                    }
                }
            }
        }

        System.err.println("\n=== Summary ===")
        System.err.println("VBlank set at: cycle=$vBlankSetCycle, scanline=$vBlankSetScanline, ppuCycle=$vBlankSetPpuCycle")
        System.err.println("Total CPU reads of $2002: $cpuRead2002Count")

        nestlin.stop()
    }
}