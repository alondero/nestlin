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

class Ppu(var memory: Memory) {

    private var cycle = 0
    private var scanline = 0

    private fun reverseBits(value: Int): Int {
        var result = 0
        var v = value
        for (i in 0..7) {
            result = (result shl 1) or (v and 1)
            v = v shr 1
        }
        return result
    }

    // Pattern table shift registers (16-bit, hold 2 tiles worth of bitmap data)
    // Bit 15 = current pixel being rendered, bits shift left each cycle
    private var patternShiftLow: Int = 0
    private var patternShiftHigh: Int = 0

    // Palette attribute shift registers (8-bit, hold palette for next 8 pixels)
    // Each bit corresponds to one bit of the 2-bit palette index
    private var paletteShiftLow: Byte = 0
    private var paletteShiftHigh: Byte = 0

    // Latches hold fetched data until time to load into shift registers (every 8 cycles)
    private var patternLatchLow: Byte = 0
    private var patternLatchHigh: Byte = 0
    private var paletteLatch: Int = 0

    // Active sprites for current scanline (max 8)
    private val activeSpriteBuffer = mutableListOf<ActiveSprite>()

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
            // Fetch sprites for this scanline at cycle 0
            if (cycle == 0) {
                fetchActiveSpriteDataForScanline(scanline)
            }

