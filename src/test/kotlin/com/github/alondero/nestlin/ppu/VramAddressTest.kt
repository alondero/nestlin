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
        // asAddress() returns 15-bit relative address (0x001F for column 31)
        assertThat(addr.asAddress(), equalTo(0x001F))

        // Increment horizontally - should wrap to NT1
        addr.incrementHorizontalPosition()
        assertThat(addr.horizontalNameTable, equalTo(true))  // Now in NT1
        assertThat(addr.coarseXScroll, equalTo(0))
        // NT1 with column 0: bit 10 = 1 (nametable selector), so 0x0400
        assertThat(addr.asAddress(), equalTo(0x0400))  // NT1 at column 0
    }

    @Test
    fun `vram address setUpper7Bits and setLowerByte properly distribute bits`() {
        val addr = VramAddress()
        
        // $2006 first write: 0 y y N N Y Y Y
        // We'll set: bits 5-0 (0x3F). Bit 6 is ignored.
        // fineY high 2 bits=3 (11), NT=3 (11), coarseY high bits=3 (11)
        // Bit 5: 1 (fineY bit 1)
        // Bit 4: 1 (fineY bit 0)
        // Bit 3: 1 (NT bit 1)
        // Bit 2: 1 (NT bit 0)
        // Bit 1: 1 (coarseY bit 4)
        // Bit 0: 1 (coarseY bit 3)
        // Input byte: 01111111 = 0x7F. Bit 6 (0x40) should be ignored.
        addr.setUpper7Bits(0x7F.toByte())
        
        assertThat(addr.fineYScroll, equalTo(3))
        assertThat(addr.getNameTableNum(), equalTo(3))
        assertThat(addr.coarseYScroll, equalTo(0x18)) // bits 4,3 set = 24
        
        // $2006 second write: Y Y Y X X X X X
        // We'll set: coarseY low bits=7 (111), coarseX=31 (11111)
        // Input byte: 11111111 = 0xFF
        addr.setLowerByte(0xFF.toByte())
        
        assertThat(addr.coarseXScroll, equalTo(31))
        assertThat(addr.coarseYScroll, equalTo(31)) // all 5 bits set
    }
}
