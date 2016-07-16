package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

data class PpuRegisters (
        var controller: Control = Control(),
        var mask: Mask = Mask(),
        var status: Byte = 0,
        var oamAddress: Byte = 0,
        var oamData: Byte = 0,
        var scroll: Byte = 0,
        var address: Byte = 0,
        var data: Byte = 0
) {
    fun reset() {
        controller.reset()
        mask.reset()
        status = 0
        oamAddress = 0
        oamData = 0
        scroll = 0
        address = 0
        data = 0
    }

    operator fun get(addr: Int): Byte {
        when (addr) {
            0 -> return controller.register
            1 -> return mask.register
            2 -> return status
            3 -> return oamAddress
            4 -> return oamData
            5 -> return scroll
            6 -> return address
            else /*7*/ -> return data
        }
    }

    operator fun set(addr: Int, value: Byte) {
        when (addr) {
            0 -> controller.register = value
            1 -> mask.register = value
            2 -> status = value
            3 -> oamAddress = value
            4 -> oamData = value
            5 -> scroll = value
            6 -> address = value
            else /*7*/ -> data = value
        }
    }
}

class Control {
    var register: Byte = 0

    fun reset() {
        register = 0
    }

    fun baseNametableAddr() = 0x2000 + ((register.toUnsignedInt() and 0b00000011) * 0x400)
    fun vramAddressIncrement() = if (register.isBitSet(2)) 32 else 1
    fun spritePatternTableAddress() = if (register.isBitSet(3)) 0x1000 else 0
    fun backgroundPatternTableAddress() = if (register.isBitSet(4)) 0x1000 else 0
    fun spriteSize() = if (register.isBitSet(5)) SpriteSize.X_8_16 else SpriteSize.X_8_8
    fun generateNmi() = register.isBitSet(7)

    enum class SpriteSize {
        X_8_8, X_8_16
    }
}

class Mask {
    var register: Byte = 0

    fun reset() {
        register = 0
    }

    /**
    7  bit  0
    ---- ----
    BGRs bMmG
    |||| ||||
    |||| |||+- Greyscale (0: normal color, 1: produce a greyscale display)
    |||| ||+-- 1: Show background in leftmost 8 pixels of screen, 0: Hide
    |||| |+--- 1: Show sprites in leftmost 8 pixels of screen, 0: Hide
    |||| +---- 1: Show background
    |||+------ 1: Show sprites
    ||+------- Emphasize red*
    |+-------- Emphasize green*
    +--------- Emphasize blue*
     */

    fun greyscale() = register.isBitSet(0)

    fun backgroundInLeftmost8px() = register.isBitSet(1)
    fun spritesInLeftmost8px() = register.isBitSet(2)
    fun showBackground() = register.isBitSet(3)
    fun showSprites() = register.isBitSet(4)
    fun emphasizeRed() = register.isBitSet(5)
    fun emphasizeGreen() = register.isBitSet(6)
    fun emphasizeBlue() = register.isBitSet(7)
}
