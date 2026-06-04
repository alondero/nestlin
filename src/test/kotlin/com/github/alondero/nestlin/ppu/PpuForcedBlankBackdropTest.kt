package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression for the Micro Machines (mapper 71) "band that doesn't transition"
 * artifact (issue #88 follow-up).
 *
 * When a game disables rendering mid-frame (PPUMASK bit 3/4 both clear) — a common
 * trick to bulk-update palette/VRAM during a forced-blank window — a real PPU still
 * scans out the backdrop color ($3F00) for every visible pixel. Nestlin used to only
 * write the frame buffer inside the rendering-enabled path, so those scanlines kept
 * whatever pixels were there before: a frozen horizontal "band" of stale tiles.
 */
class PpuForcedBlankBackdropTest {

    private fun runOneFrame(ppu: Ppu): Frame {
        var captured: Frame? = null
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) { captured = frame }
        })
        // Tick until a frame is delivered (one full frame is < region.totalScanlines * 341 dots).
        var guard = 400_000
        while (captured == null && guard-- > 0) ppu.tick()
        return captured ?: error("PPU did not complete a frame")
    }

    @Test
    fun `rendering disabled scans out backdrop color for visible pixels`() {
        val memory = Memory()
        val ppu = Ppu(memory)

        // Backdrop ($3F00) = NES color index 0x21 (a blue). Distinct from the frame
        // buffer's initial black so a missing write is detectable.
        val backdropIndex = 0x21
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F00] = backdropIndex.toByte()

        // Rendering fully disabled (mask = 0): no background, no sprites.
        memory.ppuAddressedMemory.mask.register = 0

        val frame = runOneFrame(ppu)

        val expected = NesPalette.getRgb(backdropIndex)
        // Sample a spread of visible pixels — all should be the backdrop, none stale/black.
        for (y in intArrayOf(0, 60, 110, 120, 200, 239)) {
            for (x in intArrayOf(0, 50, 128, 200, 255)) {
                assertThat("pixel ($x,$y)", frame.scanlines[y][x], equalTo(expected))
            }
        }
    }
}
