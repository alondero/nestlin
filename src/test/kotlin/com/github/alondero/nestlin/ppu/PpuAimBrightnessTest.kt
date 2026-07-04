package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import org.junit.jupiter.api.Test

/**
 * Regression for the Zapper light-gun aim/hit mismatch (issue #209 follow-up).
 *
 * The Zapper light sensor samples [Ppu.aimBrightness], which reads the frame the PPU
 * is *currently* drawing (not the last published one). Two properties matter:
 *  - it reflects the live frame's pixels, so a game's hit lines up with the aim in the
 *    same frame the target is flashed (the earlier "read last completed frame" version
 *    lagged a frame and decoupled hits from where the player pointed); and
 *  - it returns "dark" for a row the beam hasn't reached yet, since the reused [Frame]
 *    still holds the previous frame's colour there ([beamHasDrawnRow]).
 */
class PpuAimBrightnessTest {

    /** Tick a fresh PPU up to the start of vblank, so every visible row (0..239) of the
     *  current frame is drawn and the beam (scanline >= 240) is past all of them — the
     *  raster state in which a game reads `$4017` for hit detection. */
    private fun tickToVblank(ppu: Ppu) {
        var guard = 400_000
        while (ppu.currentScanline < POST_RENDER_SCANLINE && guard-- > 0) ppu.tick()
        check(ppu.currentScanline >= POST_RENDER_SCANLINE) { "PPU never reached vblank" }
    }

    @Test
    fun `beamHasDrawnRow is true only once the beam has moved strictly past the row`() {
        assertThat(beamHasDrawnRow(targetY = 100, beamScanline = 50), equalTo(false))   // beam above
        assertThat(beamHasDrawnRow(targetY = 100, beamScanline = 100), equalTo(false))  // beam on the row
        assertThat(beamHasDrawnRow(targetY = 100, beamScanline = 101), equalTo(true))   // beam past
        assertThat(beamHasDrawnRow(targetY = 239, beamScanline = 241), equalTo(true))   // vblank: all rows done
    }

    @Test
    fun `aimBrightness reads the live frame's bright backdrop after it is drawn`() {
        val memory = Memory()
        val ppu = Ppu(memory)

        // Force-blank the whole frame to a BRIGHT backdrop (white, index 0x30). With
        // rendering disabled the PPU scans out the backdrop for every visible pixel.
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F00] = 0x30.toByte()
        memory.ppuAddressedMemory.mask.register = 0

        tickToVblank(ppu)   // beam past every visible row, frame drawn

        // On-screen pixels report the bright backdrop (R+G+B well over the 384 cutoff).
        assertThat(ppu.aimBrightness(128, 120), greaterThan(384))
        assertThat(ppu.aimBrightness(0, 0), greaterThan(384))
        assertThat(ppu.aimBrightness(255, 239), greaterThan(384))
    }

    @Test
    fun `aimBrightness returns -1 for off-screen coordinates`() {
        val ppu = Ppu(Memory())
        assertThat(ppu.aimBrightness(-1, 0), equalTo(-1))
        assertThat(ppu.aimBrightness(0, -1), equalTo(-1))
        assertThat(ppu.aimBrightness(RESOLUTION_WIDTH, 0), equalTo(-1))
        assertThat(ppu.aimBrightness(0, RESOLUTION_HEIGHT), equalTo(-1))
    }

    @Test
    fun `aimBrightness reports dark for a row the beam has not reached yet`() {
        // Fresh PPU: beam is at scanline 0, so a lower row has not been drawn this
        // frame. The gate must return 0 rather than the frame's (stale) contents.
        val ppu = Ppu(Memory())
        assertThat(ppu.aimBrightness(128, 200), equalTo(0))
    }
}
