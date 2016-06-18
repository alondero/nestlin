package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.shiftRight
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort

const val RESOLUTION_WIDTH = 256
const val RESOLUTION_HEIGHT = 224
const val NTSC_SCANLINES = 262

const val IDLE_SCANLINE = 0
const val POST_RENDER_SCANLINE = 240

class Ppu {
    var cycle = 0
    var scanline = 0
    val background = Background()
    val sprites = SpriteRegister()
    var vBlank = false

    fun tick() {
//        println("Rendering ($cycle, $scanline)")
        when (cycle++) {
            IDLE_SCANLINE,POST_RENDER_SCANLINE -> return // Idle
            in 1..256 -> fetchData()
            in 257..320 -> fetchSpriteTile()
            in 321..336 -> fetchNextScanLineTiles()
            in 337..340 -> return // Does irrelevant stuff
            341 -> endLine()
        }

        if (cycle in 241..260) {
            vBlank = true // Should be set at the second 'tick'?
            // VBlank NMI also occurs?
        }


        if (cycle % 8 == 0) {
            //  Bitmap data for the next tile is loaded into the upper 8 bits
            val bitmap = 0xFF.toSignedByte()

            background.nextTile()
        }

        //  Every cycle a bit is fetched from the 4 background shift registers in order to create a pixel on screen
        background.fetch()

        sprites.apply {
            decrementCounters()
            getActiveSprites().forEach {
                // If the counter is zero, the sprite becomes "active", and the respective pair of shift registers for the sprite is shifted once every cycle.
                // This output accompanies the data in the sprite's latch, to form a pixel.
                it.shiftRegisters()
                // The current pixel for each "active" sprite is checked (from highest to lowest priority), and the first non-transparent pixel moves on to a multiplexer,
                // where it joins the BG pixel
            }
        }
    }

    private fun endLine() {
        when (scanline) {
            NTSC_SCANLINES-1 -> endFrame()
            else -> scanline++
        }
        cycle = 0
    }

    private fun endFrame() {
        //  What to do here?
        scanline = 0
        vBlank = false
    }

    private fun fetchNextScanLineTiles() {
//        This is where the first two tiles for the next scanline are fetched, and loaded into the shift registers. Again, each memory access takes 2 PPU cycles to complete, and 4 are performed for the two tiles:
//
//        Nametable byte
//                Attribute table byte
//        Tile bitmap low
//        Tile bitmap high (+8 bytes from tile bitmap low)
    }

    private fun fetchSpriteTile() {
//        The tile data for the sprites on the next scanline are fetched here. Again, each memory access takes 2 PPU cycles to complete, and 4 are performed for each of the 8 sprites:
//
//        Garbage nametable byte
//        Garbage nametable byte
//        Tile bitmap low
//        Tile bitmap high (+8 bytes from tile bitmap low)
//        The garbage fetches occur so that the same circuitry that performs the BG tile fetches could be reused for the sprite tile fetches.
//
//        If there are less than 8 sprites on the next scanline, then dummy fetches to tile $FF occur for the left-over sprites, because of the dummy sprite data in the secondary OAM (see sprite evaluation). This data is then discarded, and the sprites are loaded with a transparent bitmap instead.
//
//        In addition to this, the X positions and attributes for each sprite are loaded from the secondary OAM into their respective counters/latches. This happens during the second garbage nametable fetch, with the attribute byte loaded during the first tick and the X coordinate during the second.
    }

    private fun fetchData() {
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
    }

}

class SpriteRegister {
    // 64 sprites for the frame
    val primaryObjectAttributeMemory = 0

    //  Holds 8 sprites for the current scanline
    private val sprites = Array(size = 8, init = {Sprite()})

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

class Background {
    val vRamAddress = 0
    val tempVRamAddress = 0
    val fineXScroll = 0
    val firstWriteToggle = 0
    val secondWriteToggle = 0

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

    fun fetch() {
        //  Fetch bit from the 4 shift registers
        //  Exactly what bit fetched depends on fineXScroll
        //  Shift registers shifted (right/left?) once
    }

    fun nextTile() {
        //  TODO: Push nextTile to somewhere...
        nextTile = youngTile
        //  TODO: Load youngTile from somewhere...
    }
}

