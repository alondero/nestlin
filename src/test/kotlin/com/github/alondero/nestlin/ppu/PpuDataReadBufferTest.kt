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
}
