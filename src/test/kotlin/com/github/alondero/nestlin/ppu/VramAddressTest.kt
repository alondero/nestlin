package com.github.alondero.nestlin.ppu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class VramAddressTest {

    @Test
    fun `vram address computes relative offset for NT0 at 0,0`() {
        val addr = VramAddress()
        addr.fineYScroll = 0
        addr.horizontalNameTable = false
        addr.verticalNameTable = false
        addr.coarseYScroll = 0
        addr.coarseXScroll = 0

        // asAddress() returns relative offset (0x0000 for NT0 at 0,0)
        // Calling code does: 0x2000 | (asAddress() & 0x0FFF) = 0x2000
        val relative = addr.asAddress()
        assertThat(relative, equalTo(0x0000))
    }

    @Test
    fun `vram address computes relative offset for NT1 at 0,0`() {
        val addr = VramAddress()
        addr.fineYScroll = 0
        addr.horizontalNameTable = true  // Selects NT1 (bits 10-11)
        addr.verticalNameTable = false
        addr.coarseYScroll = 0
        addr.coarseXScroll = 0

        // asAddress() returns offset with nametable selector in bits 10-11
        // For NT1: 0x0400
        // Calling code does: 0x2000 | (0x0400 & 0x0FFF) = 0x2400
        val relative = addr.asAddress()
        assertThat(relative, equalTo(0x0400))
    }

    @Test
    fun `vram address with coarse Y and X offsets`() {
        val addr = VramAddress()
        addr.fineYScroll = 0
        addr.horizontalNameTable = false
        addr.verticalNameTable = false
        addr.coarseYScroll = 5  // Row 5
        addr.coarseXScroll = 10  // Column 10

        // Each row is 32 bytes offset = 0x20
        // So row 5 = 0x20 * 5 = 0xA0
        // Column 10 = 0x0A
        // Relative offset = 0x0A0A
        val relative = addr.asAddress()
        assertThat(relative, equalTo(0x00A0 + 0x000A))  // 0x00AA
    }

    @Test
    fun `vram address properly transitions between nametables horizontally`() {
        val addr = VramAddress()
        addr.fineYScroll = 0
        addr.coarseYScroll = 0

        // Start at NT0, column 31
        addr.horizontalNameTable = false
        addr.verticalNameTable = false
        addr.coarseXScroll = 31
        assertThat(addr.asAddress(), equalTo(0x201F))

        // Increment horizontally - should wrap to NT1
        addr.incrementHorizontalPosition()
        assertThat(addr.horizontalNameTable, equalTo(true))  // Now in NT1
        assertThat(addr.coarseXScroll, equalTo(0))
        assertThat(addr.asAddress(), equalTo(0x2400))  // NT1 at column 0
    }

    @Test
    fun `mirrored addresses - horizontal mirroring`() {
        // In horizontal mirroring:
        // NT0 (0x2000-0x23FF) mirrors to 0x2800-0x2BFF
        // NT1 (0x2400-0x27FF) mirrors to 0x2C00-0x2FFF

        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Direct write to 0x2000 should be readable from 0x2800
        memory[0x2000] = 0xAA.toByte()
        assertThat(memory[0x2800], equalTo(0xAA.toByte()))

        // Direct write to 0x2400 should be readable from 0x2C00
        memory[0x2400] = 0xBB.toByte()
        assertThat(memory[0x2C00], equalTo(0xBB.toByte()))
    }
}
