package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.*

class PpuAddressedMemory {
    val controller = Control()    // $2000
    val mask = Mask()                // $2001
    val status = Status()                   // $2002
    var oamAddress: Byte = 0               // $2003
    var oamData: Byte = 0                  // $2004
    var scroll: Byte = 0                   // $2005
    var address: Byte = 0                  // $2006
    var data: Byte = 0                     // $2007

    private var writeToggle = false

    val ppuInternalMemory = PpuInternalMemory()
    val objectAttributeMemory = ObjectAttributeMemory()

    val vRamAddress = VramAddress() // v
    val tempVRamAddress = VramAddress() // t
    var fineXScroll = 0 // x

    var nmiOccurred = false
    var nmiOutput = false

    fun reset() {
        controller.reset()
        mask.reset()
        status.reset()
        oamAddress = 0
        oamData = 0
        scroll = 0
        address = 0
        data = 0

        writeToggle = false
    }

    operator fun get(addr: Int) = when (addr) {
            0 -> controller.register
            1 -> mask.register
            2 -> {
                writeToggle = false
                val value = status.register.letBit(7, nmiOccurred)
                status.clearVBlank()
                nmiOccurred = false
                value
            }
            3 -> oamAddress
            4 -> oamData
            5 -> scroll
            6 -> address
            else /*7*/ -> {
                vRamAddress += controller.vramAddressIncrement()
                data
            }

    }

    operator fun set(addr: Int, value: Byte) {
//        println("Setting PPU Addressed data ${addr.toHexString()}, with ${value.toHexString()}")
        when (addr) {
            0 -> {
                controller.register = value
                tempVRamAddress.updateNameTable(value.toUnsignedInt() and 0x03)
            }
            1 -> mask.register = value
            2 -> status.register = value
            3 -> oamAddress = value
            4 -> oamData = value
            5 -> {
                scroll = value
                if (writeToggle) {
                    tempVRamAddress.coarseYScroll = (value.toUnsignedInt() shr 3) and 0x1F
                    tempVRamAddress.fineYScroll = value.toUnsignedInt() and 0x07
                } else {
                    tempVRamAddress.coarseXScroll = (value.toUnsignedInt() shr 3) and 0x1F
                    fineXScroll = value.toUnsignedInt() and 0x07
                }
                writeToggle = !writeToggle
            }
            6 -> {
                address = value
                if (writeToggle) {
                    tempVRamAddress.setLowerByte(value)
                } else {
                    tempVRamAddress.setUpper7Bits(value)
                }
            }
            else /*7*/ -> {
                vRamAddress += controller.vramAddressIncrement()
                data = value.letBit(7, nmiOutput)
            }
        }
    }
}

class VramAddress {
    /** 15 bit register
    yyy NN YYYYY XXXXX
    ||| || ||||| +++++-- coarse X scroll
    ||| || +++++-------- coarse Y scroll
    ||| |+-------------- horizontal nametable select
    ||| +--------------- vertical nametable select
    +++----------------- fine Y scroll
     */

    var coarseXScroll = 0
    var coarseYScroll = 0 // 5 bits so maximum value is 31
    var horizontalNameTable = false
    var verticalNameTable = false
    var fineYScroll = 0 // 3 bits so maximum value is 7 - wraps to coarseY if overflows

    fun setUpper7Bits(bits: Byte) {
        fineYScroll = (bits.toUnsignedInt() shr 4) and 0x03
        horizontalNameTable = bits.isBitSet(2)
        verticalNameTable = bits.isBitSet(3)
        coarseYScroll = (coarseYScroll and 0x07) or ((bits.toUnsignedInt() and 0x03) shl 3)
    }

    fun setLowerByte(byte: Byte) {
        coarseXScroll = byte.toUnsignedInt() and 0x1F
        coarseYScroll = (coarseYScroll and 0x18) or ((byte.toUnsignedInt()) shr 5)
    }

    fun updateNameTable(nameTable: Int) {
        nameTable.toSignedByte().let {
            horizontalNameTable = it.isBitSet(0)
            verticalNameTable = it.isBitSet(1)
        }
    }

