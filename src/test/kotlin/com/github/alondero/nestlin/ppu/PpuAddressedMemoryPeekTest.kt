package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toSignedByte
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [PpuAddressedMemory.peek] — side-effect-free PPU register reads for the
 * Memory Editor (issue #168). The real [PpuAddressedMemory.get] mutates state on
 * read ($2002 clears vblank + resets the write toggle, $2007 increments VRAM);
 * peek must return the same value while touching nothing.
 *
 * Issue #292: for write-only registers (`$2000`, `$2001`, `$2003`, `$2005`,
 * `$2006`) both [PpuAddressedMemory.get] and [PpuAddressedMemory.peek] now
 * report the current [PpuAddressedMemory.openBus] value rather than the
 * internal software mirror. These tests were updated to reflect the new
 * semantics: peek MUST match what a real read would return.
 */
class PpuAddressedMemoryPeekTest {

    @Test
    fun `peek of write-only registers returns the open-bus value, not the backing field`() {
        val ppu = PpuAddressedMemory()
        // The CPU's writes do land on the bus, so the value the user most
        // recently wrote is still observable — but only because the bus
        // remembers it. The internal mirrors (controller.register, mask.register,
        // oamAddress, scroll, address) are NOT exposed by reads of write-only
        // registers on real hardware (issue #292).
        ppu[0] = 0x80.toSignedByte() // PPUCTRL: bus = 0x80
        ppu[1] = 0x1E.toSignedByte() // PPUMASK: bus = 0x1E (overwrites prior bus)
        ppu.oamAddress = 0x12         // direct field write — does NOT touch the bus

        // peek must match what a real read would return. After the writes
        // above, the bus holds 0x1E, so every write-only register read sees
        // 0x1E — NOT its individually stored value.
        assertThat(ppu.peek(0), equalTo(0x1E.toSignedByte()))
        assertThat(ppu.peek(1), equalTo(0x1E.toSignedByte()))
        assertThat(ppu.peek(3), equalTo(0x1E.toSignedByte()))
    }

    @Test
    fun `peek of status does not clear the vblank flag or nmi latch`() {
        val ppu = PpuAddressedMemory()
        ppu.setVBlank() // sets PPUSTATUS bit 7 and nmiOccurred

        val peeked = ppu.peek(2)

        // The peeked value reflects vblank being set...
        assertThat(ppu.status.register.toInt() and 0x80, equalTo(0x80))
        assertThat(peeked.toInt() and 0x80, equalTo(0x80))
        // ...and crucially the flag is STILL set after peeking (a real read clears it).
        assertThat(ppu.status.vBlankStarted(), equalTo(true))
        assertThat(ppu.nmiOccurred, equalTo(true))
    }

    @Test
    fun `peek of status does not reset the write toggle`() {
        val ppu = PpuAddressedMemory()
        // First $2006 write flips the toggle on and stages the high byte in t.
        ppu[6] = 0x21.toSignedByte()

        ppu.peek(2) // a real $2002 read would reset the toggle to false

        // Second $2006 write: if the toggle is still ON, it sets the low byte and
        // copies t -> v, so v becomes a non-zero address. If peek had reset the
        // toggle, this would instead re-stage the high byte and v would stay 0.
        ppu[6] = 0x08.toSignedByte()
        assertThat(ppu.vRamAddress.asAddress(), equalTo(0x2108))
    }

    @Test
    fun `peek of data returns the read buffer without incrementing VRAM`() {
        val ppu = PpuAddressedMemory()
        // Point VRAM at $2400 with the increment-by-1 mode (default).
        ppu[6] = 0x24.toSignedByte()
        ppu[6] = 0x00.toSignedByte()
        val addrBefore = ppu.vRamAddress.asAddress()

        ppu.peek(7)
        ppu.peek(7)

        // A real $2007 read increments the VRAM address each time; peek must not.
        assertThat(ppu.vRamAddress.asAddress(), equalTo(addrBefore))
    }
}
