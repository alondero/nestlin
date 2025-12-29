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
    private var frameCount = 0

    private val rand = Random()

    private var lastNametableByte: Byte = 0
    private var lastAttributeTableByte: Byte = 0

    // Diagnostic logging control
    private var diagnosticLogging = false
    private var diagnosticStartFrame = 0
    private var diagnosticEndFrame = 10  // Log first 10 frames
    private var diagnosticFile: java.io.PrintWriter? = null

    fun tick() {

//       println("Rendering ($cycle, $scanline)")
        if (cycle == 341) {
            endLine()
            return
        }


        if (rendering()) {
            // Fetch sprites for this scanline at cycle 0
            if (cycle == 0) {
                // Copy horizontal scroll from temp register BEFORE preloading
                // This must happen before preload since preload uses vRamAddress
                with(memory.ppuAddressedMemory) {
                    if (scanline in 0..239 || scanline == PRE_RENDER_SCANLINE) {
                        vRamAddress.coarseXScroll = tempVRamAddress.coarseXScroll
                        vRamAddress.horizontalNameTable = tempVRamAddress.horizontalNameTable
                    }
                }

                fetchActiveSpriteDataForScanline(scanline)
                // Pre-load the first two tiles for this scanline
                preloadFirstTwoTiles()
            }

            //  Fetch tile data
            when (cycle) {// Idle
                in 1..256 -> fetchData()
                in 257..320 -> fetchSpriteTile()
                in 321..336 -> fetchData() // Ready for next scanline
            }
            checkAndSetVerticalAndHorizontalData()

        }

        // VBlank is set at scanline 241, cycle 0 (0-indexed)
        if (scanline == 241 && cycle == 0) {
            vBlank = true
            memory.ppuAddressedMemory.nmiOccurred = true
        }

        if (scanline == PRE_RENDER_SCANLINE && cycle == 1) {memory.ppuAddressedMemory.status.clearFlags()}

        // Load shift registers every 8 cycles (at cycles 9, 17, 25, ..., 249, 257, 329, 337)
        // Reload happens after fetching is complete (at start of next tile's nametable fetch)
        if (cycle % 8 == 1 && cycle > 1 && ((cycle <= 256) || (cycle >= 321 && cycle <= 336))) {
            if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): BEFORE RELOAD - latchLow=0x${patternLatchLow.toUnsignedInt().toString(16).padStart(2, '0')} latchHigh=0x${patternLatchHigh.toUnsignedInt().toString(16).padStart(2, '0')} shiftLow=0x${patternShiftLow.toString(16).padStart(4, '0')} shiftHigh=0x${patternShiftHigh.toString(16).padStart(4, '0')}")
            }
            // Load the upper 8 bits of pattern shift registers with fetched tile data
            // Keep only lower 16 bits to prevent overflow beyond shift register capacity
            patternShiftLow = ((patternShiftLow and 0xFF) or (patternLatchLow.toUnsignedInt() shl 8)) and 0xFFFF
            patternShiftHigh = ((patternShiftHigh and 0xFF) or (patternLatchHigh.toUnsignedInt() shl 8)) and 0xFFFF

            // Reload palette attribute shift registers
            // Each bit represents whether that pixel uses this palette bit
            paletteShiftLow = if ((paletteLatch and 0x01) != 0) 0xFF.toByte() else 0x00
            paletteShiftHigh = if ((paletteLatch and 0x02) != 0) 0xFF.toByte() else 0x00

            if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): AFTER RELOAD - shiftLow=0x${patternShiftLow.toString(16).padStart(4, '0')} shiftHigh=0x${patternShiftHigh.toString(16).padStart(4, '0')}")
            }
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
        }

        if (scanline == PRE_RENDER_SCANLINE && cycle in 280..304) {
            with (memory.ppuAddressedMemory) {
                vRamAddress.coarseYScroll = tempVRamAddress.coarseYScroll
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
        frameCount++
        memory.ppuAddressedMemory.currentFrameCount = frameCount

        if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame) {
            logDiagnostic("\n=== FRAME $frameCount COMPLETE ===\n")
        }

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

    /**
     * Pre-load the shift registers with the first two tiles for this scanline.
     * The NES has a 2-tile buffer pre-loaded before rendering starts.
     *
     * After preloading:
     * - Shift registers contain tiles 0 and 1
     * - VRAM address is advanced by 2 positions
     * - First fetch cycle will continue from position 2 (tiles 2-3)
     */
    private fun preloadFirstTwoTiles() {
        with(memory.ppuAddressedMemory) {
            // Debug CTRL register on first render
            if (diagnosticLogging && frameCount == 3 && scanline == 0) {
                val ctrlReg = controller.register.toUnsignedInt()
                val bgPatternBase = controller.backgroundPatternTableAddress()
                logDiagnostic("=== CTRL REGISTER DEBUG ===")
                logDiagnostic("CTRL = 0x${ctrlReg.toString(16).padStart(2, '0')} (binary: ${ctrlReg.toString(2).padStart(8, '0')})")
                logDiagnostic("Bit 4 (BG pattern table): ${controller.register.isBitSet(4)}")
                logDiagnostic("Background pattern table address: 0x${bgPatternBase.toString(16).padStart(4, '0')}")
                logDiagnostic("=== END DEBUG ===")
            }
            // Load the first two tiles (tiles 0 and 1)
            for (tileNum in 0..1) {
                // Fetch nametable byte using vRamAddress nametable bits, not CTRL bits
                val nametableByte = ppuInternalMemory[0x2000 or (vRamAddress.asAddress() and 0x0FFF)]

                // Fetch attribute table byte
                val v = vRamAddress.asAddress()
                val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()
                // Extract which quadrant within the 2x2 tile block:
                // coarseX bit 0 -> shift bit 1, coarseY bit 0 -> shift bit 2
                val shift = ((v shr 4) and 4) or ((v shr 1) and 2)
                val paletteData = ((attributeByte shr shift) and 0x03)

                // Fetch pattern table data
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = nametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val patternLowAddr = patternTableBase + (tileIndex * 16) + fineY
                val patternHighAddr = patternTableBase + (tileIndex * 16) + fineY + 8
                val patternLow = ppuInternalMemory[patternLowAddr]
                val patternHigh = ppuInternalMemory[patternHighAddr]

                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount PRELOAD Tile $tileNum (scanline $scanline): nametable=0x${nametableByte.toUnsignedInt().toString(16).padStart(2, '0')} tileIdx=$tileIndex patternAddr=0x${patternLowAddr.toString(16).padStart(4, '0')} patternLow=0x${patternLow.toUnsignedInt().toString(16).padStart(2, '0')} patternHigh=0x${patternHigh.toUnsignedInt().toString(16).padStart(2, '0')} palette=$paletteData")
                }

                if (tileNum == 0) {
                    // Load tile 0 into lower 8 bits of shift registers
                    patternShiftLow = patternLow.toUnsignedInt()
                    patternShiftHigh = patternHigh.toUnsignedInt()
                    // Initialize palette for first tile
                    paletteShiftLow = if ((paletteData and 0x01) != 0) 0xFF.toByte() else 0x00
                    paletteShiftHigh = if ((paletteData and 0x02) != 0) 0xFF.toByte() else 0x00
                } else {
                    // Load tile 1 into upper 8 bits of shift registers
                    patternShiftLow = (patternShiftLow and 0xFF) or (patternLow.toUnsignedInt() shl 8)
                    patternShiftHigh = (patternShiftHigh and 0xFF) or (patternHigh.toUnsignedInt() shl 8)
                    // For tile 1, we need to have palette data ready to reload at cycle 9
                    paletteLatch = paletteData
                }

                // Move to next tile - address will now be at position 2
                // This is where the next fetch cycle will continue
                vRamAddress.incrementHorizontalPosition()
            }
        }
    }

    private fun fetchData() {
        // Log PPUMASK state and nametable contents at start of rendering for diagnostic frames
        if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && cycle == 1 && scanline == 0) {
            with(memory.ppuAddressedMemory) {
                val maskByte = mask.register.toUnsignedInt()
                val showBg = mask.showBackground()
                val showSprites = mask.showSprites()
                val vAddr = vRamAddress.asAddress()
                val ntSelect = (vAddr shr 10) and 3  // bits 10-11
                val ntSelectName = when (ntSelect) {
                    0 -> "NT0 (top)"
                    1 -> "NT1 (right)"
                    2 -> "NT2 (bottom)"
                    3 -> "NT3 (left)"
                    else -> "?"
                }
                logDiagnostic("FRAME $frameCount START: PPUMASK=0x${maskByte.toString(16).padStart(2, '0')} showBackground=$showBg showSprites=$showSprites rendering=${rendering()} vRamAddress=0x${vAddr.toString(16).padStart(4, '0')} ntSelect=$ntSelectName")

                // Log nametable contents (all entries, not just first 64)
                var nt0NonZero = 0
                var nt1NonZero = 0
                for (i in 0 until 0x400) {
                    if (ppuInternalMemory[0x2000 + i].toUnsignedInt() != 0) nt0NonZero++
                    if (ppuInternalMemory[0x2400 + i].toUnsignedInt() != 0) nt1NonZero++
                }
                logDiagnostic("FRAME $frameCount NT FULL: nt0 has $nt0NonZero/1024 non-zero entries, nt1 has $nt1NonZero/1024 non-zero entries")

                // Show first non-zero values in each
                if (nt0NonZero > 0) {
                    for (i in 0 until 0x400) {
                        val val0 = ppuInternalMemory[0x2000 + i].toUnsignedInt()
                        if (val0 != 0) {
                            logDiagnostic("FRAME $frameCount NT0 first non-zero: offset 0x${i.toString(16).padStart(3, '0')} value=0x${val0.toString(16).padStart(2, '0')}")
                            break
                        }
                    }
                }
                if (nt1NonZero > 0) {
                    for (i in 0 until 0x400) {
                        val val1 = ppuInternalMemory[0x2400 + i].toUnsignedInt()
                        if (val1 != 0) {
                            logDiagnostic("FRAME $frameCount NT1 first non-zero: offset 0x${i.toString(16).padStart(3, '0')} value=0x${val1.toString(16).padStart(2, '0')}")
                            break
                        }
                    }
                }
            }
        }

        when (cycle % 8) {
            1 -> with (memory.ppuAddressedMemory){
                val ntAddr = 0x2000 or (vRamAddress.asAddress() and 0x0FFF)
                lastNametableByte = ppuInternalMemory[ntAddr]
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    val vAddr = vRamAddress.asAddress()
                    val ntSelect = (vAddr shr 10) and 3  // bits 10-11
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): Fetch NAMETABLE addr=0x${ntAddr.toString(16).padStart(4, '0')} ntSelect=$ntSelect vAddr=0x${vAddr.toString(16).padStart(4, '0')} tileIdx=${lastNametableByte.toUnsignedInt()}")
                }
            }
            3 -> with(memory.ppuAddressedMemory){
                // Fetch Attribute Table Byte
                // Formula from nesdev wiki for attribute table addressing
                val v = vRamAddress.asAddress()
                val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()

                // Extract the 2-bit palette for this specific tile within the 2x2 block
                // coarseX bit 0 -> shift bit 1, coarseY bit 0 -> shift bit 2
                val shift = ((v shr 4) and 4) or ((v shr 1) and 2)
                paletteLatch = ((attributeByte shr shift) and 0x03)

                lastAttributeTableByte = attributeByte.toSignedByte()
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): Fetch ATTRIBUTE palette=$paletteLatch")
                }
            }
            5 -> with(memory.ppuAddressedMemory) {
                //  Fetch Low Tile Byte (bit 0 of each pixel)
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = lastNametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val address = patternTableBase + (tileIndex * 16) + fineY
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): BEFORE pattern-low fetch: patternLatchLow=0x${patternLatchLow.toUnsignedInt().toString(16).padStart(2, '0')}")
                }
                patternLatchLow = ppuInternalMemory[address]
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): Fetch PATTERN-LOW tileIdx=$tileIndex addr=0x${address.toString(16).padStart(4, '0')} data=0x${patternLatchLow.toUnsignedInt().toString(16).padStart(2, '0')}")
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): AFTER pattern-low fetch: patternLatchLow=0x${patternLatchLow.toUnsignedInt().toString(16).padStart(2, '0')}")
                }
            }
            7 -> with(memory.ppuAddressedMemory) {
                //  Fetch High Tile Byte (bit 1 of each pixel, 8 bytes after low byte)
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = lastNametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val address = patternTableBase + (tileIndex * 16) + fineY + 8
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): BEFORE pattern-high fetch: patternLatchHigh=0x${patternLatchHigh.toUnsignedInt().toString(16).padStart(2, '0')}")
                }
                patternLatchHigh = ppuInternalMemory[address]
                if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10) {
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): Fetch PATTERN-HIGH tileIdx=$tileIndex addr=0x${address.toString(16).padStart(4, '0')} data=0x${patternLatchHigh.toUnsignedInt().toString(16).padStart(2, '0')}")
                    logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline): AFTER pattern-high fetch: patternLatchHigh=0x${patternLatchHigh.toUnsignedInt().toString(16).padStart(2, '0')}")
                }
            }
        }

        // Increment horizontal position after fetching pattern data (cycle 7)
        if (cycle % 8 == 7 && (cycle in 1..256 || cycle in 321..336)) {
            with(memory.ppuAddressedMemory) {
                vRamAddress.incrementHorizontalPosition()
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
            // Keep only lower 16 bits (shift registers are 16-bit, not 32-bit)
            patternShiftLow = (patternShiftLow shl 1) and 0xFFFF
            patternShiftHigh = (patternShiftHigh shl 1) and 0xFFFF
            paletteShiftLow = (paletteShiftLow.toInt() shl 1).toByte()
            paletteShiftHigh = (paletteShiftHigh.toInt() shl 1).toByte()

            // Shift active sprite registers
            activeSpriteBuffer.forEach { sprite ->
                sprite.shiftLow = sprite.shiftLow shl 1
                sprite.shiftHigh = sprite.shiftHigh shl 1
            }

            // Extract pixel from shift registers
            // Fine X scroll only applies to the first tile (pixels 0-7)
            // After first tile, extract from bit 15 (MSB) normally
            val fineX = memory.ppuAddressedMemory.fineXScroll
            val pixelIndexInScanline = cycle - 1  // 0-255

            // For first 8 pixels, apply fine X offset; after that, extract from MSB
            val shiftAmount = if (pixelIndexInScanline < 8) 15 - fineX else 15
            val paletteShiftAmount = if (pixelIndexInScanline < 8) 7 - fineX else 7

            val patternBit0 = (patternShiftLow shr shiftAmount) and 1
            val patternBit1 = (patternShiftHigh shr shiftAmount) and 1
            val pixelValue = (patternBit1 shl 1) or patternBit0

            // Extract palette bits
            val paletteBit0 = (paletteShiftLow.toInt() shr paletteShiftAmount) and 1
            val paletteBit1 = (paletteShiftHigh.toInt() shr paletteShiftAmount) and 1
            val paletteIndex = (paletteBit1 shl 1) or paletteBit0

            if (diagnosticLogging && frameCount in diagnosticStartFrame until diagnosticEndFrame && scanline < 10 && x < 16) {
                logDiagnostic("FRAME $frameCount Cycle $cycle (SL $scanline) X=$x: PIXEL patternBits=$pixelValue palette=$paletteIndex shiftReg_Low=0x${patternShiftLow.toString(16).padStart(4, '0')} shiftReg_High=0x${patternShiftHigh.toString(16).padStart(4, '0')} fineX=$fineX")
            }


            // Look up color in palette RAM
            var finalPixel = 0
            var finalPalette = 0
            var usedBackground = false

            // Extract background pixel
            val paletteAddr = if (pixelValue == 0) {
                0x3F00  // Universal background color
            } else {
                usedBackground = true
                0x3F00 + (paletteIndex shl 2) + pixelValue
            }

            val bgNesColorIndex = memory.ppuAddressedMemory.ppuInternalMemory[paletteAddr].toUnsignedInt()
            var rgbColor = NesPalette.getRgb(bgNesColorIndex)


            // Check sprites (in order, first non-transparent sprite wins)
            for (sprite in activeSpriteBuffer) {
                // Current rendering X position (0-255)
                val pixelX = cycle - 1

                // Calculate sprite X position relative to current pixel
                val spritePixelX = pixelX - sprite.data.x

                // Skip if sprite is off-screen horizontally
                if (spritePixelX < 0 || spritePixelX > 7) continue

                // Extract sprite pixel from shift registers
                val shiftAmount = 7 - spritePixelX
                val spriteBit0 = (sprite.shiftLow shr shiftAmount) and 1
                val spriteBit1 = (sprite.shiftHigh shr shiftAmount) and 1
                val spritePixel = (spriteBit1 shl 1) or spriteBit0

                // Skip transparent sprite pixels
                if (spritePixel == 0) continue

                // Sprite is visible - check priority
                if (sprite.data.priority == 0 || !usedBackground) {
                    // In front of background OR background is transparent
                    finalPixel = spritePixel
                    finalPalette = sprite.data.paletteIndex
                    break  // First visible sprite wins
                }
            }

            // Look up final color
            if (finalPixel != 0) {
                // Sprite pixel is visible
                val spritePaletteAddr = 0x3F10 + (finalPalette shl 2) + finalPixel
                val spriteNesColorIndex = memory.ppuAddressedMemory.ppuInternalMemory[spritePaletteAddr].toUnsignedInt()
                rgbColor = NesPalette.getRgb(spriteNesColorIndex)
            }

            frame[x, scanline] = rgbColor
        }
    }

    fun addFrameListener(listener: FrameListener) {
        this.listener = listener
    }

    fun enableDiagnosticLogging(startFrame: Int = 3, endFrame: Int = 8) {
        diagnosticLogging = true
        diagnosticStartFrame = startFrame
        diagnosticEndFrame = endFrame
        try {
            diagnosticFile = java.io.PrintWriter(java.io.FileWriter("/tmp/ppu_diagnostics.log"))
            diagnosticFile!!.println("[DIAGNOSTIC LOG STARTED] Logging frames $startFrame-$endFrame")
            diagnosticFile!!.flush()
            System.err.println("[PPU] Diagnostic file opened successfully")
            // Also pass diagnostic file to PPU addressed memory for VRAM write logging
            memory.ppuAddressedMemory.setDiagnosticFile(diagnosticFile!!, startFrame, endFrame)
        } catch (e: Exception) {
            System.err.println("[PPU] Failed to open diagnostic file: ${e.message}")
        }
    }

    private fun logDiagnostic(msg: String) {
        if (diagnosticLogging && diagnosticFile != null) {
            try {
                diagnosticFile!!.println(msg)
                diagnosticFile!!.flush()
            } catch (e: Exception) {
                System.err.println("[PPU] Error writing diagnostic: ${e.message}")
            }
        }
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

