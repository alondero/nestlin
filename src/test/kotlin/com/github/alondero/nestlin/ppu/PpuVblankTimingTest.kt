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
}
