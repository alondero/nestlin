package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.cpu.NmiSource
import java.io.DataInput
import java.io.DataOutput

/**
 * The PPU's CPU-visible register window ($2000-$2007, mirrored every 8 bytes
 * through $3FFF). Also acts as the production [NmiSource] for the CPU↔PPU
 * interrupt seam (issue #190): `setVBlank`/`clearVBlankAtPreRender`/`$2002`
 * read mutate `nmiOccurred`, and the [NmiSource] adapter surfaces that flag
 * plus the PPUCTRL bit-7 gate to the `InterruptController` without exposing
 * the controller implementation detail to the CPU.
 */
class PpuAddressedMemory : NmiSource {
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
    var vBlankDebug = false
    var vBlankReadCounter = 0
    var lastVBlankReadFrame = -1

    // --- NmiSource adapter (issue #190) ---------------------------------------
    // The controller asks "is an NMI edge pending right now?" via this method
    // instead of reaching into the PPU's two-field condition (`nmiOccurred &&
    // controller.generateNmi()`). The combined condition lives here so the
    // CPU↔PPU interrupt seam has a single source of truth.

    /** True if an NMI edge is pending: PPU vblank set AND PPUCTRL bit 7 set. */
    override fun nmiPending(): Boolean = nmiOccurred && controller.generateNmi()

    /** Acknowledge the NMI edge — clears the latch (NESdev: edge-triggered). */
    override fun acknowledgeNmi() {
        nmiOccurred = false
    }

    // Debug: log every $2002 read with frame number
    var debugLogStatusReads = false
    var currentFrameForDebug = 0
    data class StatusRead(val frame: Int, val value: Int, val bit7: Boolean)
    val statusReadLog = mutableListOf<StatusRead>()

    fun setVBlank() {
        status.register = status.register.setBit(7)
        nmiOccurred = true
    }

    /**
     * Pre-render scanline (dot 1) housekeeping. The PPUSTATUS vblank bit and the
     * CPU-visible [nmiOccurred] latch are two views of the SAME hardware flag, which
     * NESdev specifies is "cleared after reading $2002 AND at dot 1 of the pre-render
     * line." Clearing only the status register here (and leaving [nmiOccurred] set)
     * lets a stale latch survive a frame in which the game never read $2002 — so the
     * next mid-frame "enable NMI" write fires a spurious immediate NMI. That is the
     * Mr. Gimmick (Europe) boot hang (issue #82): a mistimed NMI disables NMI for good.
     */
    fun clearVBlankAtPreRender() {
        status.clearFlags()
        nmiOccurred = false
    }

    fun enableDebugLogging() {
        debugLogStatusReads = true
        statusReadLog.clear()
    }

    fun disableDebugLogging() {
        debugLogStatusReads = false
    }

    fun setDebugFrame(frame: Int) {
        currentFrameForDebug = frame
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

    fun saveState(out: DataOutput) {
        out.writeByte(controller.register.toInt())
        out.writeByte(mask.register.toInt())
        out.writeByte(status.register.toInt())
        out.writeByte(oamAddress.toInt())
        out.writeByte(oamData.toInt())
        out.writeByte(scroll.toInt())
        out.writeByte(address.toInt())
        out.writeByte(data.toInt())
        out.writeBoolean(writeToggle)
        vRamAddress.saveState(out)
        tempVRamAddress.saveState(out)
        out.writeInt(fineXScroll)
        out.writeBoolean(nmiOccurred)
        out.writeBoolean(nmiOutput)
        ppuInternalMemory.saveState(out)
        objectAttributeMemory.saveState(out)
    }

    fun loadState(input: DataInput) {
        controller.register = input.readByte()
        mask.register = input.readByte()
        status.register = input.readByte()
        oamAddress = input.readByte()
        oamData = input.readByte()
        scroll = input.readByte()
        address = input.readByte()
        data = input.readByte()
        writeToggle = input.readBoolean()
        vRamAddress.loadState(input)
        tempVRamAddress.loadState(input)
        fineXScroll = input.readInt()
        nmiOccurred = input.readBoolean()
        nmiOutput = input.readBoolean()
        ppuInternalMemory.loadState(input)
        objectAttributeMemory.loadState(input)
    }

    /**
     * Side-effect-free read of a PPU register (issue #168, Memory Editor).
     *
     * Mirrors the value [get] would return but triggers NONE of the read side
     * effects the real PPU has:
     *  - `$2002` does NOT clear the vblank flag, the NMI latch, or the write toggle;
     *  - `$2007` returns the read buffer ([data]) WITHOUT incrementing the VRAM
     *    address (and without the palette fast-path, since that too would read
     *    through to backing VRAM).
     *
     * [register] is the low 3 bits of the CPU address (`$2000-$2007`, mirrored
     * every 8 bytes through `$3FFF`).
     */
    fun peek(register: Int): Byte = when (register and 7) {
        0 -> controller.register
        1 -> mask.register
        2 -> status.register
        3 -> oamAddress
        4 -> objectAttributeMemory[oamAddress.toUnsignedInt()]
        5 -> scroll
        6 -> address
        else /*7*/ -> data
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
                // Debug logging
                if (debugLogStatusReads) {
                    val bit7 = (value.toUnsignedInt() and 0x80) != 0
                    statusReadLog.add(StatusRead(currentFrameForDebug, value.toUnsignedInt(), bit7))
                }
                value
            }
            3 -> oamAddress
            4 -> objectAttributeMemory[oamAddress.toUnsignedInt()]
            5 -> scroll
            6 -> address
            else /*7*/ -> {
                val vramAddr = vRamAddress.asAddress() and 0x3FFF
                val result: Byte
                if (vramAddr >= 0x3F00) {
                    // Palette reads are not buffered — the entry returns immediately.
                    // The read buffer is still refilled, but with the NAMETABLE byte
                    // that sits "underneath" the palette (addr - $1000, the $2Fxx
                    // mirror), exactly as the real PPU's VRAM fetch does.
                    result = ppuInternalMemory[vramAddr]
                    data = ppuInternalMemory[vramAddr - 0x1000]
                } else {
                    result = data
                    data = ppuInternalMemory[vramAddr]
                }
                vRamAddress.increment(controller.vramAddressIncrement())
                result
            }
        }
    }

    operator fun set(addr: Int, value: Byte) {
//        println("Setting PPU Addressed data ${addr.toHexString()}, with ${value.toHexString()}")
        when (addr) {
            0 -> {
                val previousNmiEnabled = controller.generateNmi()
                controller.register = value
                tempVRamAddress.updateNameTable(value.toUnsignedInt() and 0x03)
                // Edge-triggered NMI: if bit-7 transitioned 0→1 while VBlank was already set,
                // re-trigger nmiOccurred so checkAndHandleNmi() fires on next CPU tick
                if (!previousNmiEnabled && controller.generateNmi() && status.vBlankStarted()) {
                    nmiOccurred = true
                }
            }
            1 -> {
                mask.register = value
            }
            2 -> status.register = value
            3 -> oamAddress = value
            4 -> writeOamData(value)
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
                    vRamAddress.setFrom(tempVRamAddress)
                } else {
                    tempVRamAddress.setUpper7Bits(value)
                }
                writeToggle = !writeToggle
            }
            else /*7*/ -> {
                // Write to VRAM at current address, then increment
                val writeAddr = vRamAddress.asAddress() and 0x3FFF
                ppuInternalMemory[writeAddr] = value

                vRamAddress.increment(controller.vramAddressIncrement())
            }
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

