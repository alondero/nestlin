# NES Sprite Rendering Implementation Plan (Minimal Path)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement 8×8 sprite rendering with up to 8 sprites per scanline, transparency support, and sprite/background priority.

**Architecture:** Minimal implementation fetches active sprites per scanline by checking all 64 OAM entries. For each sprite on the current scanline, fetch tile data and render with priority support. Sprites render on top of or behind background based on priority bit.

**Tech Stack:** Kotlin, NES OAM at $200-$2FF, sprite pattern tables, PPU cycle rendering

---

## Task 1: Define Sprite Data Structures

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` (lines 246-273)

**Step 1: Update ObjectAttributeMemory class**

Replace the stub with full OAM implementation:

```kotlin
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
    val paletteIndex: Int get() = (attributes and 0x03).toInt()
    val priority: Int get() = (attributes.toInt() shr 5) and 0x01  // 0=in front, 1=behind
    val horizontalFlip: Boolean get() = (attributes.toInt() shr 6) and 0x01 != 0
    val verticalFlip: Boolean get() = (attributes.toInt() shr 7) and 0x01 != 0
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
```

**Step 2: Run build to verify syntax**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "feat: add sprite data structures (OAM, SpriteData, ActiveSprite)"
```

---

## Task 2: Add OAM Access to Memory Class

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/Memory.kt`

**Step 1: Check current Memory structure**

Read lines 1-50 to understand the memory map:

```bash
grep -n "class Memory" src/main/kotlin/com/github/alondero/nestlin/Memory.kt
```

**Step 2: Add OAM property to Memory class**

After the existing PPU memory initialization (around line 20), add:

```kotlin
private val oam = ObjectAttributeMemory()
```

And add a public property to access it:

```kotlin
fun getOAM(): ObjectAttributeMemory = oam
```

**Step 3: Wire OAM into CPU address space ($200-$2FF)**

Find the `operator fun set(addr: Int, value: Byte)` method and add OAM writes:

```kotlin
in 0x0200..0x02FF -> oam[addr - 0x0200] = value
```

And in `operator fun get(addr: Int): Byte`, add OAM reads:

```kotlin
in 0x0200..0x02FF -> oam[addr - 0x0200]
```

**Step 4: Build and test**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 5: Run golden log test to ensure CPU still works**

```bash
./gradlew test --tests GoldenLogTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (all tests pass)

**Step 6: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/Memory.kt
git commit -m "feat: add OAM access to Memory class ($200-$2FF)"
```

---

## Task 3: Add Sprite Fetching Logic to PPU

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` (add new method and field)

**Step 1: Add active sprite buffer field**

In the Ppu class (after the palette shift register definitions around line 33), add:

```kotlin
// Active sprites for current scanline (max 8)
private val activeSpriteBuffer = mutableListOf<ActiveSprite>()
```

**Step 2: Implement sprite fetching function**

Add this new function to the Ppu class (after the `fetchData()` method, around line 240):

```kotlin
/**
 * Fetch active sprites for the current scanline.
 * Simple approach: check all 64 sprites, add to buffer if Y matches.
 */
private fun fetchActiveSpriteDataForScanline(scanline: Int) {
    activeSpriteBuffer.clear()

    // Check all 64 sprites in OAM
    for (i in 0 until 64) {
        if (activeSpriteBuffer.size >= 8) break  // Max 8 sprites per scanline

        val spriteData = memory.getOAM().getSprite(i)
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

            activeSpriteBuffer.add(ActiveSprite(
                data = spriteData,
                tileDataLow = tileDataLow,
                tileDataHigh = tileDataHigh
            ))
        }
    }
}
```

**Step 3: Build and verify**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "feat: add sprite fetching logic for active scanline sprites"
```

---

## Task 4: Integrate Sprite Fetching into Tick Loop

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` (lines 44-96)

**Step 1: Call fetchActiveSpritesForScanline at start of scanline**

In the `tick()` method, find where a new scanline starts (after `endLine()` returns or at scanline change). Add this right after scanline increments (around line 121 in `endLine()`):

Actually, we need to fetch sprites at the START of rendering phase (cycle 0 of each scanline). Modify the `tick()` method's rendering check:

```kotlin
if (rendering()) {
    // Fetch sprites for this scanline at cycle 0
    if (cycle == 0) {
        fetchActiveSpriteDataForScanline(scanline)
    }

    // Fetch tile data (existing code)
    when (cycle) {
        in 1..256 -> fetchData()
        in 257..320 -> fetchSpriteTile()
        in 321..336 -> fetchData()
    }
    checkAndSetVerticalAndHorizontalData()
}
```

**Step 2: Build and verify**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 3: Run golden log test**

