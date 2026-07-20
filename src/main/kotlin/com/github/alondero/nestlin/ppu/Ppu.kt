package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.ui.FrameListener
import java.io.DataInput
import java.io.DataOutput

const val RESOLUTION_WIDTH = 256
const val RESOLUTION_HEIGHT = 240

const val POST_RENDER_SCANLINE = 240

/**
 * Whether the raster beam has finished drawing row [targetY] this frame, given it
 * is currently on [beamScanline]. The [Frame] buffer is reused across
 * frames, so a row the beam has not yet redrawn still holds the *previous* frame's
 * colour — the Zapper light sensor must treat such a pixel as "not yet lit" or it
 * reads phantom light (e.g. last frame's bright sky) during a game's blank-then-flash
 * hit-detection sequence. A row counts as drawn once the beam has moved strictly past
 * it (including the whole vblank period, where beamScanline >= 241 > any visible row).
 */
internal fun beamHasDrawnRow(targetY: Int, beamScanline: Int): Boolean = beamScanline > targetY

class Ppu(var memory: Memory) {

    private var cycle = 0
    private var scanline = 0

    // Read-only test seams: the dot/scanline the PPU is currently on. Lets timing
    // diagnostics correlate a CPU write with the exact raster position, the same way
    // Mesen2's emu.getState() exposes ppu.scanline/ppu.cycle. Not used by emulation.
    val currentScanline: Int get() = scanline
    val currentCycle: Int get() = cycle

