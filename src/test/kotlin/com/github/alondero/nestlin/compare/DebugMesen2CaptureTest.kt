package com.github.alondero.nestlin.compare

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Test to verify Mesen2 state capture works.
 */
class DebugMesen2CaptureTest {

    @Test
    fun captureTetrisStateFromMesen2() {
        println("Checking Mesen2 availability...")
        println("Mesen2 available: ${Mesen2StateCapturer.isMesen2Available()}")

        val mesenAvailable = Mesen2StateCapturer.isMesen2Available()

        // Write availability status to a file
        val debugDir = Paths.get("build/debug-state")
        Files.createDirectories(debugDir)
        Files.writeString(debugDir.resolve("mesen2-available.txt"), mesenAvailable.toString())

        if (!mesenAvailable) {
            println("Mesen2 not available - skipping")
            return
        }

        val romPath = Paths.get("testroms/tetris.nes")
        println("Capturing Mesen2 state for tetris.nes at frame 60...")

        try {
            val state = Mesen2StateCapturer.captureState(romPath, 60)

            // Write to file
            Files.writeString(debugDir.resolve("mesen2-state.json"), state.toJson())

            println("=== Mesen2 State at frame 60 for tetris.nes ===")
            println("CPU: PC=0x${state.cpu.pc.toString(16).uppercase()}, A=0x${state.cpu.a.toString(16).uppercase()}, X=0x${state.cpu.x.toString(16).uppercase()}, Y=0x${state.cpu.y.toString(16).uppercase()}, SP=0x${state.cpu.sp.toString(16).uppercase()}, Status=0x${state.cpu.status.toString(16).uppercase()}")
            println("PPU: cycle=${state.ppu.cycle}, scanline=${state.ppu.scanline}, frameCount=${state.ppu.frameCount}")
            println("PPU Registers: control=0x${state.ppuRegisters.controller.toString(16).uppercase()}, mask=0x${state.ppuRegisters.mask.toString(16).uppercase()}, status=0x${state.ppuRegisters.status.toString(16).uppercase()}")
            println("Cycle count: ${state.cpu.cycleCount}")

            // Also capture Nestlin for comparison
            val nestlinState = NestlinStateCapturer.captureState(romPath, 60)
            Files.writeString(debugDir.resolve("nestlin-state.json"), nestlinState.toJson())

            println("\n=== Comparison ===")
            println("CPU cycleCount: Nestlin=${nestlinState.cpu.cycleCount}, Mesen2=${state.cpu.cycleCount}")
            println("CPU PC: Nestlin=0x${nestlinState.cpu.pc.toString(16).uppercase()}, Mesen2=0x${state.cpu.pc.toString(16).uppercase()}")

        } catch (e: Exception) {
            println("Mesen2 capture failed: ${e.message}")
            e.printStackTrace()
            Files.writeString(debugDir.resolve("mesen2-error.txt"), "${e.message}\n")
        }
    }
}