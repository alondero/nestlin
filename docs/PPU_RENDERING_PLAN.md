# PPU Rendering Implementation Plan (Priority 1)

## Goal
Implement basic background tile rendering in the PPU to display actual game graphics instead of random pixels.

## Background: How NES Tile Rendering Works

### The Rendering Pipeline
Each visible scanline (0-239) renders 256 pixels by:
1. **Fetching tile data** every 8 cycles (4 memory reads × 2 cycles each)
2. **Loading shift registers** with pattern/palette data
3. **Shifting and outputting pixels** every cycle

### The 4 Memory Fetches (per tile, 8 cycles total)
1. **Nametable byte** (cycle 0) - Which tile index to render (0-255)
2. **Attribute table byte** (cycle 2) - Which palette to use (0-3)
3. **Pattern table low byte** (cycle 4) - Bitmap data bit 0
4. **Pattern table high byte** (cycle 6) - Bitmap data bit 1

### How Pixels Are Formed
- Each pixel has 2 bits of color data (from pattern table lo/hi bytes)
- The 2-bit value (0-3) indexes into a 4-color palette
- The palette is determined by the attribute table byte
- The final color comes from palette RAM at $3F00-$3F1F

### Pattern Table Structure
- Each tile is 8×8 pixels = 16 bytes total
- Bytes 0-7: Low bit plane (bit 0 of each pixel)
- Bytes 8-15: High bit plane (bit 1 of each pixel)
- To get pixel X: `((highByte >> (7-x)) & 1) << 1 | ((lowByte >> (7-x)) & 1)`

### Attribute Table Structure
The attribute table is at nametable + $3C0 (64 bytes for 32×30 tile area).
Each byte controls a 4×4 tile region (2×2 metatiles):
```
Attribute Byte: 33221100
                ||||||++-- Palette for top-left 2×2 tiles
                ||||++---- Palette for top-right 2×2 tiles
                ||++------ Palette for bottom-left 2×2 tiles
                ++-------- Palette for bottom-right 2×2 tiles
```

Address calculation (from nesdev wiki):
```
address = 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07)
shift = ((v >> 4) & 4) | (v & 2)
palette = ((attributeByte >> shift) & 3) << 2
```

## Current State Analysis

### ✅ Already Implemented
- **Timing skeleton** (Ppu.kt:41-86) - Cycle/scanline tracking correct
- **VRAM address logic** (PpuAddressedMemory.kt:99-173) - Scroll registers working
- **Memory structures** - Pattern tables, nametables, palette RAM exist
- **Frame buffer** (Frame.kt) - Simple 2D array for RGB output
- **Nametable fetch** (Ppu.kt:140-143) - Reads nametable byte

### ❌ Missing/Broken
- **Shift registers** (Ppu.kt:23-30) - Defined as constants, should be mutable
- **Pattern table fetch** (Ppu.kt:156-161) - Completely empty
- **Attribute table fetch** (Ppu.kt:144-155) - Logic incomplete/wrong
- **Pixel rendering** (Ppu.kt:179-181) - Just random colors
- **Palette color lookup** - No NES color palette defined
- **Shift register loading** (Ppu.kt:66-69) - Bitmap never loaded
- **Pixel extraction** - No logic to shift and extract from registers

## Implementation Tasks

### Task 1: Define NES Color Palette
**File**: `ppu/NesPalette.kt` (new file)

Create the 64-color NES palette as RGB values. The NES has a fixed hardware palette.

```kotlin
object NesPalette {
    private val colors = intArrayOf(
        0x666666, 0x002A88, 0x1412A7, 0x3B00A4, // $00-$03
        0x5C007E, 0x6E0040, 0x6C0600, 0x561D00, // $04-$07
        // ... (complete 64 colors)
    )

    fun getRgb(index: Int): Int = colors[index and 0x3F]
}
```

**Reference**: https://www.nesdev.org/wiki/PPU_palettes

---

### Task 2: Fix Shift Registers
**File**: `ppu/Ppu.kt:23-30`

Replace constants with mutable variables and add palette shift registers:

```kotlin
// Pattern table shift registers (16-bit, hold 2 tiles worth of data)
private var patternShiftLow: Int = 0
private var patternShiftHigh: Int = 0

// Palette attribute shift registers (8-bit, hold next 8 pixels)
private var paletteShiftLow: Byte = 0
private var paletteShiftHigh: Byte = 0

// Latches (hold fetched data until time to load into shift registers)
private var patternLatchLow: Byte = 0
private var patternLatchHigh: Byte = 0
private var paletteLatch: Int = 0
```

---

### Task 3: Implement Pattern Table Fetch
**File**: `ppu/Ppu.kt:156-161`

Complete the pattern table fetches at cycles 4 and 6:

```kotlin
4 -> with(memory.ppuAddressedMemory) {
    // Fetch Low Tile Byte
    val patternTableBase = controller.backgroundPatternTableAddress()
    val tileIndex = lastNametableByte.toUnsignedInt()
    val fineY = vRamAddress.fineYScroll
    val address = patternTableBase + (tileIndex * 16) + fineY
    patternLatchLow = ppuInternalMemory[address]
}
6 -> with(memory.ppuAddressedMemory) {
    // Fetch High Tile Byte (8 bytes after low byte)
    val patternTableBase = controller.backgroundPatternTableAddress()
    val tileIndex = lastNametableByte.toUnsignedInt()
    val fineY = vRamAddress.fineYScroll
    val address = patternTableBase + (tileIndex * 16) + fineY + 8
    patternLatchHigh = ppuInternalMemory[address]
}
```

**Key Points**:
- Pattern table base comes from PPUCTRL bit 4 ($2000 register)
- Each tile is 16 bytes (8 for low plane + 8 for high plane)
- Fine Y scroll selects which row of the tile (0-7)

---

### Task 4: Fix Attribute Table Fetch
**File**: `ppu/Ppu.kt:144-155`

Replace the broken logic with correct attribute table addressing:

```kotlin
2 -> with(memory.ppuAddressedMemory) {
    // Fetch Attribute Table Byte
    val v = vRamAddress.asAddress()
    val attributeAddr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
    val attributeByte = ppuInternalMemory[attributeAddr].toUnsignedInt()

    // Extract the 2-bit palette for this tile
    val shift = ((v shr 4) and 4) or (v and 2)
    paletteLatch = ((attributeByte shr shift) and 0x03) shl 2

    lastAttributeTableByte = attributeByte.toSignedByte()
}
```

**Why this works**:
- Attribute table starts at nametable + $3C0
- Each byte covers 4×4 tiles (2×2 metatiles)
- Shift calculation determines which 2 bits to extract

---

### Task 5: Load Shift Registers Every 8 Cycles
**File**: `ppu/Ppu.kt:66-69`

Replace the stub with actual loading logic:

```kotlin
if (cycle % 8 == 0 && cycle >= 1 && cycle <= 256) {
    // Load the upper 8 bits of shift registers with fetched data
    patternShiftLow = (patternShiftLow and 0xFF) or (patternLatchLow.toUnsignedInt() shl 8)
    patternShiftHigh = (patternShiftHigh and 0xFF) or (patternLatchHigh.toUnsignedInt() shl 8)

    // Reload palette attribute shift registers
    paletteShiftLow = if ((paletteLatch and 0x01) != 0) 0xFF.toByte() else 0x00
    paletteShiftHigh = if ((paletteLatch and 0x02) != 0) 0xFF.toByte() else 0x00
}
```

**Note**: Cycles 9, 17, 25, ..., 249, 257 reload the shifters (right after fetch completes).

---

### Task 6: Implement Pixel Rendering
**File**: `ppu/Ppu.kt:179-181`

Replace random pixel generation with actual rendering:

```kotlin
if (scanline < RESOLUTION_HEIGHT && cycle >= 1 && cycle <= 256) {
    val x = cycle - 1

    // Shift registers every cycle
    if (cycle > 0) {
        patternShiftLow = patternShiftLow shl 1
        patternShiftHigh = patternShiftHigh shl 1
        paletteShiftLow = (paletteShiftLow.toInt() shl 1).toByte()
        paletteShiftHigh = (paletteShiftHigh.toInt() shl 1).toByte()
    }

    // Extract pixel from shift registers (bit 15 = current pixel)
    val patternBit0 = (patternShiftLow shr 15) and 1
    val patternBit1 = (patternShiftHigh shr 15) and 1
    val pixelValue = (patternBit1 shl 1) or patternBit0

    // Extract palette bits
    val paletteBit0 = (paletteShiftLow.toInt() shr 7) and 1
    val paletteBit1 = (paletteShiftHigh.toInt() shr 7) and 1
    val paletteIndex = (paletteBit1 shl 1) or paletteBit0

    // Look up color in palette RAM
    val paletteAddr = if (pixelValue == 0) {
        0x3F00 // Universal background color
    } else {
        0x3F00 + (paletteIndex shl 2) + pixelValue
    }

    val nesColorIndex = memory.ppuAddressedMemory.ppuInternalMemory[paletteAddr].toUnsignedInt()
    val rgbColor = NesPalette.getRgb(nesColorIndex)

    frame[scanline, x] = rgbColor
}
```

**Critical Details**:
- Shift happens BEFORE extracting pixel (or after, depending on timing model)
- Pixel value 0 always uses universal background color at $3F00
- Palette RAM is indexed as: $3F00 + (palette << 2) + pixelValue

---

### Task 7: Handle Fine X Scroll
**File**: `ppu/Ppu.kt` (modify pixel rendering)

The fine X scroll (0-7) shifts the rendering by up to 7 pixels:

```kotlin
// In pixel rendering section
val fineX = memory.ppuAddressedMemory.fineXScroll
val shiftAmount = 15 - fineX

val patternBit0 = (patternShiftLow shr shiftAmount) and 1
val patternBit1 = (patternShiftHigh shr shiftAmount) and 1
// ... rest of pixel extraction
```

---

### Task 8: Load CHR ROM into Pattern Tables
**File**: `Memory.kt` or `GamePak.kt`