    /**
     * Sum of the R+G+B channels (0..765) of the pixel the light gun is aimed at,
     * read from the LIVE frame the PPU is *currently* drawing. This is
     * what the Zapper light sensor samples on a `$4017` read: reading the in-progress
     * frame (not the last published one) removes a full frame of lag, which is what
     * decouples a game's hit from where the player is actually aiming during its fast
     * blank-then-flash detection sequence.
     *
     * Returns 0 ("dark") for a row the beam has not yet drawn this frame (see
     * [beamHasDrawnRow] — the reused [Frame] would otherwise hand back the previous
     * frame's colour). Off-screen coordinates return -1.
     *
     * Runs on the emulation thread (the `$4017` read path), the same thread that
     * writes [frame], so no synchronisation is needed. (The Memory Editor's peek path
     * may call it from the UI thread; a torn read there only mis-colours a debug view.)
     */
    fun aimBrightness(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= RESOLUTION_WIDTH || y >= RESOLUTION_HEIGHT) return -1
        if (!beamHasDrawnRow(y, scanline)) return 0
        val rgb = frame[x, y]
        return ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
    }

    // Active timing region. NTSC has 262 scanlines (pre-render at 261); PAL has
    // 312 (pre-render at 311). VBlank still begins at scanline 241 dot 1 for both;
    // only the pre-render line index and total line count change.
    var region: Region = Region.NTSC

    // Stable testing hook: total dots ticked since construction. Lets timing tests
    // verify the PPU:CPU ratio and per-frame dot count without reflecting into
    // private cycle/scanline state. Not used by emulation.
    var ticksElapsed: Long = 0L
        private set

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

    // Palette attribute shift registers (16-bit, hold palette for next 16 pixels)
    // Each bit corresponds to one bit of the 2-bit palette index
    private var paletteShiftLow: Int = 0
    private var paletteShiftHigh: Int = 0

    // Latches hold fetched data until time to load into shift registers (every 8 cycles)
    private var patternLatchLow: Byte = 0
    private var patternLatchHigh: Byte = 0
    private var paletteLatch: Int = 0

    // Active sprites for current scanline (max 8) — consumed by fetchData() during 2-257.
    private val activeSpriteBuffer = mutableListOf<ActiveSprite>()

    // Secondary OAM: up to 8 sprites picked during evaluation (cycle 65) for the next scanline.
    // The tile-row offset is pre-computed with vertical-flip applied.
    private data class SecondaryOamEntry(val sprite: SpriteData, val tileY: Int)
    private val secondaryOam = mutableListOf<SecondaryOamEntry>()

    // Sprites being assembled during the cycles 257-320 fetch phase, ready to render on the
    // next scanline. Swapped into activeSpriteBuffer in endLine() before the next scanline begins.
    private val nextScanlineSprites = mutableListOf<ActiveSprite>()

    // Latches for the currently-fetching sprite slot. The pattern address is computed once at
    // accessType=2 and reused at accessType=3 to avoid recomputing 8x16 arithmetic.
    private var spriteTileLowLatch: Byte = 0
    private var spriteTileHighLatch: Byte = 0
    private var spritePatternAddrLatch: Int = 0

    // Multicast frame listeners. The renderer (Application.frameUpdated) is one entry; the movie
    // replayer is another. Listeners are fired in registration order at end-of-frame, so a later
    // listener (e.g. a latch hook) can see the frame the renderer has already observed. Multicast
    // was chosen over a dedicated movie hook so the PPU stays ignorant of input semantics.
    //
    // CopyOnWriteArrayList: the emulation thread iterates these at end-of-frame while the
    // JavaFX thread may concurrently add/remove (when a movie session starts/stops). A regular
    // ArrayList would throw ConcurrentModificationException on that interleaving — exactly
    // what the latch hook add/remove does. CoW pays a small per-write cost (defensive copy
    // on mutation) for lock-free, exception-free iteration, which is the right shape for
    // "many readers, rare writer" (issue #123, post-review).
    private val frameListeners = java.util.concurrent.CopyOnWriteArrayList<FrameListener>()
    private val frameCompletionListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var vBlank = false
    private var frame = Frame()
    private var frameCount = 0

    private var lastNametableByte: Byte = 0

    // Frame completion tracking for throttling
    private var frameCompletedThisTick = false

    fun tick() {
        ticksElapsed++
//       println("Rendering ($cycle, $scanline)")
        if (cycle == 341) {
            endLine()
            return
        }


        val renderingEnabled = rendering()
        val renderingActive = renderingEnabled && (scanline in 0 until RESOLUTION_HEIGHT || scanline == region.preRenderScanline)

        if (renderingActive) {
            if (cycle == 0) {
                // Pre-load the first two BG tiles for this scanline (reads pattern table 0/1
                // depending on PPUCTRL bit 4 — drives A12 low for typical MMC3 setups).
                preloadFirstTwoTiles()
            }

            // Horizontal scroll copy (v <- t) happens at NES dot 257 == Nestlin
            // cycle 256 (issue #227). It used to fire at cycle 0 of the rendering
            // line, which is one scanline late: a $2005/$2006 write during the
            // sprite-fetch window (cycles 257..319 of the previous line) would be
            // picked up for THIS scanline's BG fetch instead of the NEXT.
            if (cycle == 256) {
                with(memory.ppuAddressedMemory) {
                    vRamAddress.coarseXScroll = tempVRamAddress.coarseXScroll
                    vRamAddress.horizontalNameTable = tempVRamAddress.horizontalNameTable
                }
            }

            // Sprite evaluation for the *next* scanline happens at NES dot 65. Nestlin's
            // cycle counter is 0-indexed (endLine resets to 0), so dot 65 == cycle 64.
            // We do the full scan in one cycle for simplicity; it has no pattern-table
            // accesses, so it does not affect A12.
            if (cycle == 64) {
                evaluateSpritesForTargetScanline(targetScanlineForSpriteEval())
            }

            //  Fetch tile data. All three windows are 0-indexed (cycle 0 == NES dot 1).
            when (cycle) {
                in 0..255 -> fetchData()
                in 256..319 -> fetchSpriteTile()
                in 320..335 -> fetchData() // Ready for next scanline
            }
            if (scanline < RESOLUTION_HEIGHT && cycle in 1..256) renderPixel()
            checkAndSetVerticalAndHorizontalData()

        } else if (scanline < RESOLUTION_HEIGHT && cycle in 1..256) {
            // Rendering disabled on a visible scanline: a real PPU still scans out a
            // pixel every dot — the backdrop colour at $3F00 (or, if the VRAM address
            // happens to point into the palette, that entry — the "background palette
            // hack"). Without emitting these pixels the frame buffer keeps whatever was
            // drawn before, so a mid-frame forced-blank window (e.g. Micro Machines'
            // title-screen palette swap) leaves a frozen "band" of stale tiles.
            // Issue #88 follow-up.
            frame[cycle - 1, scanline] = resolveColor(backdropPaletteAddress())
        }

        // VBlank is set at scanline 241, NES dot 1 == Nestlin cycle 0.
        if (scanline == 241 && cycle == 0) {
            vBlank = true
            memory.ppuAddressedMemory.setVBlank()
        }

        if (scanline == region.preRenderScanline && cycle == 0) {memory.ppuAddressedMemory.clearVBlankAtPreRender()}

        // MMC3 scanline IRQ is triggered solely by A12 rising edges from pattern fetches.

        // Load shift registers every 8 cycles (at NES dots 9, 17, 25, ..., 249, 329, 337
        // == Nestlin cycles 8, 16, 24, ..., 248, 328, 336). Nestlin's cycle is 0-indexed
        // (cycle 0 == NES dot 1), so the reload fires when cycle % 8 == 0 starting at 8.
        // Note: NES reloads at 329 and 337 for post-render (cycles 321-340), NOT at 321.
        // Visible reloads: cycles 0-255 at 8, 16, 24, ..., 248 (NOT at 256 which is sprite eval).
        if (renderingActive && cycle % 8 == 0 && cycle > 0 && ((cycle <= 255) || (cycle >= 328 && cycle <= 336))) {
            // Load the lower 8 bits of pattern shift registers with fetched tile data (Next Tile)
            // Keep upper 8 bits (Current Tile) which have shifted from previous fetch
            patternShiftLow = (patternShiftLow and 0xFF00) or patternLatchLow.toUnsignedInt()
            patternShiftHigh = (patternShiftHigh and 0xFF00) or patternLatchHigh.toUnsignedInt()

            // Reload palette attribute shift registers
            // Load into lower 8 bits (for next tile), keep upper 8 bits (for current tile)
            paletteShiftLow = (paletteShiftLow and 0xFF00) or (if ((paletteLatch and 0x01) != 0) 0xFF else 0x00)
            paletteShiftHigh = (paletteShiftHigh and 0xFF00) or (if ((paletteLatch and 0x02) != 0) 0xFF else 0x00)
        }

        //  Every cycle a bit is fetched from the 4 backgroundNametables shift registers in order to create a pixel on screen


        cycle++
    }

    private fun rendering() = with (memory.ppuAddressedMemory.mask) { showBackground() || showSprites() }

    /**
     * Final colour for the palette entry at [paletteAddr], with PPUMASK bit 0
     * (greyscale — masks the index to the grey column $x0) and bits 5-7 (colour
     * emphasis) applied. Every pixel the PPU emits goes through here so the two
     * display modifiers affect background, sprites and forced-blank backdrop alike.
     */
    private fun resolveColor(paletteAddr: Int): Int {
        val mask = memory.ppuAddressedMemory.mask
        var index = memory.ppuAddressedMemory.ppuInternalMemory[paletteAddr].toUnsignedInt()
        if (mask.greyscale()) index = index and 0x30
        return NesPalette.getRgb(index, emphasisBits(mask))
    }

    /**
     * The 3-bit emphasis field from PPUMASK. On PAL machines the red and green
     * emphasis bits are swapped relative to NTSC (2C07 wiring difference).
     */
    private fun emphasisBits(mask: Mask): Int {
        val bits = (mask.register.toUnsignedInt() shr 5) and 0x07
        return if (region == Region.PAL) {
            (bits and 0x4) or ((bits and 0x1) shl 1) or ((bits and 0x2) shr 1)
        } else bits
    }

    /**
     * The palette address whose colour the PPU emits for a visible pixel while rendering
     * is disabled. Normally the universal backdrop at $3F00, but if the current VRAM
     * address points into the palette range ($3F00-$3FFF) the PPU emits that entry
     * instead — the hardware "background palette hack". Palette mirroring is handled by
     * [PpuInternalMemory].
     */
    private fun backdropPaletteAddress(): Int {
        val v = memory.ppuAddressedMemory.vRamAddress.asAddress() and 0x3FFF
        return if (v >= 0x3F00) 0x3F00 or (v and 0x1F) else 0x3F00
    }

    private fun checkAndSetVerticalAndHorizontalData() {
        when (cycle) {
            255 -> memory.ppuAddressedMemory.vRamAddress.incrementVerticalPosition()
        }

        if (scanline == region.preRenderScanline && cycle in 279..303) {
            with (memory.ppuAddressedMemory) {
                vRamAddress.coarseYScroll = tempVRamAddress.coarseYScroll
                vRamAddress.verticalNameTable = tempVRamAddress.verticalNameTable
                vRamAddress.fineYScroll = tempVRamAddress.fineYScroll
            }
        }
    }

    private fun endLine() {
        // Sprite tile data assembled during cycles 257-320 becomes the active set for the
        // upcoming scanline. The secondary-OAM scratch buffer is also cleared for the next eval.
        activeSpriteBuffer.clear()
        activeSpriteBuffer.addAll(nextScanlineSprites)
        nextScanlineSprites.clear()
        secondaryOam.clear()

        when (scanline) {
            region.totalScanlines - 1 -> endFrame()
            else -> scanline++
        }
        cycle = 0
    }

    private fun endFrame() {
        // Fire all registered frame listeners (renderer, then later consumers like the movie
        // latch hook). Listeners run in registration order; if any one throws, the remaining
        // listeners for this frame are skipped (we still reset scanline/vBlank below).
        for (listener in frameListeners) {
            listener.frameUpdated(frame)
        }
        frameCount++

        // Signal frame completion for throttling + the movie latch hook. The latch hook uses
        // this to commit the next frame's input, which is why it must fire AFTER the render
        // listener — by the time the latch writes to controller.buttons, the renderer has
        // already observed the frame that just ended.
        frameCompletedThisTick = true
        for (listener in frameCompletionListeners) {
            listener.invoke()
        }

        //  What to do here?
        scanline = 0
        vBlank = false
    }

    /**
     * The scanline that the current sprite eval/fetch phase is preparing for.
     * For pre-render (261), prep is for scanline 0 of the next frame.
     */
    private fun targetScanlineForSpriteEval(): Int =
        if (scanline == region.preRenderScanline) 0 else scanline + 1

    /**
     * Sprite evaluation: scan primary OAM, pick up to 8 sprites visible on the target scanline.
     * Stores their resolved tile-Y offset (with vertical-flip applied) in secondaryOam.
     * No pattern-table accesses occur here, so A12 is not affected.
     *
     * The hardware rotates the scan so it STARTS at the current OAMADDR (issue #227),
     * not always at sprite 0. This is what games exploit for sprite-cycling flicker
     * tricks (Galaga, Pac-Man, etc.) — the OAM byte read offset is preserved across
     * the eval pass.
     */
    private fun evaluateSpritesForTargetScanline(target: Int) {
        secondaryOam.clear()
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        val spriteSize = memory.ppuAddressedMemory.controller.spriteSize()
        val spriteHeight = if (spriteSize == Control.SpriteSize.X_8_16) 16 else 8
        // OAMADDR is a byte offset (4 bytes per sprite); divide by 4 to get the
        // starting sprite index. The hardware scans OAM in rotated order from there.
        val startIndex = (memory.ppuAddressedMemory.oamAddress.toUnsignedInt() shr 2) and 0x3F

        for (n in 0 until 64) {
            val i = (startIndex + n) and 0x3F
            val s = oam.getSprite(i)
            val y = s.y
            // NES Y semantics: sprite at Y appears at scanlines Y+1 through Y+spriteHeight
            if (target > y && target <= y + spriteHeight) {
                if (secondaryOam.size >= 8) {
                    // A 9th in-range sprite sets PPUSTATUS bit 5 (sprite overflow),
                    // cleared at pre-render dot 1 alongside the other status flags.
                    // The hardware's cycle-by-cycle diagonal-OAM scan can produce
                    // false positives and negatives. That quirk is intentionally not
                    // modelled here: it requires a per-dot evaluation state machine,
                    // while this one-shot scan covers the documented >8-sprite case.
                    memory.ppuAddressedMemory.status.register =
                        memory.ppuAddressedMemory.status.register.setBit(5)
                    break
                }
                val tileYOffset = target - y - 1
                val tileY = if (s.verticalFlip) (spriteHeight - 1) - tileYOffset else tileYOffset
                secondaryOam.add(SecondaryOamEntry(s, tileY))
            }
        }
    }

    /**
     * Sprite tile fetch phase (cycles 257-320 of scanline N, preparing for scanline N+1).
     * Each of the 8 slots gets 4 memory accesses spread across 8 cycles:
     *   access 0,1: garbage nametable read at $2xxx (A12 unaffected for typical setups)
     *   access 2:   pattern table low byte at the sprite's tile address ($0xxx or $1xxx)
     *   access 3:   pattern table high byte (low + 8)
     *
     * For typical MMC3 games (BG at $0000, sprites at $1000), this produces exactly one
     * A12 rising edge per scanline — the transition from BG fetches at $0xxx to the first
     * sprite pattern read at $1xxx. The PpuInternalMemory 3-cycle low-time filter suppresses
     * the spurious per-sprite A12 toggles caused by garbage NT reads between sprites.
     */
    private fun fetchSpriteTile() {
        val slotIndex = (cycle - 256) / 4
        if (slotIndex >= 8) return
        val accessType = (cycle - 256) % 4

        val mem = memory.ppuAddressedMemory.ppuInternalMemory
        val entry = secondaryOam.getOrNull(slotIndex)

        when (accessType) {
            0, 1 -> {
                val vramAddr = memory.ppuAddressedMemory.vRamAddress.asAddress()
                mem[0x2000 or (vramAddr and 0x0FFF)]
            }
            2 -> {
                spritePatternAddrLatch = resolveSpritePatternAddress(entry)
                spriteTileLowLatch = mem[spritePatternAddrLatch]
            }
            3 -> {
                spriteTileHighLatch = mem[spritePatternAddrLatch + 8]
                if (entry != null) {
                    nextScanlineSprites.add(buildActiveSprite(entry, spriteTileLowLatch, spriteTileHighLatch))
                }
            }
        }
    }

    /**
     * Pattern-table address for a sprite slot. In 8x16 mode the pattern table is selected
     * per-sprite by bit 0 of the tile index (overriding PPUCTRL bit 3). Empty slots fetch
     * tile $FF from the default sprite pattern table — the canonical dummy fetch that still
     * drives A12 for hardware-accurate bus behaviour.
     */
    private fun resolveSpritePatternAddress(entry: SecondaryOamEntry?): Int {
        val controller = memory.ppuAddressedMemory.controller
        val spritePatternBase = controller.spritePatternTableAddress()
        if (entry == null) return spritePatternBase + (0xFF * 16)

        val tileIndex = entry.sprite.tileIndex.toUnsignedInt()
        return if (controller.spriteSize() == Control.SpriteSize.X_8_16) {
            val base = (tileIndex and 0x01) * 0x1000
            val tileNumber = (tileIndex and 0xFE) + if (entry.tileY >= 8) 1 else 0
            base + (tileNumber * 16) + (entry.tileY and 0x07)
        } else {
            spritePatternBase + (tileIndex * 16) + entry.tileY
        }
    }

    private fun buildActiveSprite(entry: SecondaryOamEntry, low: Byte, high: Byte): ActiveSprite {
        // +1 so the counter reaches zero on the cycle whose frame column equals OAM X.
        val active = ActiveSprite(
            data = entry.sprite,
            tileDataLow = low,
            tileDataHigh = high,
            xCounter = entry.sprite.x + 1
        )
        if (entry.sprite.horizontalFlip) {
            active.shiftLow = reverseBits(low.toUnsignedInt() and 0xFF)
            active.shiftHigh = reverseBits(high.toUnsignedInt() and 0xFF)
        } else {
            active.shiftLow = low.toUnsignedInt()
            active.shiftHigh = high.toUnsignedInt()
        }
        return active
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
            // Load the first two tiles (tiles 0 and 1)
            for (tileNum in 0..1) {
                // Fetch nametable byte using vRamAddress nametable bits, not CTRL bits
                val nametableByte = ppuInternalMemory[0x2000 or (vRamAddress.asAddress() and 0x0FFF)]

                // Fetch attribute table byte
                val v = vRamAddress.asAddress()
                val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()
                // Extract which quadrant within the 2x2 tile block:
                // coarseX bit 1 (bit 1 of v) -> shift bit 1 (value 2)
                // coarseY bit 1 (bit 6 of v) -> shift bit 2 (value 4)
                val shift = ((v shr 4) and 4) or (v and 2)
                val paletteData = ((attributeByte shr shift) and 0x03)

                // Fetch pattern table data
                val patternTableBase = controller.backgroundPatternTableAddress()
                val tileIndex = nametableByte.toUnsignedInt()
                val fineY = vRamAddress.fineYScroll
                val patternLowAddr = patternTableBase + (tileIndex * 16) + fineY
                val patternHighAddr = patternTableBase + (tileIndex * 16) + fineY + 8
                val patternLow = ppuInternalMemory[patternLowAddr]
                val patternHigh = ppuInternalMemory[patternHighAddr]

                if (tileNum == 0) {
                    // Load tile 0 into upper 8 bits (Current Tile)
                    patternShiftLow = patternLow.toUnsignedInt() shl 8
                    patternShiftHigh = patternHigh.toUnsignedInt() shl 8
                    // Initialize palette for first tile (upper 8 bits)
                    paletteShiftLow = if ((paletteData and 0x01) != 0) 0xFF00 else 0x00
                    paletteShiftHigh = if ((paletteData and 0x02) != 0) 0xFF00 else 0x00
                } else {
                    // Load tile 1 into lower 8 bits (Next Tile)
                    patternShiftLow = patternShiftLow or patternLow.toUnsignedInt()
                    patternShiftHigh = patternShiftHigh or patternHigh.toUnsignedInt()
                    // Load palette for tile 1 into lower 8 bits
                    paletteShiftLow = paletteShiftLow or (if ((paletteData and 0x01) != 0) 0xFF else 0x00)
                    paletteShiftHigh = paletteShiftHigh or (if ((paletteData and 0x02) != 0) 0xFF else 0x00)
                    // Also update latch so it's ready for the standard pipeline
                    paletteLatch = paletteData
                }

                // Move to next tile - address will now be at position 2
                // This is where the next fetch cycle will continue
                vRamAddress.incrementHorizontalPosition()
            }
        }
    }

    private fun fetchData() {
        when (cycle % 8) {
            0 -> with (memory.ppuAddressedMemory){
                val ntAddr = 0x2000 or (vRamAddress.asAddress() and 0x0FFF)
                lastNametableByte = ppuInternalMemory[ntAddr]
            }
            2 -> with(memory.ppuAddressedMemory){
                // Fetch Attribute Table Byte
                // Formula from nesdev wiki for attribute table addressing
                val v = vRamAddress.asAddress()
                val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()

                // Extract the 2-bit palette for this specific tile within the 2x2 block
                // coarseX bit 1 (bit 1 of v) -> shift bit 1 (value 2)
                // coarseY bit 1 (bit 6 of v) -> shift bit 2 (value 4)
                val shift = ((v shr 4) and 4) or (v and 2)
                paletteLatch = ((attributeByte shr shift) and 0x03)

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

        // Increment horizontal position after fetching pattern data (NES dot 7 == Nestlin cycle 6).
        // Only during the visible fetch window (cycles 0-255); do NOT advance at 320-335 or
        // preloadFirstTwoTiles at the next scanline's cycle 0 will read two tiles past t.coarseX
        // (regression of the issue-#227 fix: SMB3 status bar reads "ORLD 1" instead of "WORLD 1").
        if (cycle % 8 == 6 && cycle in 0..255) {
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

    }

    /** Render one visible pixel. Called independently of the background/sprite fetch window. */
    private fun renderPixel() {
        val x = cycle - 1

        // Extract pixel from shift registers
        val fineX = memory.ppuAddressedMemory.fineXScroll
        val shiftAmount = 15 - fineX

        val patternBit0 = (patternShiftLow shr shiftAmount) and 1
        val patternBit1 = (patternShiftHigh shr shiftAmount) and 1
        val pixelValue = (patternBit1 shl 1) or patternBit0

        // Extract palette bits
        val paletteBit0 = (paletteShiftLow shr shiftAmount) and 1
        val paletteBit1 = (paletteShiftHigh shr shiftAmount) and 1
        val paletteIndex = (paletteBit1 shl 1) or paletteBit0

        // Shift registers every cycle to advance to next pixel
        // Keep only lower 16 bits (shift registers are 16-bit, not 32-bit)
        patternShiftLow = (patternShiftLow shl 1) and 0xFFFF
        patternShiftHigh = (patternShiftHigh shl 1) and 0xFFFF
        paletteShiftLow = (paletteShiftLow shl 1) and 0xFFFF
        paletteShiftHigh = (paletteShiftHigh shl 1) and 0xFFFF

        // Update sprite X counters and shift only active sprites
        // On NES hardware, each sprite has an X position counter that counts down each cycle.
        // When counter reaches 0, the sprite becomes "active" and its shift registers output pixels.
        activeSpriteBuffer.forEach { sprite ->
            if (sprite.xCounter > 0) {
                // Counter not yet zero, decrement it
                sprite.xCounter--
                if (sprite.xCounter == 0) {
                    // Counter reached 0, sprite is now active
                    sprite.isActive = true
                }
            } else {
                // Sprite is active (counter already at 0), shift its registers
                sprite.shiftLow = (sprite.shiftLow shl 1) and 0xFF
                sprite.shiftHigh = (sprite.shiftHigh shl 1) and 0xFF
            }
        }

        // Look up color in palette RAM
        var finalPixel = 0
        var finalPalette = 0
        var usedBackground = false

        // Extract background pixel. PPUMASK bit 3 gates the background layer
        // on its own (independent of bit 4) — a disabled background renders
        // as pixel value 0 (the backdrop) even while sprites are showing.
        val showBg = memory.ppuAddressedMemory.mask.showBackground()
        val showBgLeft = memory.ppuAddressedMemory.mask.backgroundInLeftmost8px()
        val pixelValueVisible = if (!showBg || (x < 8 && !showBgLeft)) 0 else pixelValue

        val paletteAddr = if (pixelValueVisible == 0) {
            0x3F00  // Universal background color
        } else {
            usedBackground = true
            0x3F00 + (paletteIndex shl 2) + pixelValueVisible
        }

        var rgbColor = resolveColor(paletteAddr)

        // Check sprites (in order, first non-transparent sprite wins).
        // PPUMASK bit 4 gates the sprite layer on its own: with sprites
        // disabled, no sprite pixels and no sprite-0 hit.
        val showSprites = memory.ppuAddressedMemory.mask.showSprites()
        val showSpritesLeft = memory.ppuAddressedMemory.mask.spritesInLeftmost8px()
        if (showSprites) for (sprite in activeSpriteBuffer) {
            // Skip if clipping leftmost 8px
            val pixelX = cycle - 1
            if (pixelX < 8 && !showSpritesLeft) continue

            // Only render sprites that are actively outputting pixels
            // (xCounter reached 0 and we're within the 8-pixel output window)
            if (!sprite.isActive) continue

            // Extract sprite pixel from shift register MSB
            // Since shift registers shift left, the MSB (bit 7) is the current pixel
            val spriteBit0 = (sprite.shiftLow shr 7) and 1
            val spriteBit1 = (sprite.shiftHigh shr 7) and 1
            val spritePixel = (spriteBit1 shl 1) or spriteBit0

            // Skip transparent sprite pixels
            if (spritePixel == 0) continue

            // Sprite 0 Hit detection
            if (sprite.data.index == 0 && usedBackground && pixelX < 255) {
                val mask = memory.ppuAddressedMemory.mask
                var canHit = true
                if (pixelX < 8) {
                    if (!mask.backgroundInLeftmost8px() || !mask.spritesInLeftmost8px()) {
                        canHit = false
                    }
                }
                if (canHit) {
                    memory.ppuAddressedMemory.status.register =
                        memory.ppuAddressedMemory.status.register.setBit(6)
                }
            }

            // First non-transparent sprite in OAM order always wins the sprite-sprite priority contest
            if (sprite.data.priority == 0 || !usedBackground) {
                // In front of background OR background is transparent
                finalPixel = spritePixel
                finalPalette = sprite.data.paletteIndex
            }

            // Even if hidden by background, this sprite blocks lower-indexed sprites
            break
        }

        // Look up final color
        if (finalPixel != 0) {
            // Sprite pixel is visible
            rgbColor = resolveColor(0x3F10 + (finalPalette shl 2) + finalPixel)
        }

        frame[x, scanline] = rgbColor
    }

    /**
     * Register a frame listener. Multiple listeners are supported; all registered listeners
     * fire in registration order at the end of each frame. Pre-existing single-slot callers
     * (e.g. `NestlinApplication.frameUpdated`) continue to work — they're simply one entry
     * in a list of one.
     */
    fun addFrameListener(listener: FrameListener) {
        frameListeners.add(listener)
    }

    /**
     * Remove a previously-registered frame listener. Used by the movie recorder/player to
     * detach its latch hook when the session ends. Safe to call with a listener that was
     * never registered.
     */
    fun removeFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    /**
     * Check if a frame just completed on the last tick.
     * This is a one-shot flag that clears after being read.
     * Used for frame rate throttling.
     */
    fun frameJustCompleted(): Boolean {
        val result = frameCompletedThisTick
        frameCompletedThisTick = false
        return result
    }

    /**
     * Register a frame-completion listener. Fires at the end of each frame, AFTER the frame
     * listeners (so the renderer has observed the frame first). Used by the throttle loop
     * and by the movie replayer to commit per-frame input latches.
     */
    fun addFrameCompletionListener(listener: () -> Unit) {
        frameCompletionListeners.add(listener)
    }

    /**
     * Remove a previously-registered frame-completion listener. Used by the movie
     * recorder/player to detach its latch hook when the session ends. Safe to call with a
     * listener that was never registered.
     */
    fun removeFrameCompletionListener(listener: () -> Unit) {
        frameCompletionListeners.remove(listener)
    }

    fun saveState(out: DataOutput) {
        memory.ppuAddressedMemory.saveState(out)

        out.writeInt(cycle)
        out.writeInt(scanline)

        out.writeInt(patternShiftLow)
        out.writeInt(patternShiftHigh)
        out.writeInt(paletteShiftLow)
        out.writeInt(paletteShiftHigh)

        out.writeByte(patternLatchLow.toInt())
        out.writeByte(patternLatchHigh.toInt())
        out.writeInt(paletteLatch)

        out.writeBoolean(vBlank)
        out.writeInt(frameCount)
        out.writeByte(lastNametableByte.toInt())

        out.writeByte(spriteTileLowLatch.toInt())
        out.writeByte(spriteTileHighLatch.toInt())
        out.writeInt(spritePatternAddrLatch)

        writeActiveSpriteList(out, activeSpriteBuffer)
        writeActiveSpriteList(out, nextScanlineSprites)
        writeSecondaryOamList(out, secondaryOam)
    }

    fun loadState(input: DataInput) {
        memory.ppuAddressedMemory.loadState(input)

        cycle = input.readInt()
        scanline = input.readInt()

        patternShiftLow = input.readInt()
        patternShiftHigh = input.readInt()
        paletteShiftLow = input.readInt()
        paletteShiftHigh = input.readInt()

        patternLatchLow = input.readByte()
        patternLatchHigh = input.readByte()
        paletteLatch = input.readInt()

        vBlank = input.readBoolean()
        frameCount = input.readInt()
        lastNametableByte = input.readByte()

        spriteTileLowLatch = input.readByte()
        spriteTileHighLatch = input.readByte()
        spritePatternAddrLatch = input.readInt()

        activeSpriteBuffer.clear()
        activeSpriteBuffer.addAll(readActiveSpriteList(input))
        nextScanlineSprites.clear()
        nextScanlineSprites.addAll(readActiveSpriteList(input))
        secondaryOam.clear()
        secondaryOam.addAll(readSecondaryOamList(input))
    }

    private fun writeActiveSpriteList(out: DataOutput, list: List<ActiveSprite>) {
        out.writeInt(list.size)
        for (s in list) {
            writeSpriteData(out, s.data)
            out.writeByte(s.tileDataLow.toInt())
            out.writeByte(s.tileDataHigh.toInt())
            out.writeInt(s.shiftLow)
            out.writeInt(s.shiftHigh)
            out.writeInt(s.xCounter)
            out.writeBoolean(s.isActive)
        }
    }

    private fun readActiveSpriteList(input: DataInput): List<ActiveSprite> {
        val n = input.readInt()
        return List(n) {
            val data = readSpriteData(input)
            val tileLow = input.readByte()
            val tileHigh = input.readByte()
            ActiveSprite(
                data = data,
                tileDataLow = tileLow,
                tileDataHigh = tileHigh,
                shiftLow = input.readInt(),
                shiftHigh = input.readInt(),
                xCounter = input.readInt(),
                isActive = input.readBoolean()
            )
        }
    }

    private fun writeSecondaryOamList(out: DataOutput, list: List<SecondaryOamEntry>) {
        out.writeInt(list.size)
        for (e in list) {
            writeSpriteData(out, e.sprite)
            out.writeInt(e.tileY)
        }
    }

    private fun readSecondaryOamList(input: DataInput): List<SecondaryOamEntry> {
        val n = input.readInt()
        return List(n) {
            val data = readSpriteData(input)
            SecondaryOamEntry(data, input.readInt())
        }
    }

    private fun writeSpriteData(out: DataOutput, s: SpriteData) {
        out.writeInt(s.index)
        out.writeInt(s.y)
        out.writeByte(s.tileIndex.toInt())
        out.writeByte(s.attributes.toInt())
        out.writeInt(s.x)
    }

    private fun readSpriteData(input: DataInput): SpriteData {
        val index = input.readInt()
        val y = input.readInt()
        val tileIndex = input.readByte()
        val attributes = input.readByte()
        val x = input.readInt()
        return SpriteData(index, y, tileIndex, attributes, x)
    }
}


class ObjectAttributeMemory {


    private val memory = ByteArray(0x100)





    operator fun get(addr: Int): Byte = memory[addr and 0xFF]
    operator fun set(addr: Int, value: Byte) {
        val index = addr and 0xFF
        // Attribute bits 2-4 are not wired on the 2C02 and read back as zero.
        memory[index] = if ((index and 0x03) == 2) {
            (value.toUnsignedInt() and 0xE3).toSignedByte()
        } else value
    }

    /**
     * Get sprite data from OAM.
     * Each sprite occupies 4 bytes: Y, Tile, Attributes, X
     */
    fun getSprite(index: Int): SpriteData {
        val base = (index and 0x3F) * 4  // 64 sprites max, wrap at boundary
        return SpriteData(
            index = index,
            y = memory[base].toUnsignedInt(),
            tileIndex = memory[base + 1],
            attributes = memory[base + 2],
            x = memory[base + 3].toUnsignedInt()
        )
    }

    fun saveState(out: DataOutput) { out.write(memory) }
    fun loadState(input: DataInput) {
        input.readFully(memory)
        for (i in 2 until memory.size step 4) {
            memory[i] = (memory[i].toUnsignedInt() and 0xE3).toSignedByte()
        }
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
    val index: Int,       // 0-63, OAM index
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
class ActiveSprite(
    val data: SpriteData,
    val tileDataLow: Byte,    // Pattern table low byte (bit 0 plane)
    val tileDataHigh: Byte,   // Pattern table high byte (bit 1 plane)
    var shiftLow: Int = 0,    // 8-bit shift register, bits 7-0 = pixels 7-0
    var shiftHigh: Int = 0,   // 8-bit shift register
    var xCounter: Int = 0,    // Counts down from sprite X position to 0
    var isActive: Boolean = false  // True when xCounter reaches 0 (sprite is rendering)
)
