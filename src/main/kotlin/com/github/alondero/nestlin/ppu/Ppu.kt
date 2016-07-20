package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.ui.FrameListener
import java.util.*

const val RESOLUTION_WIDTH = 256
const val RESOLUTION_HEIGHT = 224
const val NTSC_SCANLINES = 262

const val IDLE_SCANLINE = 0
const val PRE_RENDER_SCANLINE = 261
const val POST_RENDER_SCANLINE = 240

class Ppu(
        var memory: Memory
) {
    private var cycle = 0
    private var scanline = 0

    // 2 16-Bit registers containing bitmap data for two tiles
    // Every 8 cycles, the bitmap data for the next tile is loaded into the upper 8 bits of this shift register.
    // Meanwhile, the pixel to render is fetched from one of the lower 8 bits.
    var youngTile = 0xFFFF.toSignedShort()
    var nextTile = 0xFFFF.toSignedShort()

    // 2 8-bit shift registers - These contain the palette attributes for the lower 8 pixels of the 16-bit shift register.
    // Every 8 cycles, the latch is loaded with the palette attribute for the next tile.
    val paletteRegisters = 0
    // These registers are fed by a latch which contains the palette attribute for the next tile.
    val paletteLatch = 0

    private var listener: FrameListener? = null
    private var vBlank = false
    private var frame = Frame()

    private val rand = Random()

    private var lastNametableByte: Byte = 0
    private var lastAttributeTableByte: Byte = 0

    fun tick() {
//       println("Rendering ($cycle, $scanline)")
        if (cycle == 341) {
            endLine()
            return
        }

        if (rendering()) {
            //  Fetch tile data
            when (cycle) {// Idle
                in 1..256 -> fetchData()
                in 257..320 -> fetchSpriteTile()
                in 321..336 -> fetchData() // Ready for next scanline
                341 -> endLine()
            }
            checkAndSetVerticalAndHorizontalData()

        }

        if (cycle in 241..260) {
            vBlank = true // Should be set at the second 'tick'?
            memory.ppuAddressedMemory.nmiOccurred = true
        }

        if (scanline == PRE_RENDER_SCANLINE && cycle == 1) {memory.ppuAddressedMemory.status.clearFlags()}

        if (cycle % 8 == 0) {
            //  Bitmap data for the next tile is loaded into the upper 8 bits
            val bitmap = 0xFF.toSignedByte()
        }

        //  Every cycle a bit is fetched from the 4 backgroundNametables shift registers in order to create a pixel on screen


//        with(spriteNametables) {
//            decrementCounters()
//            getActiveSprites().forEach {
//                // If the counter is zero, the sprite becomes "active", and the respective pair of shift registers for the sprite is shifted once every cycle.
//                // This output accompanies the data in the sprite's latch, to form a pixel.
//                it.shiftRegisters()
//                // The current pixel for each "active" sprite is checked (from highest to lowest priority), and the first non-transparent pixel moves on to a multiplexer,
//                // where it joins the BG pixel
//            }
//        }

        cycle++
    }

    private fun rendering() = memory.ppuAddressedMemory.mask.showBackground() && memory.ppuAddressedMemory.mask.showSprites()

    private fun checkAndSetVerticalAndHorizontalData() {
        when (cycle) {
            256 -> memory.ppuAddressedMemory.vRamAddress.incrementVerticalPosition()
            257 -> with(memory.ppuAddressedMemory) {
                vRamAddress.coarseXScroll = tempVRamAddress.coarseXScroll
                vRamAddress.horizontalNameTable = tempVRamAddress.horizontalNameTable
            }
        }

        if (scanline == PRE_RENDER_SCANLINE && cycle in 280..304) {
            with (memory.ppuAddressedMemory) {
                vRamAddress.coarseYScroll = tempVRamAddress.coarseYScroll
                vRamAddress.fineYScroll = tempVRamAddress.fineYScroll
                vRamAddress.verticalNameTable = tempVRamAddress.verticalNameTable
            }
        }
    }

    private fun endLine() {
        when (scanline) {
            NTSC_SCANLINES - 1 -> endFrame()
            else -> scanline++
        }
        cycle = 0
    }

    private fun endFrame() {
        listener?.frameUpdated(frame)

        //  What to do here?
        scanline = 0
        vBlank = false
    }

    private fun fetchSpriteTile() {
//        The tile data for the spriteNametables on the next scanline are fetched here. Again, each memory access takes 2 PPU cycles to complete, and 4 are performed for each of the 8 spriteNametables:
//
//        Garbage nametable byte
//        Garbage nametable byte
//        Tile bitmap low
//        Tile bitmap high (+8 bytes from tile bitmap low)
//        The garbage fetches occur so that the same circuitry that performs the BG tile fetches could be reused for the sprite tile fetches.
//
//        If there are less than 8 spriteNametables on the next scanline, then dummy fetches to tile $FF occur for the left-over spriteNametables, because of the dummy sprite data in the secondary OAM (see sprite evaluation). This data is then discarded, and the spriteNametables are loaded with a transparent bitmap instead.
//
//        In addition to this, the X positions and attributes for each sprite are loaded from the secondary OAM into their respective counters/latches. This happens during the second garbage nametable fetch, with the attribute byte loaded during the first tick and the X coordinate during the second.
    }

    private fun fetchData() {
        when (cycle % 8) {
            0 -> with (memory.ppuAddressedMemory){
                vRamAddress.incrementHorizontalPosition()
                lastNametableByte = ppuInternalMemory[controller.baseNametableAddr() or (address.toUnsignedInt() and 0x0FFF)]
            }
            2 -> with(memory.ppuAddressedMemory){
                /**
                 * v := ppu.v
                address := 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07)
                shift := ((v >> 4) & 4) | (v & 2)
                ppu.attributeTableByte = ((ppu.Read(address) >> shift) & 3) << 2
                 */

                //  Don't understand this logic... had to inspect other source code to work out what to do...
                val address = controller.baseNametableAddr() or (vRamAddress.coarseXScroll and 0b11100) or (vRamAddress.coarseYScroll and 0b11100)
                lastAttributeTableByte = ppuInternalMemory[controller.baseNametableAddr() + 0x3C0]
            }
            4 -> with(memory.ppuAddressedMemory) {
                //  Fetch Low Tile Byte
            }
            6 -> with(memory.ppuAddressedMemory) {
                //  Fetch High Tile Byte
            }
        }

        //  The data for each tile is fetched during this phase. Each memory access takes 2 PPU cycles to complete, and 4 must be performed per tile:
//        Nametable byte
//        Attribute table byte
//        Tile bitmap low
//        Tile bitmap high (+8 bytes from tile bitmap low)
//        The data fetched from these accesses is placed into internal latches, and then fed to the appropriate shift registers when it's time to do so (every 8 cycles). Because the PPU can only fetch an attribute byte every 8 cycles, each sequential string of 8 pixels is forced to have the same palette attribute.
//
//        Sprite zero hits act as if the image starts at cycle 2 (which is the same cycle that the shifters shift for the first time), so the sprite zero flag will be raised at this point at the earliest. Actual pixel output is delayed further due to internal render pipelining, and the first pixel is output during cycle 4.
//
//        The shifters are reloaded during ticks 9, 17, 25, ..., 257.
//
//        Note: At the beginning of each scanline, the data for the first two tiles is already loaded into the shift registers (and ready to be rendered), so the first tile that gets fetched is Tile 3.
//
//        While all of this is going on, sprite evaluation for the next scanline is taking place as a seperate process, independent to what's happening here.

        if (scanline < RESOLUTION_HEIGHT && cycle - 1 < RESOLUTION_WIDTH) {
            frame[scanline, cycle - 1] = rand.nextInt(0xFFFFFF)
        }
    }

    fun addFrameListener(listener: FrameListener) {
        this.listener = listener
    }
}


class ObjectAttributeMemory {
    private val memory = ByteArray(0x100)
}


class Sprites {
    //  Holds 8 sprites for the current scanline
    private val sprites = Array(size = 8, init = { Sprite() })

    fun decrementCounters() = sprites.forEach { it.counter.dec() }
    fun getActiveSprites() = sprites.filter { it.isActive() }
}

data class Sprite(
        val objectAttributeMemory: Byte = 0,
        var bitmapDataA: Byte = 0,
        var bitmapDataB: Byte = 0,
        val latch: Byte = 0,
        val counter: Int = 0
) {
    fun isActive() = counter <= 0

    fun shiftRegisters() {
        bitmapDataA = bitmapDataA.shiftRight()
        bitmapDataB = bitmapDataB.shiftRight()
    }
}

