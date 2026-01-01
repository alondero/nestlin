package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toSignedByte
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class PpuAddressedMemoryTest {

    val addressedMemory: PpuAddressedMemory

    init {
        addressedMemory = PpuAddressedMemory()
    }

    /**
     * Control register
     *
    7  bit  0
    ---- ----
    VPHB SINN
    |||| ||||
    |||| ||++- Base nametable address
    |||| ||    (0 = $2000; 1 = $2400; 2 = $2800; 3 = $2C00)
    |||| |+--- VRAM address increment per CPU read/write of PPUDATA
    |||| |     (0: add 1, going across; 1: add 32, going down)
    |||| +---- Sprite pattern table address for 8x8 sprites
    ||||       (0: $0000; 1: $1000; ignored in 8x16 mode)
    |||+------ Background pattern table address (0: $0000; 1: $1000)
    ||+------- Sprite size (0: 8x8; 1: 8x16)
    |+-------- PPU master/slave select
    |          (0: read backdrop from EXT pins; 1: output color on EXT pins)
    +--------- Generate an NMI at the start of the
    vertical blanking interval (0: off; 1: on)
     */

    @Test
    fun baseNametableAddress() {
        addressedMemory[0] = 0b00000010
        assertThat(addressedMemory.controller.baseNametableAddr(), equalTo(0x2800))
    }

    @Test
    fun vramAddressIncrement() {
        addressedMemory[0] = 0b00000100
        assertThat(addressedMemory.controller.vramAddressIncrement(), equalTo(32))
    }

    @Test
    fun spritePatternTableAddress() {
        addressedMemory[0] = 0b00001000
        assertThat(addressedMemory.controller.spritePatternTableAddress(), equalTo(0x1000))
    }

    @Test
    fun backgroundPatternTableAddress() {
        addressedMemory[0] = 0b00010000
        assertThat(addressedMemory.controller.backgroundPatternTableAddress(), equalTo(0x1000))
    }

    @Test
    fun spriteSize() {
        addressedMemory[0] = 0b00100000
        assertThat(addressedMemory.controller.spriteSize(), equalTo(Control.SpriteSize.X_8_16))
    }

    // 2000 tests
    @Test
    fun setControlCorrectlySetsNametableAddress() {
        addressedMemory[0] = 0b00101010

        // t: ...BA.. ........ = d: ......BA
        assertThat(addressedMemory.tempVRamAddress.verticalNameTable, equalTo(true))
        assertThat(addressedMemory.tempVRamAddress.horizontalNameTable, equalTo(false))
    }

    // 2005 tests
    @Test
    fun setScrollCorrectlySetsCoarseAndFineYScrollWhenToggleOn() {
        setToggleOn()
        addressedMemory[5] = 0xF3.toSignedByte()

        // t: .CBA..HG FED..... = d: HGFEDCBA
        // w:                   = 0
        assertThat(addressedMemory.tempVRamAddress.coarseYScroll, equalTo(0x1E))
        assertThat(addressedMemory.tempVRamAddress.fineYScroll, equalTo(3))
    }

    @Test
    fun setScrollCorrectlySetsCoarseXScrollWhenToggleOff() {
        //  Address toggle false
        addressedMemory[5] = 0xFF.toSignedByte()

        // t: ........ ...HGFED = d: HGFED...
        // x:               CBA = d: .....CBA
        // w:                   = 1
        assertThat(addressedMemory.tempVRamAddress.coarseXScroll, equalTo(0x1F))
        assertThat(addressedMemory.fineXScroll, equalTo(0b111))
    }

    // 2006 tests
    @Test
    fun setUpper7BitsOn2006CallWhenToggleOff() {
        addressedMemory[6] = 0xFF.toSignedByte()

        // t: .FEDCBA ........ = d: ..FEDCBA
        // t: X...... ........ = 0
        // w:                  = 1
        with(addressedMemory.tempVRamAddress) {
            assertThat(fineYScroll, equalTo(3))
            assertThat(verticalNameTable, equalTo(true))
            assertThat(horizontalNameTable, equalTo(true))
            assertThat(coarseYScroll, equalTo(0x18)) // Upper two bits of coarseYScroll
        }
    }

    @Test
    fun `setUpper7Bits preserves fine Y high bit`() {
        addressedMemory.tempVRamAddress.fineYScroll = 0b100

        // High byte write should update fineY low bits but preserve bit 2.
        addressedMemory[6] = 0b00110000.toSignedByte()

        assertThat(addressedMemory.tempVRamAddress.fineYScroll, equalTo(0b111))
    }

    @Test
    fun setLowerByteOn2006CallWhenToggleOn() {
        setToggleOn()
        addressedMemory[6] = 0xFF.toSignedByte()

        // t: ....... HGFEDCBA = d: HGFEDCBA
        // v                   = t
        // w:                  = 0
        with(addressedMemory.tempVRamAddress) {
            assertThat(coarseXScroll, equalTo(0x1F))
            assertThat(coarseYScroll, equalTo(0x07)) // Lower three bits of coarseYScroll
        }
    }

    private fun setToggleOn() {
        //  Set toggle on by writing first
        addressedMemory[5] = 0
    }
}
