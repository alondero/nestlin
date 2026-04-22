package com.github.alondero.nestlin.compare

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * Simple test to verify both Nestlin state capture and Mesen2 state capture work.
 */
class StateCaptureIntegrationTest {

    @Test
    fun captureNestlinStateAtFrame60() {
        val romPath = Paths.get("testroms/tetris.nes")
        val state = NestlinStateCapturer.captureState(romPath, 60)

        println("=== Nestlin State at frame 60 for tetris.nes ===")
        println("CPU: PC=0x${state.cpu.pc.toString(16).uppercase()}, A=0x${state.cpu.a.toString(16).uppercase()}, X=0x${state.cpu.x.toString(16).uppercase()}, Y=0x${state.cpu.y.toString(16).uppercase()}, SP=0x${state.cpu.sp.toString(16).uppercase()}, Status=0x${state.cpu.status.toString(16).uppercase()}")
        println("PPU: cycle=${state.ppu.cycle}, scanline=${state.ppu.scanline}, frameCount=${state.ppu.frameCount}")
        println("CPU RAM[0x00-0x0F]: ${state.cpuRam.slice(0..15).map { "0x${it.toString(16).uppercase()}" }.joinToString(", ")}")
    }

    @Test
    fun captureMesen2StateAtFrame60() {
        assumeTrue("Mesen2 not available", Mesen2StateCapturer.isMesen2Available())

        val romPath = Paths.get("testroms/tetris.nes")
        val state = Mesen2StateCapturer.captureState(romPath, 60)

        println("=== Mesen2 State at frame 60 for tetris.nes ===")
        println("CPU: PC=0x${state.cpu.pc.toString(16).uppercase()}, A=0x${state.cpu.a.toString(16).uppercase()}, X=0x${state.cpu.x.toString(16).uppercase()}, Y=0x${state.cpu.y.toString(16).uppercase()}, SP=0x${state.cpu.sp.toString(16).uppercase()}, Status=0x${state.cpu.status.toString(16).uppercase()}")
        println("PPU: cycle=${state.ppu.cycle}, scanline=${state.ppu.scanline}, frameCount=${state.ppu.frameCount}")
        println("CPU RAM[0x00-0x0F]: ${state.cpuRam.slice(0..15).map { "0x${it.toString(16).uppercase()}" }.joinToString(", ")}")
    }

    @Test
    fun compareNestlinAndMesen2() {
        assumeTrue("Mesen2 not available", Mesen2StateCapturer.isMesen2Available())

        val romPath = Paths.get("testroms/tetris.nes")

        val nestlinState = NestlinStateCapturer.captureState(romPath, 60)
        val mesen2State = Mesen2StateCapturer.captureState(romPath, 60)

        println("=== State Comparison at frame 60 for tetris.nes ===")
        println("NESTLIN: PC=0x${nestlinState.cpu.pc.toString(16).uppercase()}, A=0x${nestlinState.cpu.a.toString(16).uppercase()}")
        println("MESEN2:  PC=0x${mesen2State.cpu.pc.toString(16).uppercase()}, A=0x${mesen2State.cpu.a.toString(16).uppercase()}")

        val diff = StateComparator.compare(nestlinState, mesen2State)
        println("Match: ${diff.match}")
        if (!diff.match) {
            println("Mismatches found:")
            diff.cpuMismatches.forEach { println("  CPU ${it.field}: Nestlin=0x${it.nestlinValue.toString(16)}, Mesen2=0x${it.mesen2Value.toString(16)}") }
            diff.memoryMismatches.take(5).forEach { println("  RAM[0x${it.address.toString(16)}]: Nestlin=0x${it.nestlinValue.toString(16)}, Mesen2=0x${it.mesen2Value.toString(16)}") }
        }
    }
}
