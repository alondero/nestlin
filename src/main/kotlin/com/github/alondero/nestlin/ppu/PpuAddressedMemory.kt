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

    // Diagnostic logging support
    private var diagnosticFile: java.io.PrintWriter? = null
    var diagnosticLogging = false
    var diagnosticStartFrame = 0
    var diagnosticEndFrame = 0
    var currentFrameCount = 0

    fun setVBlank() {
        status.register = status.register.setBit(7)
        nmiOccurred = true
    }

    fun writeOamData(value: Byte) {
        objectAttributeMemory[oamAddress.toUnsignedInt()] = value
        oamAddress = (oamAddress + 1).toSignedByte()
    }

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

    operator fun get(addr: Int): Byte {
        return when (addr) {
            0 -> controller.register
            1 -> mask.register
            2 -> {
                writeToggle = false
                val value = status.register
                status.clearVBlank()
                // Reading $2002 also clears the NMI flag? 
                // NESdev: "Reading $2002... will also acknowledge the interrupt"
                nmiOccurred = false 
                value
            }
            3 -> oamAddress
            4 -> objectAttributeMemory[oamAddress.toUnsignedInt()]
            5 -> scroll
            6 -> address
            else /*7*/ -> {
                val addr = vRamAddress.asAddress() and 0x3FFF
                val result = data
                data = ppuInternalMemory[addr]
                vRamAddress.increment(controller.vramAddressIncrement())

                // Palette reads are not buffered, they return immediately.
                // However, they still update the buffer with mirrored nametable data (handled above).
                if (addr >= 0x3F00) {
                    return data
                }
                result
            }
        }
    }

    fun setDiagnosticFile(file: java.io.PrintWriter, startFrame: Int, endFrame: Int) {
        diagnosticFile = file
        diagnosticStartFrame = startFrame
        diagnosticEndFrame = endFrame
        diagnosticLogging = true
    }

    private fun logDiagnostic(msg: String) {
        if (diagnosticLogging && currentFrameCount in diagnosticStartFrame until diagnosticEndFrame && diagnosticFile != null) {
            diagnosticFile!!.println("[VRAM-TRACE] Frame $currentFrameCount: $msg")
            diagnosticFile!!.flush()
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
            4 -> writeOamData(value)
            5 -> {
                scroll = value
                if (writeToggle) {
                    tempVRamAddress.coarseYScroll = (value.toUnsignedInt() shr 3) and 0x1F
                    tempVRamAddress.fineYScroll = value.toUnsignedInt() and 0x07
                    logDiagnostic("SCROLL_Y write: ${value.toHexString()}, coarseY=${tempVRamAddress.coarseYScroll}, fineY=${tempVRamAddress.fineYScroll}")
                } else {
                    tempVRamAddress.coarseXScroll = (value.toUnsignedInt() shr 3) and 0x1F
                    fineXScroll = value.toUnsignedInt() and 0x07
                    logDiagnostic("SCROLL_X write: ${value.toHexString()}, coarseX=${tempVRamAddress.coarseXScroll}, fineX=$fineXScroll")
                }
                writeToggle = !writeToggle
            }
            6 -> {
                address = value
                if (writeToggle) {
                    tempVRamAddress.setLowerByte(value)
                    vRamAddress.setFrom(tempVRamAddress)
                    logDiagnostic("VRAM_ADDR low byte: ${value.toHexString()}, vRamAddr after=${tempVRamAddress.asAddress().toString(16)}, NT=${tempVRamAddress.getNameTableNum()}")
                } else {
                    tempVRamAddress.setUpper7Bits(value)
                    logDiagnostic("VRAM_ADDR high byte: ${value.toHexString()}, vRamAddr after=${tempVRamAddress.asAddress().toString(16)}, NT=${tempVRamAddress.getNameTableNum()}")
                }
                writeToggle = !writeToggle
            }
            else /*7*/ -> {
                // Write to VRAM at current address, then increment
                val writeAddr = vRamAddress.asAddress() and 0x3FFF
                logDiagnostic("VRAM_DATA write: addr=${writeAddr.toString(16)}, data=${value.toHexString()}, NT=${vRamAddress.getNameTableNum()}, coarseX=${vRamAddress.coarseXScroll}, coarseY=${vRamAddress.coarseYScroll}")
                ppuInternalMemory[writeAddr] = value

                vRamAddress.increment(controller.vramAddressIncrement())
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

    fun setFrom(other: VramAddress) {
        coarseXScroll = other.coarseXScroll
        coarseYScroll = other.coarseYScroll
        horizontalNameTable = other.horizontalNameTable
        verticalNameTable = other.verticalNameTable
        fineYScroll = other.fineYScroll
    }

    fun updateNameTable(nameTable: Int) {
        nameTable.toSignedByte().let {
            horizontalNameTable = it.isBitSet(0)
            verticalNameTable = it.isBitSet(1)
        }
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
    private val paletteRam = PaletteRam()

    var mirroring = Mirroring.HORIZONTAL

    enum class Mirroring {
        HORIZONTAL, VERTICAL
    }

    private fun mapNametableAddress(addr: Int): Pair<ByteArray, Int> {
        val normalizedAddr = (addr - 0x2000) % 0x1000
        val tableIndex = when (mirroring) {
            Mirroring.HORIZONTAL -> {
                // Horizontal mirroring: $2000 mirrors $2400 (NT0), $2800 mirrors $2C00 (NT1)
                // This means CIRAM A10 = PPU A11
                if (normalizedAddr < 0x800) 0 else 1
            }
            Mirroring.VERTICAL -> {
                // Vertical mirroring: $2000 mirrors $2800 (NT0), $2400 mirrors $2C00 (NT1)
                // This means CIRAM A10 = PPU A10
                (normalizedAddr / 0x400) % 2
            }
        }
        val table = if (tableIndex == 0) nameTable0 else nameTable1
        return Pair(table, addr % 0x400)
    }

    operator fun get(addr: Int): Byte = when (addr) {
        in 0x0000..0x0FFF -> patternTable0[addr]
        in 0x1000..0x1FFF -> patternTable1[addr - 0x1000]
        in 0x2000..0x2FFF -> {
            val (table, offset) = mapNametableAddress(addr)
            table[offset]
        }
        in 0x3000..0x3EFF -> this[addr - 0x1000] // Mirror of 0x2000 - 0x2EFF
        else /*in 0x3F00..0x3FFF*/ -> paletteRam[addr % 0x020]
    }

    operator fun set(addr: Int, value: Byte) {
        when (addr) {
            // Pattern tables (0x0000-0x1FFF) are read-only for NROM cartridges
            // Writes are silently ignored (games shouldn't write here, but some buggy code might try)
            in 0x0000..0x0FFF -> {} // Ignore writes to pattern table 0
            in 0x1000..0x1FFF -> {} // Ignore writes to pattern table 1
            in 0x2000..0x2FFF -> {
                val (table, offset) = mapNametableAddress(addr)
                table[offset] = value
            }
            in 0x3000..0x3EFF -> this[addr - 0x1000] = value // Mirror of 0x2000 - 0x2EFF
            else /*in 0x3F00..0x3FFF*/ -> paletteRam[addr % 0x020] = value
        }
    }

    /**
     * Load CHR ROM data into pattern tables.
     * CHR ROM contains tile graphics data.
     * $0000-$0FFF: Pattern table 0
     * $1000-$1FFF: Pattern table 1
     *
     * For cartridges with small CHR ROMs (8KB or less), the CHR ROM is mirrored
     * across both pattern tables (both tables see the same data).
     * For cartridges with 16KB+ CHR ROM, pattern table 0 and 1 are separate.
     */
    fun loadChrRom(chrRom: ByteArray) {
        if (chrRom.isEmpty()) return

        // Load pattern table 0 ($0000-$0FFF)
        val table0Size = minOf(0x1000, chrRom.size)
        chrRom.copyInto(
            destination = patternTable0,
            destinationOffset = 0,
            startIndex = 0,
            endIndex = table0Size
        )

        // For pattern table 1 ($1000-$1FFF):
        // - Mapper 0 (NROM): CHR ROM is fixed at boot, never swappable
        // - 4KB CHR: mirror to both tables (duplicate data)
        // - 8KB CHR: ENTIRE 8KB ROM mirrors to both PT0 and PT1 (NROM behavior)
        //           Load the same first 4KB to pattern table 1
        //           (CTRL bit 4 doesn't swap banks; both tables always see same data)
        // - 16KB+: split across two pattern tables

        if (chrRom.size < 0x2000) {
            // 4KB, 8KB or less: mirror pattern table 0 data to pattern table 1
            // The entire CHR ROM repeats across both pattern tables
            patternTable0.copyInto(patternTable1)
        } else {
            // 16KB+: load second half of CHR ROM to pattern table 1
            val table1Size = minOf(0x1000, chrRom.size - 0x1000)
            chrRom.copyInto(
                destination = patternTable1,
                destinationOffset = 0,
                startIndex = 0x1000,
                endIndex = 0x1000 + table1Size
            )
        }
    }
}

class PaletteRam {
    private val memory = ByteArray(0x20)

    operator fun get(addr: Int): Byte {
        val index = addr and 0x1F
        // Mirror $3F10/$3F14/$3F18/$3F1C to $3F00/$3F04/$3F08/$3F0C
        val mirroredIndex = if (index and 0x13 == 0x10) index and 0x0F else index
        return memory[mirroredIndex]
    }

    operator fun set(addr: Int, value: Byte) {
        val index = addr and 0x1F
        // Mirror $3F10/$3F14/$3F18/$3F1C to $3F00/$3F04/$3F08/$3F0C
        val mirroredIndex = if (index and 0x13 == 0x10) index and 0x0F else index
        memory[mirroredIndex] = value
    }
}