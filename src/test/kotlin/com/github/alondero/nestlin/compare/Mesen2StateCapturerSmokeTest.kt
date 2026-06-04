package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class Mesen2StateCapturerSmokeTest {
    @Test
    fun capturesNestestStateViaTestRunner() {
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")
        val rom = Paths.get("testroms/nestest.nes")
        val state = Mesen2StateCapturer.captureState(rom, 60)
        println("Mesen2 nestest.nes frame 60: PC=0x${state.cpu.pc.toString(16).uppercase()} " +
            "A=0x${state.cpu.a.toString(16).uppercase()} " +
            "scanline=${state.ppu.scanline} ppuFrameCount=${state.ppu.frameCount}")
        assert(state.cpu.pc != 0) { "PC should be non-zero after 60 frames of execution" }
        assert(state.ppu.frameCount > 0) { "frameCount should be > 0 after 60 frames" }
    }
}
