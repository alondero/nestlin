package com.github.alondero.nestlin.ppu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * `$2007` read-buffer semantics for palette addresses (NESdev "PPUDATA"):
 * a read in $3F00-$3FFF returns the palette entry IMMEDIATELY, but the internal
 * read buffer is filled with the NAMETABLE byte that would sit at that address
 * with A13 dropped (addr - $1000, i.e. the $2F00-mirror "underneath" the palette).
 * Nestlin filled the buffer with the palette value instead, so the first
 * post-palette read of ordinary VRAM returned palette garbage.
 */
class PpuDataReadBufferTest {

    private fun setVramAddress(ppu: PpuAddressedMemory, addr: Int) {
        ppu[6] = ((addr shr 8) and 0x3F).toByte()
        ppu[6] = (addr and 0xFF).toByte()
    }

    @Test
    fun `palette read fills the buffer with the nametable byte underneath`() {
        val ppu = PpuAddressedMemory()
        // The nametable byte "under" $3F00 is at $2F00 (mirrored into the NT RAM).
        ppu.ppuInternalMemory[0x2F00] = 0xAB.toByte()
        ppu.ppuInternalMemory[0x3F00] = 0x21.toByte()

        setVramAddress(ppu, 0x3F00)
        val paletteValue = ppu[7]
        assertThat("palette returned immediately", paletteValue, equalTo(0x21.toByte()))

        // Point somewhere else and read: the FIRST read returns the buffer,
        // which must hold the nametable byte from under the palette — not $21.
        setVramAddress(ppu, 0x2000)
        val buffered = ppu[7]
        assertThat("buffer held nametable-under-palette", buffered, equalTo(0xAB.toByte()))
    }

    /**
     * Issue #293 (1/3): palette writes must mask to 6 bits so palette RAM never
     * holds bit 6 or bit 7 of an externally-written byte. Verified via the
     * CPU-visible path: write `$FF`, read `$2007` back; without masking we'd
     * see the upper two bits on the returned value (they should come from the
     * open bus, which starts at zero here).
     */
    @Test
    fun `palette write masks stored value to six bits`() {
        val ppu = PpuAddressedMemory()
        setVramAddress(ppu, 0x3F00)
        ppu[7] = 0xFF.toByte()

        // VRAM address is post-incremented by the write; reset to $3F00 to read.
        setVramAddress(ppu, 0x3F00)
        // Open bus is the seed byte we last wrote to *any* PPU register (here,
        // the $2006 address latches), not the palette value. After two $2006
        // writes the bus holds the LOW byte ($00), so palette should return
        // only its 6 stored bits — never the high two.
        assertThat("\$FF write masked to \$3F on read", ppu[7], equalTo(0x3F.toByte()))
    }

    /**
     * Issue #293 (2/3): palette reads return open bus in the top two bits.
     * The 6-bit stored entry occupies bits 0-5; bits 6-7 are whatever the PPU
     * data bus last held (any PPU write updates the bus — see issue #227).
     *
     * Each `setVramAddress` write clobbers the open bus with its LOW byte
     * (any $2006 write does), so the open-bus seed is the LAST write before
     * the read — and we use $2001 (PPUMASK) because mask writes do NOT touch
     * the VRAM address register.
     */
    @Test
    fun `palette read takes high two bits from open bus`() {
        listOf(0x00, 0x40, 0x80, 0xC0).forEach { openBusSeed ->
            val ppu = PpuAddressedMemory()
            // Seed palette entry $21 (post-increments vram to $3F01).
            setVramAddress(ppu, 0x3F00)
            ppu[7] = 0x21.toByte()
            // Reset vram to $3F00 — this also clobbers openBus to $00 (low byte),
            // which is fine because we're about to re-seed it.
            setVramAddress(ppu, 0x3F00)
            // Seed the PPU open bus via a $2001 (PPUMASK) write — last write before read.
            // Bit 0 = 0 in all of $00/$40/$80/$C0, so grayscale stays OFF.
            ppu[1] = openBusSeed.toByte()
            assertThat(
                "PPUMASK bit 0 = 0 (greyscale off) was not assumed",
                ppu.mask.greyscale(),
                equalTo(false),
            )
            val expected = (0x21 or openBusSeed).toByte()
            assertThat(
                "open bus $openBusSeed merged into bits 7-6",
                ppu[7],
                equalTo(expected),
            )
        }
    }

    /**
     * Issue #293 (3/3): when PPUMASK grayscale is set, palette reads mask the
     * low four palette bits to zero (mask $30). Bits 6-7 still come from open bus.
     * Acceptance: open bus $80 + entry $21 + grayscale ON → $A0.
     */
    @Test
    fun `palette read applies greyscale mask when enabled`() {
        val ppu = PpuAddressedMemory()
        setVramAddress(ppu, 0x3F00)
        ppu[7] = 0x21.toByte() // palette entry $21
        setVramAddress(ppu, 0x3F00) // reset vram, clobbers openBus to $00
        // Last write before read: $2001 = $81 (grayscale bit 0 = 1, openBus high bits = $80).
        ppu[1] = 0x81.toByte()
        assertThat(ppu.mask.greyscale(), equalTo(true))

        assertThat("grayscale forces low nibble to 0", ppu[7], equalTo(0xA0.toByte()))
    }

    /**
     * Issue #293 (2/3 corollary): palette reads LATCH the combined byte onto
     * the PPU open bus. A subsequent $2002 read's low 5 bits pick up the
     * palette return (masked to 5 bits). We verify via $2002 because there
     * is no direct CPU-visible open-bus register.
     */
    @Test
    fun `palette read latches combined byte on open bus`() {
        val ppu = PpuAddressedMemory()
        setVramAddress(ppu, 0x3F00)
        ppu[7] = 0x21.toByte()
        setVramAddress(ppu, 0x3F00)
        ppu[1] = 0x80.toByte() // open bus = $80, grayscale off

        val read = ppu[7]
        assertThat("palette read returns merged value", read, equalTo(0xA1.toByte()))

        // $2002 reads low 5 bits from the open bus. The latched combined byte
        // is $A1; bits 0-4 = $01 (palette entry $21 has bit 0 set, bits 1-4
        // clear). The upper bits come from the status register, which is 0
        // here (we never set vblank / sprite-0 / overflow). So $2002 = $01.
        val status = ppu[2]
        assertThat(
            "open bus now holds the palette-read result",
            status.toInt() and 0x1F,
            equalTo(0x01),
        )
    }

    /**
     * The shadow read buffer must still be filled with the nametable byte that
     * sits "under" the palette ($2Fxx mirror) even after the new open-bus /
     * grayscale handling is layered on top. Acceptance criterion 4 in #293.
     */
    @Test
    fun `palette read still fills shadow buffer from nametable under it`() {
        val ppu = PpuAddressedMemory()
        ppu.ppuInternalMemory[0x2F05] = 0xCD.toByte()
        ppu.ppuInternalMemory[0x3F05] = 0x21.toByte()

        setVramAddress(ppu, 0x3F05)
        ppu[1] = 0x80.toByte() // open bus = $80, grayscale off
        // Read returns $A1 (open bus = $80, palette entry $21, grayscale off).
        assertThat(ppu[7], equalTo(0xA1.toByte()))

        // Next read of any non-palette address returns the shadow buffer.
        setVramAddress(ppu, 0x2000)
        assertThat("shadow buffer preserved \$2F05 nametable byte", ppu[7], equalTo(0xCD.toByte()))
    }
}