            //  Fetch tile data
            when (cycle) {// Idle
                in 1..256 -> fetchData()
                in 257..320 -> fetchSpriteTile()
                in 321..336 -> fetchData() // Ready for next scanline
            }
            checkAndSetVerticalAndHorizontalData()

        }

        if (cycle in 241..260) {
            vBlank = true // Should be set at the second 'tick'?
            memory.ppuAddressedMemory.nmiOccurred = true
        }

        if (scanline == PRE_RENDER_SCANLINE && cycle == 1) {memory.ppuAddressedMemory.status.clearFlags()}

        // Load shift registers every 8 cycles (at cycles 9, 17, 25, ..., 249, 257, 329, 337)
        if (cycle % 8 == 1 && ((cycle >= 1 && cycle <= 256) || (cycle >= 321 && cycle <= 336))) {
            // Load the upper 8 bits of pattern shift registers with fetched tile data
            patternShiftLow = (patternShiftLow and 0xFF) or (patternLatchLow.toUnsignedInt() shl 8)
            patternShiftHigh = (patternShiftHigh and 0xFF) or (patternLatchHigh.toUnsignedInt() shl 8)

            // Reload palette attribute shift registers
            // Each bit represents whether that pixel uses this palette bit
            paletteShiftLow = if ((paletteLatch and 0x01) != 0) 0xFF.toByte() else 0x00
            paletteShiftHigh = if ((paletteLatch and 0x02) != 0) 0xFF.toByte() else 0x00
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

    private fun rendering() = with (memory.ppuAddressedMemory.mask) { showBackground() && showSprites() }

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

    /**
     * Fetch active sprites for the current scanline.
     * Checks all 64 sprites in OAM, adds those on current scanline to active buffer.
     */
    private fun fetchActiveSpriteDataForScanline(scanline: Int) {
        activeSpriteBuffer.clear()

        // Get access to PPU's ObjectAttributeMemory
        val oam = memory.ppuAddressedMemory.objectAttributeMemory

        // Check all 64 sprites in OAM
        for (i in 0 until 64) {
            if (activeSpriteBuffer.size >= 8) break  // Max 8 sprites per scanline

            val spriteData = oam.getSprite(i)
            val spriteY = spriteData.y
            val spriteHeight = 8  // Minimal path: 8×8 only

            // Check if sprite is visible on current scanline
            // In the NES, Y position 0 is off-screen (renders at scanline 1)
            if (scanline > spriteY && scanline <= (spriteY + spriteHeight)) {
                val tileYOffset = scanline - spriteY - 1  // 0-7 within tile

                // Apply vertical flip if needed
                val tileY = if (spriteData.verticalFlip) {
                    (spriteHeight - 1) - tileYOffset
                } else {
                    tileYOffset
                }

                // Fetch sprite tile data from pattern table
                val patternBase = memory.ppuAddressedMemory.controller.spritePatternTableAddress()
                val tileIndex = spriteData.tileIndex.toUnsignedInt()
                val tileAddr = patternBase + (tileIndex * 16) + tileY

                val tileDataLow = memory.ppuAddressedMemory.ppuInternalMemory[tileAddr]
                val tileDataHigh = memory.ppuAddressedMemory.ppuInternalMemory[tileAddr + 8]

                // Load shift registers with tile data
                val activeSprite = ActiveSprite(
                    data = spriteData,
                    tileDataLow = tileDataLow,
                    tileDataHigh = tileDataHigh
                )

                // Initialize shift registers with tile data, apply horizontal flip
                if (spriteData.horizontalFlip) {
                    // If flipped, reverse bit order
                    activeSprite.shiftLow = reverseBits(tileDataLow.toUnsignedInt() and 0xFF)
                    activeSprite.shiftHigh = reverseBits(tileDataHigh.toUnsignedInt() and 0xFF)
                } else {
                    // Normal: no flip, just load the data
                    activeSprite.shiftLow = tileDataLow.toUnsignedInt()
                    activeSprite.shiftHigh = tileDataHigh.toUnsignedInt()
                }

                activeSpriteBuffer.add(activeSprite)
            }
        }
    }

    private fun fetchData() {
        when (cycle % 8) {
            0 -> with (memory.ppuAddressedMemory){
                vRamAddress.incrementHorizontalPosition()
                lastNametableByte = ppuInternalMemory[controller.baseNametableAddr() or (address.toUnsignedInt() and 0x0FFF)]
            }
            2 -> with(memory.ppuAddressedMemory){
                // Fetch Attribute Table Byte
                // Formula from nesdev wiki for attribute table addressing
                val v = vRamAddress.asAddress()
                val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()

                // Extract the 2-bit palette for this specific tile
                val shift = ((v shr 4) and 4) or (v and 2)
                paletteLatch = ((attributeByte shr shift) and 0x03) shl 2

                lastAttributeTableByte = attributeByte.toSignedByte()
            }
            4 -> with(memory.ppuAddressedMemory) {
                //  Fetch Low Tile Byte (bit 0 of each pixel)
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = lastNametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val address = patternTableBase + (tileIndex * 16) + fineY
                patternLatchLow = ppuInternalMemory[address]
            }
            6 -> with(memory.ppuAddressedMemory) {
                //  Fetch High Tile Byte (bit 1 of each pixel, 8 bytes after low byte)
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = lastNametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val address = patternTableBase + (tileIndex * 16) + fineY + 8
                patternLatchHigh = ppuInternalMemory[address]
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

        // Render pixel during visible scanline and cycles 1-256
        if (scanline < RESOLUTION_HEIGHT && cycle >= 1 && cycle <= 256) {
            val x = cycle - 1

            // Shift registers every cycle to advance to next pixel
            patternShiftLow = patternShiftLow shl 1
            patternShiftHigh = patternShiftHigh shl 1
            paletteShiftLow = (paletteShiftLow.toInt() shl 1).toByte()
            paletteShiftHigh = (paletteShiftHigh.toInt() shl 1).toByte()

            // Shift active sprite registers
            activeSpriteBuffer.forEach { sprite ->
                sprite.shiftLow = sprite.shiftLow shl 1
                sprite.shiftHigh = sprite.shiftHigh shl 1
            }

            // Extract pixel from shift registers with fine X scroll
            // Fine X scroll (0-7) determines which bit to extract
            val fineX = memory.ppuAddressedMemory.fineXScroll
            val shiftAmount = 15 - fineX
            val paletteShiftAmount = 7 - fineX

            val patternBit0 = (patternShiftLow shr shiftAmount) and 1
            val patternBit1 = (patternShiftHigh shr shiftAmount) and 1
            val pixelValue = (patternBit1 shl 1) or patternBit0

            // Extract palette bits with fine X scroll
            val paletteBit0 = (paletteShiftLow.toInt() shr paletteShiftAmount) and 1
            val paletteBit1 = (paletteShiftHigh.toInt() shr paletteShiftAmount) and 1
            val paletteIndex = (paletteBit1 shl 1) or paletteBit0

            // Look up color in palette RAM
            // If pixel value is 0 (transparent), use universal background color
            val paletteAddr = if (pixelValue == 0) {
                0x3F00 // Universal background color
            } else {
                0x3F00 + (paletteIndex shl 2) + pixelValue
            }

            val nesColorIndex = memory.ppuAddressedMemory.ppuInternalMemory[paletteAddr].toUnsignedInt()
            val rgbColor = NesPalette.getRgb(nesColorIndex)

            frame[scanline, x] = rgbColor
        }
    }

    fun addFrameListener(listener: FrameListener) {
        this.listener = listener
    }
}


class ObjectAttributeMemory {
    private val memory = ByteArray(0x100)

    operator fun get(addr: Int): Byte = memory[addr and 0xFF]
    operator fun set(addr: Int, value: Byte) {
        memory[addr and 0xFF] = value
    }

    /**
     * Get sprite data from OAM.
     * Each sprite occupies 4 bytes: Y, Tile, Attributes, X
     */
    fun getSprite(index: Int): SpriteData {
        val base = (index and 0x3F) * 4  // 64 sprites max, wrap at boundary
        return SpriteData(
            y = memory[base].toUnsignedInt(),
            tileIndex = memory[base + 1],
            attributes = memory[base + 2],
            x = memory[base + 3].toUnsignedInt()
        )
    }
}

/**
 * Sprite attribute byte format:
 * VPHP0000
 * V = vertical flip (1=flip)
 * P = priority (1=behind background)
 * H = horizontal flip (1=flip)
 * P = palette index (0-3)
 */
data class SpriteData(
    val y: Int,           // 0-255, sprite Y position
    val tileIndex: Byte,  // Tile index in pattern table
    val attributes: Byte, // VPHP0000
    val x: Int            // 0-255, sprite X position
) {
    val paletteIndex: Int get() = (attributes.toUnsignedInt() and 0x03)
    val priority: Int get() = (attributes.toUnsignedInt() shr 5) and 0x01  // 0=in front, 1=behind
    val horizontalFlip: Boolean get() = (attributes.toUnsignedInt() shr 6) and 0x01 != 0
    val verticalFlip: Boolean get() = (attributes.toUnsignedInt() shr 7) and 0x01 != 0
}

/**
 * Sprite with fetched tile data, ready to render on current scanline.
 */
data class ActiveSprite(
    val data: SpriteData,
    val tileDataLow: Byte,    // Pattern table low byte (bit 0 plane)
    val tileDataHigh: Byte,   // Pattern table high byte (bit 1 plane)
    var shiftLow: Int = 0,    // 8-bit shift register, bits 7-0 = pixels 7-0
    var shiftHigh: Int = 0    // 8-bit shift register
)

