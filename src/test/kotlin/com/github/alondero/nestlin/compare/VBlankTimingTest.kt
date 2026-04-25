package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths

/**
 * Targeted test to find exactly where VBlank gets set vs when NMI fires.
 */
class VBlankTimingTest {

    @Test
    fun trackVBlankAndNmiAcrossFrames() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        val vBlankStates = mutableListOf<String>()

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++

                val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
                val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                val vBlank = (status shr 7) and 1
                val nmiOccurred = nestlin.memory.ppuAddressedMemory.nmiOccurred

                vBlankStates.add("Frame $frameCount: status=\$${String.format("%02X", status)} (VBlank=$vBlank) mask=\$${String.format("%02X", mask)} nmiOcc=$nmiOccurred PC=\$${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")

                if (frameCount >= 5) {
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        System.err.println("\n=== VBlank State Across Frames ===")
        vBlankStates.forEach { System.err.println(it) }
    }

    @Test
    fun findWhereVBlankIsSetManual() {
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

        // Set running = true via reflection so we can manually tick
        val runningField = nestlin.javaClass.getDeclaredField("running")
        runningField.isAccessible = true
        runningField.setBoolean(nestlin, true)

        var cycles = 0
        val maxCycles = 100000
        var vBlankSetAt: Pair<Int, Int>? = null
        var nmiFiredAt: Pair<Int, Int>? = null
        var lastStatus = 0
        var statusChanges = 0

        while (cycles < maxCycles) {
            for (i in 0 until 3) nestlin.ppu.tick()
            nestlin.apu.tick()
            nestlin.cpu.tick()
            cycles++

            val scanline = scanlineField.getInt(nestlin.ppu)
            val cycle = cycleField.getInt(nestlin.ppu)
            val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()

            // Track status changes
            if (status != lastStatus) {
                if (statusChanges < 30) {
                    System.err.println("Status change at scanline=$scanline cycle=$cycle: \$${String.format("%02X", lastStatus)} -> \$${String.format("%02X", status)}")
                }
                statusChanges++
                lastStatus = status
            }

            // Check if VBlank bit 7 just got set
            if ((status and 0x80) != 0 && vBlankSetAt == null) {
                vBlankSetAt = Pair(scanline, cycle)
                System.err.println("VBlank bit 7 FIRST SET at scanline=$scanline cycle=$cycle")
            }

            // Check if nmiOccurred just went true
            if (nestlin.memory.ppuAddressedMemory.nmiOccurred && nmiFiredAt == null) {
                nmiFiredAt = Pair(scanline, cycle)
                System.err.println("nmiOccurred FIRST TRUE at scanline=$scanline cycle=$cycle")
            }

            // Every 20000 cycles show progress
            if (cycles % 20000 == 0) {
                System.err.println("Cycles=$cycles: scanline=$scanline cycle=$cycle status=\$${String.format("%02X", status)} nmiOcc=${nestlin.memory.ppuAddressedMemory.nmiOccurred}")
            }

            // Stop after we find both events
            if (vBlankSetAt != null && nmiFiredAt != null) {
                break
            }
        }

        System.err.println("\n=== VBlank Timing Results ===")
        System.err.println("Total status changes: $statusChanges")
        if (vBlankSetAt == null) {
            System.err.println("VBlank bit 7 was NEVER set in $cycles cycles!")
        } else {
            System.err.println("VBlank set at: scanline=${vBlankSetAt.first}, cycle=${vBlankSetAt.second}")
        }
        if (nmiFiredAt == null) {
            System.err.println("NMI was NEVER fired in $cycles cycles!")
        } else {
            System.err.println("NMI fired at: scanline=${nmiFiredAt.first}, cycle=${nmiFiredAt.second}")
        }
        System.err.println("Final status: \$${String.format("%02X", lastStatus)}")

        nestlin.stop()
    }

    @Test
    fun checkPpuStateAtScanline241() {
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

        var cycles = 0
        val maxCycles = 100000

        while (cycles < maxCycles) {
            for (i in 0 until 3) nestlin.ppu.tick()
            nestlin.apu.tick()
            nestlin.cpu.tick()
            cycles++

            val scanline = scanlineField.getInt(nestlin.ppu)
            val cycle = cycleField.getInt(nestlin.ppu)

            // At scanline 241, cycle 1 - check VBlank
            if (scanline == 241 && cycle == 1) {
                val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
                val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                val nmiOcc = nestlin.memory.ppuAddressedMemory.nmiOccurred

                System.err.println("\n=== At scanline=241, cycle=1 ===")
                System.err.println("status: \$${String.format("%02X", status)} (bit7=${(status shr 7) and 1})")
                System.err.println("mask: \$${String.format("%02X", mask)}")
                System.err.println("nmiOccurred: $nmiOcc")
                System.err.println("CPU PC: \$${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
                break
            }
        }

        nestlin.stop()
    }

    @Test
    fun traceCpuAndPpuTogether() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Trace first 3000 instructions
        nestlin.cpu.enableInstructionTrace(3000)

        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount >= 2) {
                    System.err.println("\n=== After frame $frameCount ===")
                    System.err.println("CPU PC: \$${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
                    System.err.println("PPU \$2000 (ctrl): \$${String.format("%02X", nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt())}")
                    System.err.println("PPU \$2001 (mask): \$${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")
                    System.err.println("PPU \$2002 (status): \$${String.format("%02X", nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt())}")
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Get the trace list via reflection
        val cpu = nestlin.cpu
        val traceField = cpu.javaClass.getDeclaredField("instructionTrace")
        traceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val trace = traceField.get(cpu) as? MutableList<Pair<Int, Int>>

        // Print first 100 instructions and last 50 instructions
        System.err.println("\n=== First 100 Instructions ===")
        trace?.take(100)?.forEachIndexed { idx, (pc, opcode) ->
            System.err.println("${idx.toString().padStart(4)}: PC=\$${String.format("%04X", pc)} op=\$${String.format("%02X", opcode)}")
        }

        System.err.println("\n=== Last 50 Instructions (before frame 2) ===")
        trace?.takeLast(50)?.forEachIndexed { idx, (pc, opcode) ->
            System.err.println("${(trace.size - 50 + idx).toString().padStart(4)}: PC=\$${String.format("%04X", pc)} op=\$${String.format("%02X", opcode)}")
        }
    }
}