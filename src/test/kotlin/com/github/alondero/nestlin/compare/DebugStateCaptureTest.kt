package com.github.alondero.nestlin.compare

import org.junit.Test
import java.nio.file.Paths

/**
 * Simple test to capture Nestlin state at various frames and inspect for debugging.
 */
class DebugStateCaptureTest {

    @Test
    fun captureTetrisStateAtFrame60() {
        val romPath = Paths.get("testroms/tetris.nes")
        val state = NestlinStateCapturer.captureState(romPath, 60)

        println("=== Nestlin State at frame 60 for tetris.nes ===")
        println("CPU: PC=0x${state.cpu.pc.toString(16).uppercase()}, A=0x${state.cpu.a.toString(16).uppercase()}, X=0x${state.cpu.x.toString(16).uppercase()}, Y=0x${state.cpu.y.toString(16).uppercase()}, SP=0x${state.cpu.sp.toString(16).uppercase()}, Status=0x${state.cpu.status.toString(16).uppercase()}")
        println("PPU: cycle=${state.ppu.cycle}, scanline=${state.ppu.scanline}, frameCount=${state.ppu.frameCount}")
        println("PPU Registers: control=0x${state.ppuRegisters.controller.toString(16).uppercase()}, mask=0x${state.ppuRegisters.mask.toString(16).uppercase()}, status=0x${state.ppuRegisters.status.toString(16).uppercase()}")
        println("CPU RAM first 16 bytes: ${state.cpuRam.take(16).map { "0x${it.toString(16).uppercase()}" }.joinToString(", ")}")
        println("CPU RAM at 0x00FC-0x00FF: ${state.cpuRam.slice(0xFC..0xFF).map { "0x${it.toString(16).uppercase()}" }.joinToString(", ")}")

        println("\nJSON output:")
        println(state.toJson())
    }

    @Test
    fun captureKirbyStateAtFrame150() {
        val romPath = Paths.get("testroms/kirby.nes")
        val state = NestlinStateCapturer.captureState(romPath, 150)

        println("=== Nestlin State at frame 150 for kirby.nes ===")
        println("CPU: PC=0x${state.cpu.pc.toString(16).uppercase()}, A=0x${state.cpu.a.toString(16).uppercase()}, X=0x${state.cpu.x.toString(16).uppercase()}, Y=0x${state.cpu.y.toString(16).uppercase()}, SP=0x${state.cpu.sp.toString(16).uppercase()}, Status=0x${state.cpu.status.toString(16).uppercase()}")
        println("PPU: cycle=${state.ppu.cycle}, scanline=${state.ppu.scanline}, frameCount=${state.ppu.frameCount}")
        println("PPU Registers: control=0x${state.ppuRegisters.controller.toString(16).uppercase()}, mask=0x${state.ppuRegisters.mask.toString(16).uppercase()}, status=0x${state.ppuRegisters.status.toString(16).uppercase()}")
        println("Cycle count: ${state.cpu.cycleCount}")

        println("\nJSON output:")
        println(state.toJson())
    }
}