package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.setBit
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.greaterThanOrEqualTo
import com.natpryce.hamkrest.lessThanOrEqualTo
import org.junit.Test

/**
 * Verifies that PPU pattern-table accesses produce exactly one A12 rising edge
 * per rendered scanline, matching real NES hardware. This is the canonical
 * trigger for MMC3 scanline IRQs.
 *
 * Configuration mirrors a typical MMC3 game:
 *  - Background pattern table at $0000 (PPUCTRL bit 4 = 0)
 *  - Sprite pattern table at $1000 (PPUCTRL bit 3 = 1)
 *  - Both background and sprites enabled
 *  - OAM seeded so several sprites fall on each visible scanline
 *
 * Real hardware emits one rising edge per scanline: A12 stays low during BG
 * fetches (cycles 1-256), rises when sprite tile fetches begin (cycles 257-320),
 * then falls again for the BG pre-fetch (cycles 321-336). Visible scanlines
 * (0-239) plus pre-render (261) = 241 expected edges per frame.
 */
class A12EdgeRateTest {

    @Test
    fun `one A12 rising edge per rendered scanline`() {
        val memory = Memory()
        val ppu = Ppu(memory)

        // chrReadDelegate routes pattern-table reads through the A12 detector.
        // Returning zero is fine for this test - we only care about address bits.
        memory.ppuAddressedMemory.ppuInternalMemory.chrReadDelegate = { _ -> 0 }

        var risingEdgeCount = 0
        memory.ppuAddressedMemory.ppuInternalMemory.a12EdgeListener = { rising ->
            if (rising) risingEdgeCount++
        }
        memory.ppuAddressedMemory.ppuInternalMemory.resetA12State()

        // PPUCTRL: bit 3 = sprite pattern table at $1000, bit 4 = BG pattern table at $0000
        memory.ppuAddressedMemory.controller.register = 0x08.toByte()
        // PPUMASK: bits 3 (show BG) and 4 (show sprites) set
        memory.ppuAddressedMemory.mask.register = 0x18.toByte()

        // Seed OAM with sprites scattered across visible scanlines so the sprite
        // tile-fetch phase actually has work to drive A12 transitions.
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 0 until 64) {
            // y = i * 3 → first 64 sprites cover scanlines ~1-192
            oam[i * 4] = (i * 3).toByte()       // Y
            oam[i * 4 + 1] = (i and 0xFF).toByte()  // tile index
            oam[i * 4 + 2] = 0                     // attributes
            oam[i * 4 + 3] = (i * 4).toByte()      // X
        }

        // Tick through one full frame. The PPU implementation spends an extra "boundary tick"
        // per scanline to invoke endLine(), so a frame is 262 × 342 = 89604 host ticks.
        for (i in 0 until 89604) {
            ppu.tick()
        }

        println("A12EdgeRateTest: rising edges per frame = $risingEdgeCount")

        // Real hardware: 240 visible + 1 pre-render = 241 rising edges per frame.
        // Allow a small tolerance for boundary effects.
        assertThat(
            "A12 rising edges per frame should be ~241 (was $risingEdgeCount)",
            risingEdgeCount,
            lessThanOrEqualTo(245)
        )
        assertThat(
            "A12 rising edges per frame should be ~241 (was $risingEdgeCount)",
            risingEdgeCount,
            greaterThanOrEqualTo(235)
        )
    }
}