    private fun getNameTable() = (if (verticalNameTable) 2 else 0) + (if (horizontalNameTable) 1 else 0)

    fun incrementVerticalPosition() {
        fineYScroll++

        if (fineYScroll > 7) {
            coarseYScroll++
            fineYScroll = 0

            if (coarseYScroll > 29) {
                // Y Scroll now out of bounds
                if (coarseYScroll < 32) {
                    //  Hasn't overflowed therefore switch vertical nametable
                    verticalNameTable = !verticalNameTable
                }

                coarseYScroll = 0
            }
        }
    }

    fun incrementHorizontalPosition() {
        coarseXScroll++

        if (coarseXScroll > 31) {
            horizontalNameTable = !horizontalNameTable
            coarseXScroll = 0
        }
    }

    infix operator fun plusAssign(vramAddressIncrement: Int) {
        if (vramAddressIncrement == 32) {
            coarseYScroll++
        } else {
            coarseXScroll++
        }
    }

    fun asAddress() = (((((fineYScroll shl 2) or getNameTable()) shl 5) or coarseYScroll) shl 5) or coarseXScroll
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

class Status {
    var register: Byte = 0

    /**
    7  bit  0
    ---- ----
    VSO. ....
    |||| ||||
    |||+-++++- Least significant bits previously written into a PPU register
    |||        (due to register not being updated for this address)
    ||+------- Sprite overflow. The intent was for this flag to be set
    ||         whenever more than eight sprites appear on a scanline, but a
    ||         hardware bug causes the actual behavior to be more complicated
    ||         and generate false positives as well as false negatives; see
    ||         PPU sprite evaluation. This flag is set during sprite
    ||         evaluation and cleared at dot 1 (the second dot) of the
    ||         pre-render line.
    |+-------- Sprite 0 Hit.  Set when a nonzero pixel of sprite 0 overlaps
    |          a nonzero background pixel; cleared at dot 1 of the pre-render
    |          line.  Used for raster timing.
    +--------- Vertical blank has started (0: not in vblank; 1: in vblank).
    Set at dot 1 of line 241 (the line *after* the post-render
    line); cleared after reading $2002 and at dot 1 of the
    pre-render line.
     */

    fun spriteOverflow() = register.isBitSet(5)
    fun sprite0Hit() = register.isBitSet(6)
    fun vBlankStarted() = register.isBitSet(7)

    fun reset() { register = 0 }

    fun clearOverflow() { register = register.clearBit(5) }
    fun clearVBlank() { register = register.clearBit(7) }

    fun clearFlags() {reset()}
}

class PpuInternalMemory {

    private val patternTable0 = ByteArray(0x1000)
    private val patternTable1 = ByteArray(0x1000)
    private val nameTable0 = ByteArray(0x400)
    private val nameTable1 = ByteArray(0x400)
    private val nameTable2 = ByteArray(0x400)
    private val nameTable3 = ByteArray(0x400)
    private val paletteRam = PaletteRam()


//    private val backgroundNametables = Background()
//    private val spriteNametables = Sprites()

    operator fun get(addr: Int): Byte = when (addr) {
        in 0x0000..0x0999 -> patternTable0[addr % 0x1000]
        in 0x1000..0x1999 -> patternTable1[addr % 0x1000]
        in 0x2000..0x23FF -> nameTable0[addr % 0x400]
        in 0x2400..0x27FF -> nameTable1[addr % 0x400]
        in 0x2800..0x2BFF -> nameTable2[addr % 0x400]
        in 0x2C00..0x2FFF -> nameTable3[addr % 0x400]
        in 0x3000..0x3EFF -> this[addr - 0x1000] // Mirror of 0x2000 - 0x2EFF
        else /*in 0x3F00..0x3FFF*/ -> paletteRam[addr % 0x020]
    }
}

class PaletteRam {
    private val bgPalette = ByteArray(0x10)
    private val spritePalette = ByteArray(0x10)

    operator fun get(addr: Int): Byte = when (addr) {
        in 0x00..0x0F -> bgPalette[addr]
        else /*in 0x10..0x1F*/ -> spritePalette[addr]
    }
}