package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class PpuVblankTimingTest {

    @Test
    fun `vram address remains stable during vblank when rendering enabled`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.mask.register = 0b00001000

        while (!memory.ppuAddressedMemory.status.vBlankStarted()) {
            ppu.tick()
        }

        val vramAtVblankStart = memory.ppuAddressedMemory.vRamAddress.asAddress()
        repeat(200) { ppu.tick() }
        val vramAfterTicks = memory.ppuAddressedMemory.vRamAddress.asAddress()

        assertThat(vramAfterTicks, equalTo(vramAtVblankStart))
    }

    @Test
    fun `nmiOccurred latch clears at the pre-render scanline even without a 2002 read`() {
        // Regression for Mr. Gimmick (Europe) boot hang (issue #82). The CPU-visible
        // nmiOccurred latch and PPUSTATUS bit 7 are the SAME hardware flag, which is
        // "cleared after reading $2002 AND at dot 1 of the pre-render line" (NESdev).
        // If a game lets a vblank elapse without reading $2002, nmiOccurred must still
        // clear at pre-render; otherwise enabling NMI mid-frame fires a spurious
        // immediate NMI at the wrong point in the frame.
        val memory = Memory()
        val ppu = Ppu(memory)

        // NMI disabled (controller bit 7 = 0 by default), rendering off.
        // Advance to vblank start (scanline 241, dot 1): both flags get set.
        while (!memory.ppuAddressedMemory.status.vBlankStarted()) ppu.tick()
        assertThat(memory.ppuAddressedMemory.nmiOccurred, equalTo(true))

        // Without reading $2002, advance until the PPUSTATUS vblank bit clears (this
        // happens at the pre-render scanline, dot 1). The nmiOccurred latch must have
        // cleared at the same moment.
        while (memory.ppuAddressedMemory.status.vBlankStarted()) ppu.tick()

        assertThat(memory.ppuAddressedMemory.nmiOccurred, equalTo(false))
    }
}
