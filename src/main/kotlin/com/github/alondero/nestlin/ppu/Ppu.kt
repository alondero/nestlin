package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort

const val RESOLUTION_WIDTH = 256
const val RESOLUTION_HEIGHT = 224
const val NTSC_SCANLINES = 262

const val IDLE_SCANLINE = 0
const val POST_RENDER_SCANLINE = 240

class Ppu {
    var cycle = 0
    val background = Background()
    val sprites = SpriteRegister()
    var vBlank = false

    fun tick() {
        when (cycle) {
            IDLE_SCANLINE,POST_RENDER_SCANLINE -> return // Idle
            in 1..256 -> fetchData()
            in 257..320 -> fetchSpriteTile()
            in 321..336 -> fetchNextScanLineTiles()
            in 337..340 -> return // Does irrelevant stuff
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
                //  If the counter is zero, the sprite becomes "active", and the respective pair of shift registers for the sprite is shifted once every cycle.
                // This output accompanies the data in the sprite's latch, to form a pixel.
                // The current pixel for each "active" sprite is checked (from highest to lowest priority), and the first non-transparent pixel moves on to a multiplexer,
                // where it joins the BG pixel
            }
        }

        cycle++
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
        val bitmapDataA: Byte = 0,
        val bitMapDataB: Byte = 0,
        val latch: Byte = 0,
        val counter: Int = 0
) {
    fun isActive() = counter <= 0
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