Currently CHR ROM is loaded but may not be copied to PPU internal memory:

```kotlin
// In Memory.kt or GamePak initialization
fun loadChrRom(gamePak: GamePak) {
    gamePak.chrRom.copyInto(
        destination = ppuAddressedMemory.ppuInternalMemory.patternTable0,
        destinationOffset = 0,
        startIndex = 0,
        endIndex = minOf(0x1000, gamePak.chrRom.size)
    )

    if (gamePak.chrRom.size > 0x1000) {
        gamePak.chrRom.copyInto(
            destination = ppuAddressedMemory.ppuInternalMemory.patternTable1,
            destinationOffset = 0,
            startIndex = 0x1000,
            endIndex = minOf(0x2000, gamePak.chrRom.size)
        )
    }
}
```

**Note**: Need to expose pattern tables in PpuInternalMemory (currently private).

---

### Task 9: Enable Palette RAM Writes
**File**: `ppu/PpuAddressedMemory.kt:291-298`

Ensure PaletteRam has setter and handles mirroring:

```kotlin
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
        val mirroredIndex = if (index and 0x13 == 0x10) index and 0x0F else index
        memory[mirroredIndex] = value
    }
}
```

---

## Testing Strategy

### Phase 1: Single Tile Test
1. Manually set palette RAM to known colors
2. Manually set one nametable entry to tile $00
3. Manually set pattern table data for tile $00
4. Run and verify 8×8 tile appears at correct position

### Phase 2: nestest.nes Visual Test
1. Load nestest.nes ROM
2. Run emulator and visually compare to screenshots
3. Should see test result screen with pass/fail indicators

### Phase 3: Simple Game Test
1. Try a simple game like "Balloon Fight" or "Ice Climber"
2. Should see recognizable graphics (even if scrolling broken)
3. Verify no major graphical glitches

### Phase 4: Palette Test ROM
1. Use palette test ROM from nesdev
2. Verify all 64 colors display correctly
3. Check palette mirroring works

---

## Expected Outcomes

### What Will Work
✅ Static background tiles rendering
✅ Correct colors from palette RAM
✅ Basic scrolling (if game sets PPUSCROLL)
✅ Visible nestest.nes output

### What Won't Work Yet
❌ Sprites (Priority 3)
❌ Sprite 0 hit detection
❌ Mid-frame register changes (e.g., status bar tricks)
❌ Complex scrolling effects
❌ Games requiring mappers other than 0

---

## Implementation Order

1. **Task 1** - Define NES palette (standalone, no dependencies)
2. **Task 2** - Fix shift register declarations
3. **Task 8** - Ensure CHR ROM loads into pattern tables
4. **Task 9** - Enable palette RAM writes
5. **Task 3** - Implement pattern table fetch
6. **Task 4** - Fix attribute table fetch
7. **Task 5** - Load shift registers every 8 cycles
8. **Task 6** - Implement basic pixel rendering (without fine X)
9. **Task 7** - Add fine X scroll support
10. **Test** - Verify with nestest.nes and simple games

---

## Common Pitfalls to Avoid

### Timing Issues
- **Don't shift on wrong cycle** - Shift registers update every cycle during rendering
- **Load at right time** - Shifters reload at cycles 9, 17, 25, etc. (every 8 cycles)
- **Fetch in order** - NT → AT → PT-low → PT-high (cycles 0, 2, 4, 6)

### Bit Manipulation
- **Endianness** - Pattern table bit 7 is leftmost pixel, bit 0 is rightmost
- **Palette indexing** - Formula: $3F00 + (palette × 4) + pixelValue
- **Attribute shift** - Easy to get wrong; test carefully

### Memory Access
- **Pattern table base** - Check PPUCTRL bit 4 ($0000 or $1000)
- **Nametable mirroring** - Depends on cartridge (horizontal/vertical)
- **Palette mirroring** - Sprite palette $10/$14/$18/$1C mirrors to BG $00/$04/$08/$0C

### Edge Cases
- **Pixel 0** - Always uses universal background color ($3F00)
- **Fine Y scroll** - Must select correct row within 8×8 tile
- **Pre-render scanline** - Don't render, but do fetch for next frame

---

## Reference Materials

- [PPU Rendering - NESdev](https://www.nesdev.org/wiki/PPU_rendering)
- [PPU Scrolling - NESdev](https://www.nesdev.org/wiki/PPU_scrolling)
- [PPU Palettes - NESdev](https://www.nesdev.org/wiki/PPU_palettes)
- [PPU Pattern Tables - NESdev](https://www.nesdev.org/wiki/PPU_pattern_tables)
- [PPU Attribute Tables - NESdev](https://www.nesdev.org/wiki/PPU_attribute_tables)

---

## Success Criteria

The implementation is complete when:
1. ✅ nestest.nes displays recognizable text/graphics (not random pixels)
2. ✅ At least one simple game (e.g., Donkey Kong) shows proper background tiles
3. ✅ Colors match expected NES palette
4. ✅ No crashes or exceptions during rendering
5. ✅ Golden log test still passes (CPU not affected)
