package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths

/**
 * Detailed VBlank timing debug test.
 * Watches PPU state at exact cycle when VBlank should be set.
 */
class KirbyVBlankTest {

    @Test
    fun trackVBlankOverTime() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Trace first 500 instructions
        nestlin.cpu.enableInstructionTrace(500)

        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++

                // Check VBlank state at each frame
                val status = nestlin.memory.ppuAddressedMemory.status
                val nmiOccurred = nestlin.memory.ppuAddressedMemory.nmiOccurred

                System.err.println("Frame $frameCount completed")
                System.err.println("  PPU status register: ${String.format("%02X", status.register.toUnsignedInt())}")
                System.err.println("  VBlank (bit 7): ${if (status.vBlankStarted()) 1 else 0}")
                System.err.println("  nmiOccurred: $nmiOccurred")
                System.err.println("  mask: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")
                System.err.println("  CPU PC: ${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")

                if (frameCount >= 10) nestlin.stop()
            }
        })

        nestlin.powerReset()
        nestlin.start()

        System.err.println("\n=== Final Summary ===")
        System.err.println("Frames rendered: $frameCount")
        System.err.println("Total CPU instructions: ${nestlin.cpu.getInstructionCount()}")
    }

    @Test
    fun checkPpuStateDuringWaitLoop() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        nestlin.cpu.enableInstructionTrace(2000)

        // Custom run loop to check PPU state periodically
        nestlin.powerReset()

        try {
            val runningField = nestlin.javaClass.getDeclaredField("running")
            runningField.isAccessible = true

            var cycles = 0
            val maxCycles = 50000

            while (runningField.getBoolean(nestlin) && cycles < maxCycles) {
                for (i in 0 until 3) nestlin.ppu.tick()
                nestlin.apu.tick()
                nestlin.cpu.tick()
                cycles++

                // Every 10000 cycles, check state
                if (cycles % 10000 == 0) {
                    val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                    val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
                    val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                    System.err.println("Cycles=$cycles PC=${String.format("%04X", pc)} status=${String.format("%02X", status)} mask=${String.format("%02X", mask)}")
                }
            }

            System.err.println("\nFinal state after $cycles cycles:")
            System.err.println("CPU PC: ${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
            System.err.println("PPU status: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt())}")
            System.err.println("PPU mask: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")

        } catch (e: Exception) {
            e.printStackTrace()
        }

        nestlin.stop()
    }

    @Test
    fun checkVBlankStateAtScanline241Cycle1() {
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

        var lastStatusAt241_1 = -1

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                // Frame completed
            }
        })

        nestlin.powerReset()

        try {
            val runningField = nestlin.javaClass.getDeclaredField("running")
            runningField.isAccessible = true

            var cycles = 0
            val maxCycles = 100000
            var frameNum = 0

            while (runningField.getBoolean(nestlin) && cycles < maxCycles) {
                for (i in 0 until 3) nestlin.ppu.tick()
                nestlin.apu.tick()
                nestlin.cpu.tick()
                cycles++

                val scanline = scanlineField.getInt(nestlin.ppu)
                val cycle = cycleField.getInt(nestlin.ppu)

                // Check at scanline 241, cycle 1 (where VBlank should be set)
                if (scanline == 241 && cycle == 1) {
                    val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
                    val nmiOccurred = nestlin.memory.ppuAddressedMemory.nmiOccurred
                    val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                    System.err.println("Frame $frameNum: At scanline=241 cycle=1:")
                    System.err.println("  PPU status: ${String.format("%02X", status)} (bit7=${status shr 7})")
                    System.err.println("  nmiOccurred: $nmiOccurred")
                    System.err.println("  mask: ${String.format("%02X", mask)}")
                    System.err.println("  CPU PC: ${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
                }

                // At cycle 341 of scanline 261, frame ends
                if (scanline == 261 && cycle == 341) {
                    frameNum++
                    // Check status just before frame ends
                    val status = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
                    System.err.println("Frame $frameNum ending: status=${String.format("%02X", status)} nmiOccurred=${nestlin.memory.ppuAddressedMemory.nmiOccurred}")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        nestlin.stop()
    }

    @Test
    fun logAllVBlankEvents() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Use reflection to track scanline/cycle at VBlank set time
        val ppuClass = nestlin.ppu.javaClass
        val cycleField = ppuClass.getDeclaredField("cycle")
        cycleField.isAccessible = true
        val scanlineField = ppuClass.getDeclaredField("scanline")
        scanlineField.isAccessible = true

        var nmiCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                // Check if NMI just occurred for this frame
                val nmiNow = nestlin.memory.ppuAddressedMemory.nmiOccurred
                val scanline = scanlineField.getInt(nestlin.ppu)
                val cycle = cycleField.getInt(nestlin.ppu)

                if (nmiNow) {
                    nmiCount++
                    System.err.println("Frame ${nmiCount}: nmiOccurred=true at scanline=$scanline cycle=$cycle")
                    System.err.println("  PPU status: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt())}")
                    System.err.println("  CPU PC: ${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
                }

                if (nmiCount >= 5) nestlin.stop()
            }
        })

        nestlin.powerReset()
        nestlin.start()

        System.err.println("\nTotal NMI events: $nmiCount")
    }
}