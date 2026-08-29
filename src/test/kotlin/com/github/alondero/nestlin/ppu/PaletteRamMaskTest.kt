package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Issue #293 acceptance criterion 1 / 2: palette RAM stores at most 6 bits per
 * entry, even when the caller writes a full byte. The mask is applied at the
 * [PaletteRam] ownership boundary so every write alias (including the
 * $3F10/$3F14/$3F18/$3F1C → $3F00/$04/$08/$0C mirrors) is covered.
 *
 * Rendering masks via `NesPalette.getRgb(index and 0x3F)` downstream, so an
 * un-masked storage was silently hidden from pixels — but CPU reads via $2007
 * would see the upper bits and games using them for state would diverge.
 */
class PaletteRamMaskTest {

    @Test
    fun `direct palette write masks to six bits`() {
        val palette = PaletteRam()
        palette[0] = 0xFF.toByte()
        assertThat("\$FF masked to \$3F", palette[0].toUnsignedInt(), equalTo(0x3F))
    }

    @Test
    fun `every palette write alias masks to six bits`() {
        val palette = PaletteRam()
        // Drive each unique underlying slot ($3F00..$3F0F) via the public index,
        // including the four $3F1x mirrors. Each write should mask.
        for (index in 0..0x1F) {
            palette[index] = 0xC3.toByte() // arbitrary non-zero pattern with high bits set
        }
        for (index in 0..0x1F) {
            val stored = palette[index].toUnsignedInt()
            assertThat(
                "palette[$index] should be masked to \$3F",
                stored,
                equalTo(stored and 0x3F),
            )
        }
    }

    /**
     * Mirroring ($3F10/$14/$18/$1C → $3F00/$04/$08/$0C) must survive the mask:
     * writing $C0 to the mirror must still show up as the masked underlying byte.
     */
    @Test
    fun `mirrored write masks but still aliases to underlying entry`() {
        val palette = PaletteRam()
        // $3F10 is the backdrop mirror — write a full byte via the mirrored alias.
        palette[0x10] = 0xC0.toByte()
        // Underlying entry ($3F00) should be both masked AND equal to what we wrote (modulo mask).
        assertThat(
            "backdrop mirror (\$3F10) -> \$3F00 holds masked write",
            palette[0x00].toUnsignedInt(),
            equalTo(0x00), // 0xC0 & 0x3F = 0x00
        )
        // Read back via the mirror — should also see the masked value.
        assertThat(
            "read via mirror sees same masked value",
            palette[0x10].toUnsignedInt(),
            equalTo(0x00),
        )
    }

    @Test
    fun `un-masked high bits never appear in storage`() {
        val palette = PaletteRam()
        // Sweep a handful of representative values with upper bits set.
        val samples = listOf(0x40, 0x80, 0xC0, 0x7F, 0xFF, 0xBD)
        for (sample in samples) {
            palette[0x01] = sample.toByte()
            assertThat(
                "write $sample stored as ${sample and 0x3F}",
                palette[0x01].toUnsignedInt(),
                equalTo(sample and 0x3F),
            )
        }
    }
}