```bash
./gradlew test --tests GoldenLogTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "feat: integrate sprite fetching into PPU tick loop"
```

---

## Task 5: Implement Sprite Shift and Rendering

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` (in `fetchData()` method)

**Step 1: Initialize sprite shifts when sprites are fetched**

In `fetchActiveSpriteDataForScanline()`, after creating each ActiveSprite, initialize the shifts:

```kotlin
// Load shift registers with tile data
val activeSprite = ActiveSprite(
    data = spriteData,
    tileDataLow = tileDataLow,
    tileDataHigh = tileDataHigh
)

// Initialize shift registers with tile data, apply horizontal flip
if (spriteData.horizontalFlip) {
    // If flipped, shift data right (reverse bit order)
    activeSprite.shiftLow = reverseBits(tileDataLow.toUnsignedInt() and 0xFF)
    activeSprite.shiftHigh = reverseBits(tileDataHigh.toUnsignedInt() and 0xFF)
} else {
    // Normal: shift left to access MSB first
    activeSprite.shiftLow = tileDataLow.toUnsignedInt() shl 0
    activeSprite.shiftHigh = tileDataHigh.toUnsignedInt() shl 0
}

activeSpriteBuffer.add(activeSprite)
```

Add the bit reversal helper function to Ppu class:

```kotlin
private fun reverseBits(value: Int): Int {
    var result = 0
    var v = value
    for (i in 0..7) {
        result = (result shl 1) or (v and 1)
        v = v shr 1
    }
    return result
}
```

**Step 2: Shift all active sprites every cycle**

In the pixel rendering section of `fetchData()`, after background shifting (line 208), add:

```kotlin
// Shift active sprite registers
activeSpriteBuffer.forEach { sprite ->
    sprite.shiftLow = sprite.shiftLow shl 1
    sprite.shiftHigh = sprite.shiftHigh shl 1
}
```

**Step 3: Build and verify**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "feat: add sprite shift registers and shifting logic"
```

---

## Task 6: Render Sprites with Priority

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` (in pixel rendering section)

**Step 1: Modify pixel rendering to check sprites**

In `fetchData()`, replace the final pixel rendering section (lines 225-236) with:

```kotlin
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
    // Calculate sprite X position relative to current pixel
    val spritePixelX = cycle - sprite.data.x

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

frame[scanline, x] = rgbColor
```

**Step 2: Build and verify**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 3: Run tests**

```bash
./gradlew test --tests GoldenLogTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "feat: implement sprite rendering with priority support"
```

---

## Task 7: Manual Testing with Nestest.nes

**Files:**
- None

**Step 1: Build the project**

```bash
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 2: Run with nestest.nes (visual inspection)**

```bash
./gradlew run --args="testroms/nestest.nes" &
```

This will launch the emulator. Visually inspect that:
- Background rendering still works (text visible)
- No crashes or black screen
- Colors are correct

**Step 3: Run golden log test**

```bash
./gradlew test --tests GoldenLogTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, CPU execution matches golden log

**Step 4: No commit yet** - Wait for issues to surface

---

## Task 8: Fix Sprite X Positioning

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt`

**Issue:** Sprite X positioning may be off due to how we calculate `spritePixelX`.

**Fix:** In sprite rendering loop, adjust:

```kotlin
// Sprite rendering happens at the PIXEL position, which is (cycle - 1)
// But we need to check against sprite X position
val pixelX = cycle - 1  // 0-255 for screen
val spriteScreenX = sprite.data.x
val spritePixelX = pixelX - spriteScreenX

// Only render if within sprite bounds
if (spritePixelX < 0 || spritePixelX >= 8) continue
```

**Step 1: Update sprite rendering**

Replace the sprite X check with above code.

**Step 2: Build and test**

```bash
./gradlew build && ./gradlew test --tests GoldenLogTest 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt
git commit -m "fix: correct sprite X positioning calculation"
```

---

## Testing Strategy

### Phase 1: Unit Level
- Build succeeds
- Golden log test passes (CPU unaffected)

### Phase 2: Visual Level
- nestest.nes displays without crashing
- Background still visible

### Phase 3: Integration Level
- Try Donkey Kong ROM (if available) - should show sprites
- Verify sprite/background layering works

### Success Criteria
- ✅ Build succeeds
- ✅ Golden log test passes
- ✅ No crashes with nestest.nes
- ✅ At least one game ROM shows sprites

---

## Known Limitations (Minimal Path)

This implementation does NOT include:
- ❌ Tall sprites (8×16 mode)
- ❌ Sprite 0 hit detection
- ❌ Sprite overflow flag
- ❌ Accurate sprite evaluation timing
- ❌ Sprite priorities with same X (hardware quirks)

These can be added in Priority 4 if needed.
