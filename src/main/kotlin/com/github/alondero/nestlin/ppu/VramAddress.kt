package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

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
        // Hardware forces bit 14 of t (fine Y bit 2) to ZERO on the first $2006
        // write — only fine Y bits 0-1 arrive in the written byte. Preserving a
        // stale bit 2 from an earlier $2005 write offsets rendering by 4 rows.
        fineYScroll = (bits.toUnsignedInt() shr 4) and 0x03
        horizontalNameTable = bits.isBitSet(2)
        verticalNameTable = bits.isBitSet(3)
        coarseYScroll = (coarseYScroll and 0x07) or ((bits.toUnsignedInt() and 0x03) shl 3)
    }

    fun setLowerByte(byte: Byte) {
        coarseXScroll = byte.toUnsignedInt() and 0x1F
        coarseYScroll = (coarseYScroll and 0x18) or ((byte.toUnsignedInt()) shr 5)
    }

    fun setFrom(other: VramAddress) {
        coarseXScroll = other.coarseXScroll
        coarseYScroll = other.coarseYScroll
        horizontalNameTable = other.horizontalNameTable
        verticalNameTable = other.verticalNameTable
        fineYScroll = other.fineYScroll
    }

    fun updateNameTable(nameTable: Int) {
        val byte = nameTable.toSignedByte()
        horizontalNameTable = byte.isBitSet(0)
        verticalNameTable = byte.isBitSet(1)
    }

    private fun getNameTable() = (if (verticalNameTable) 2 else 0) + (if (horizontalNameTable) 1 else 0)

    fun getNameTableNum() = getNameTable()

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

    fun increment(amount: Int) {
        // Simple 14-bit increment for $2007 access
        var addr = asAddress()
        addr = (addr + amount) and 0x3FFF

        // Decompose back into fields
        coarseXScroll = addr and 0x1F
        coarseYScroll = (addr shr 5) and 0x1F
        horizontalNameTable = (addr shr 10).isBitSet(0)
        verticalNameTable = (addr shr 10).isBitSet(1)
        fineYScroll = (addr shr 12) and 0x07
    }

    infix operator fun plusAssign(vramAddressIncrement: Int) {
        increment(vramAddressIncrement)
    }

    fun asAddress() = (((((fineYScroll shl 2) or getNameTable()) shl 5) or coarseYScroll) shl 5) or coarseXScroll

    fun saveState(out: DataOutput) {
        out.writeInt(coarseXScroll)
        out.writeInt(coarseYScroll)
        out.writeBoolean(horizontalNameTable)
        out.writeBoolean(verticalNameTable)
        out.writeInt(fineYScroll)
    }

    fun loadState(input: DataInput) {
        coarseXScroll = input.readInt()
        coarseYScroll = input.readInt()
        horizontalNameTable = input.readBoolean()
        verticalNameTable = input.readBoolean()
        fineYScroll = input.readInt()
    }
}
