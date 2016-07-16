package com.github.alondero.nestlin.ppu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class PpuRegistersTest {

    val registers: PpuRegisters

    init {
        registers = PpuRegisters()
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
        registers[0] = 0b00000010
        assertThat(registers.controller.baseNametableAddr(), equalTo(0x2800))
    }

    @Test
    fun vramAddressIncrement() {
        registers[0] = 0b00000100
        assertThat(registers.controller.vramAddressIncrement(), equalTo(32))
    }

    @Test
    fun spritePatternTableAddress() {
        registers[0] = 0b00001000
        assertThat(registers.controller.spritePatternTableAddress(), equalTo(0x1000))
    }

    @Test
    fun backgroundPatternTableAddress() {
        registers[0] = 0b00010000
        assertThat(registers.controller.backgroundPatternTableAddress(), equalTo(0x1000))
    }

    @Test
    fun spriteSize() {
        registers[0] = 0b00100000
        assertThat(registers.controller.spriteSize(), equalTo(Control.SpriteSize.X_8_16))
    }


}

