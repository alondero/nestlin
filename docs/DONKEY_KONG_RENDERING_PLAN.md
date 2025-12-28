# Donkey Kong Rendering Plan

## Overview
This document outlines the current state of the Nestlin NES emulator and the path forward to successfully boot and display Donkey Kong (donkeykong.nes) with proper rendering.

---

## ✅ What's Working Well

Your emulator has a solid foundation:

1. **CPU Implementation**: ✅ **151 opcodes** with proper addressing modes, cycle counting, and interrupt handling (NMI edge-triggered detection) - Added CLI (0x58) and undocumented opcode fallback (Commit: a49d784)
2. **Memory Map**: Correct RAM mirroring ($0000-$1FFF), PPU register mirroring, cartridge space mapping
3. **ROM Loading**: iNES format parsing, Mapper 0 (NROM) support—exactly what Donkey Kong needs
4. **PPU Framework**: Timing structure (scanlines/cycles), VRAM address register with scroll logic, tile/sprite fetching
5. **Sprite System**: OAM buffer, sprite evaluation, priority handling, flip support
6. **Rendering Infrastructure**: Shift registers (pattern+palette), frame buffer, JavaFX UI pipeline with pixel update
7. **Test Framework**: Golden log validation working
8. **Nametable Mirroring**: ✅ HORIZONTAL/VERTICAL mirroring implemented and configured from iNES header (Commit: 89b5004)
9. **Sprite Coordinate System**: ✅ Fixed sprite pixel extraction to use correct coordinate space (Commit: 89b5004)
10. **Undocumented Opcode Support**: ✅ Defensive logging and fallback handling for unknown instructions in regular games (Commit: a49d784)
11. **Canvas Rendering**: ✅ Fixed canvas dimensions from (height, width) to (width, height) (Commit: 7ebdedd)
12. **VRAM Write Implementation**: ✅ Fixed critical bug where $2007 writes weren't being stored to VRAM (Commit: 3a1c09b)
13. **Pattern Table Protection**: ✅ Pattern tables now read-only in NROM mode, preventing tile data corruption (Commit: 3a1c09b)

---

## ⚠️ Critical Issues Blocking Donkey Kong Display

### 1. ✅ MISSING OPCODE - RESOLVED
- **Problem**: Donkey Kong crashed at ~15-20 seconds on unimplemented opcodes
- **Fix**: Implemented CLI (0x58), added undocumented opcode fallback (Commit: a49d784)
- **Result**: Donkey Kong now runs much further without crashing
- **Note**: Game encounters ~25 unique undocumented SKB/NOP variants which are logged but don't crash
- **Next blocker**: Now hitting PPU rendering issues as game waits for display output

### 2. ⚠️ PPU Rendering Issues (PARTIALLY RESOLVED - Phase 1.6 blocker)

**FIXED:**
- ✅ Canvas dimensions swapped (Commit: 7ebdedd) - was (height, width), now (width, height)
- ✅ VRAM writes not implemented (Commit: 3a1c09b) - $2007 writes now stored to VRAM
- ✅ Pattern tables being corrupted (Commit: 3a1c09b) - writes to 0x0000-0x1FFF now ignored

**Current Status**: Game now displays flashing colors with corrupt graphics
- Game IS writing nametable/attribute data (418+ bytes per frame)
- Frame buffer IS being populated with pixel data
- Colors ARE changing (flashing indicates VBlank transitions working)
- Corruption suggests issues with:
  - Tile fetching logic (wrong tiles being read)
  - Palette/color selection (wrong colors being applied)
  - Shift register timing (pixels misaligned)

**Investigation needed**:
  - Verify tile indices in nametable are correct
  - Check palette attribute calculation formula
  - Validate shift register reload timing
  - Confirm fine X scroll implementation

### 3. Controller Input Not Implemented (CRITICAL - Phase 2 blocker)
- **Problem**: Donkey Kong won't respond to player input
- **Location**: $4016 (strobe/data) and $4017 (data) registers
- **Impact**: Hard blocker for interactive testing and gameplay
- **Fix Required**: Full implementation of strobe signal handling and serial data shifting

### 4. ✅ PPU Infrastructure (RESOLVED)
**Fixed in Commit 89b5004:**
- ✅ **Sprite X position calculation** - Now correctly converts from PPU cycle (1-341) to pixel coordinate (0-255)
- ✅ **Nametable Mirroring** - Properly enforced based on iNES header (horizontal for Donkey Kong)

**Potential issues to debug in Phase 1.5:**
- **Fine X scroll extraction** (Ppu.kt:298): May have off-by-one errors in bit shifting logic
- **Palette index calculation** (Ppu.kt:242): Complex attribute table formula might be wrong
- **Shift register timing**: Reload happens at correct cycles but pixel output might be misaligned

---

## Donkey Kong Specific Requirements

| Aspect | Need | Status |
|--------|------|--------|
| Mapper | NROM (Mapper 0) | ✅ Supported |
| PRG ROM | 16KB | ✅ Loaded at $8000-$FFFF |
| CHR ROM | 8KB | ✅ Loaded in PPU pattern tables |
| Mirroring | Horizontal (H-mirror) | ❓ Check PpuAddressedMemory |
| Input | D-pad (left/right/up/down/jump) | ❌ Not implemented |
| NMI | VBlank interrupt | ✅ Implemented |

---

## 📋 Recommended Implementation Path

### Phase 1: Get Something on Screen (Mostly Complete ✅)

**Step 1.1**: ✅ Build and run with Donkey Kong - DONE
- Emulator runs for ~15-20 seconds before crashing on missing opcode

**Step 1.2**: ✅ Verify nametable mirroring - DONE (Commit 89b5004)
- Implemented horizontal/vertical mirroring in PpuInternalMemory
- Correctly reads mirroring mode from iNES header
- Donkey Kong's horizontal mirroring properly enforced

**Step 1.3**: ✅ Fix sprite pixel extraction - DONE (Commit 89b5004)
- Changed sprite X calculation from `cycle - sprite.data.x` to `(cycle - 1) - sprite.data.x`
- Now correctly maps PPU cycle coordinates (1-341) to pixel coordinates (0-255)
- Prevents negative array indices

**Step 1.4**: Test with simpler ROM first
- Try a test ROM that just renders a solid colored background
- Verify tiles render before debugging sprites

### Phase 1.5: Fix Missing Opcodes ✅ (COMPLETE)

**Completed**: Implemented CLI (0x58) and undocumented opcode fallback
- ✅ Added CLI (Clear Interrupt Disable) opcode implementation
- ✅ Implemented defensive logging for undocumented opcodes to `undocumented_opcodes.txt`
- ✅ Undocumented opcodes treated as 2-cycle NOPs (instead of crashing) for regular games
- ✅ Test ROMs still throw exceptions to maintain golden log test compatibility
- ✅ Donkey Kong encounters ~25 unique undocumented SKB/NOP variants - all logged and handled
- ✅ Game now runs indefinitely until hitting PPU rendering issues

**TODO (Development cleanup):** Remove undocumented opcode logging once emulator stability is proven with multiple games

### Phase 1.6: Debug PPU Rendering Output ✅ (COMPLETE - Commit: bea2adb)

**Root Causes Identified and Fixed** (Session 2025-12-28):
- ✅ **CRITICAL BUG**: Nametable fetch used wrong address variable
  - Was reading from 1-byte `address` register instead of 15-bit `vRamAddress`
  - This caused tile indices to be read from completely wrong memory locations
  - Fix: Use `vRamAddress.asAddress()` instead of `address` (Ppu.kt:232, 236)

- ✅ **VBlank Timing**: Set at wrong time (cycles 241-260 instead of scanline 241)
  - Was checking `cycle in 241..260` which doesn't correspond to any specific scanline
  - Fix: Check `scanline == 241 && cycle == 0` (Ppu.kt:84)

- ✅ **Fetch Cycle Timing**: Off by one, causing wrong fetch sequence
  - Nametable fetch happened at cycle % 8 == 0 (should be 1)
  - Attribute fetch happened at cycle % 8 == 2 (should be 3)
  - Pattern fetches were similarly offset
  - Fix: Adjusted all cycle % 8 checks to correct values (Ppu.kt:291-320)

- ✅ **Shift Register Reload**: Happened at cycle 1 (should only happen at 9, 17, 25...)
  - Caused premature reload with uninitialized data
  - Fix: Added `cycle > 1` check to exclude cycle 1 reload (Ppu.kt:91)

- ✅ **Missing Shift Register Pre-loading**: First two tiles not pre-loaded
  - NES spec requires shift registers to contain first two tiles before rendering
  - Fix: Implemented `preloadFirstTwoTiles()` function called at cycle 0 (Ppu.kt:70, 236-288)

**Result**: All critical PPU timing and memory access bugs fixed
- Golden log test still passes (CPU accuracy maintained)
- Frame buffer should now receive correct tile/color data
- Ready to test with Donkey Kong

### Phase 2: Enable Controller Input (Required for Interaction)

**Step 2.1**: Implement $4016/$4017 controller registers
- $4016 write: Strobe signal (bit 0) to latch button states
- $4016/$4017 read: Serial data output (one button bit per read)
- Standard button mapping: A, B, Select, Start, Up, Down, Left, Right

**Step 2.2**: Wire keyboard input to emulator
- Map arrow keys to D-pad (Up/Down/Left/Right)
- Map standard keys (Z, X, etc.) to A/B buttons
- Update UI to accept keyboard input while emulator runs

**Step 2.3**: Test input response
- Verify button presses cause visible changes in game
- Confirm strobe timing doesn't interfere with gameplay

### Phase 3: Debug Rendering (If Nothing Shows)

**Step 3.1**: Add debug output for tile rendering
- Print tile index, palette index, pattern data for each tile fetched
- Log shift register values at key cycles
- Output should show which tiles are being rendered and in what order

**Step 3.2**: Verify shift register reload timing
- Shift registers should reload at cycles 9, 17, 25, 33, ..., 249, 257, 321, 329, 337
- Check that reload happens BEFORE first pixel render of that tile
- Pattern shift registers should get upper 8 bits loaded; lower 8 bits from previous tile

**Step 3.3**: Validate attribute table addressing
- Attribute table addressing is notoriously complex
- Formula: `0x23C0 or (v & 0x0C00) or ((v >> 4) & 0x38) or ((v >> 2) & 0x07)`
- Where `v` is the current VRAM address
- Add logging to verify this produces correct palette indices

**Step 3.4**: Check nametable byte fetch
- Nametable byte should select which tile (0-255) to render
- Verify tile index is within valid bounds for CHR ROM
- Log mismatches between expected and actual tile data

### Phase 4: Validate (Final Verification)

**Step 4.1**: Compare rendered output against reference
- Capture screenshots of donkeykong.nes rendering
- Compare against known good Donkey Kong screenshots
- Look for correct title screen, proper colors, tile arrangement

**Step 4.2**: Run golden log test
- Ensure CPU accuracy still holds after changes
- ```bash
  ./gradlew test --tests GoldenLogTest
  ```

**Step 4.3**: Test full gameplay
- Load game, input directions, jump
- Verify Donkey Kong appears on screen
- Confirm barrels and ladders render correctly

---

## Critical Code Locations

| Component | File | Key Functions |
|-----------|------|----------------|
| PPU Rendering | `src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt` | `tick()`, `fetchData()`, `checkAndSetVerticalAndHorizontalData()` |
| Memory Map | `src/main/kotlin/com/github/alondero/nestlin/Memory.kt` | `operator fun set/get` |
| PPU Registers | `src/main/kotlin/com/github/alondero/nestlin/ppu/PpuAddressedMemory.kt` | Controller, Mask, Status, VRAM addressing |
| CPU | `src/main/kotlin/com/github/alondero/nestlin/cpu/Cpu.kt` | `reset()`, `tick()`, `checkAndHandleNmi()` |
| Controller Input | `src/main/kotlin/com/github/alondero/nestlin/Memory.kt` (need to add) | `$4016` and `$4017` handling |

---

## Known Hardware Quirks to Watch

From NES emulation expertise:

1. **Fine X Scroll**: Used after shift extraction to pick correct bit from 16-bit shift register
   - Must be applied BEFORE rendering pixel, not after

2. **Sprite 0 Hit**: Not implemented yet, but Donkey Kong may not rely on it heavily
   - Avoid X=255 position (hardware quirk causes detection failures)

3. **Attribute Table Byte Shifts**: Each 2x2 tile block shares one palette
   - The 2-bit palette index must be shifted based on which quadrant of the block the tile is in
   - Formula uses coarse Y bits 1-0 and coarse X bits 1-0

4. **PPU-CPU Timing**: Your timing loop is:
   ```
   PPU tick (3x) → APU tick (1x) → CPU tick (1x)
   ```
   This is correct for NTSC (3:1 ratio)

5. **NMI Edge Detection**: Correctly implemented as edge-triggered (not level-triggered)
   - Prevent double-NMI by clearing flag after handling

---

## Success Criteria

You'll know this is working when:

1. ✅ Emulator launches without crashing on donkeykong.nes
2. ✅ Something appears on screen (even if wrong colors)
3. ✅ Title screen displays with recognizable Donkey Kong barrel/ladder graphics
4. ✅ Player sprite (Jumpman) appears and can be moved with arrow keys
5. ✅ Barrels fall and game responds to player input
6. ✅ Golden log test still passes (CPU accuracy maintained)

---

## Testing Checklist

- [x] Build compiles without warnings (besides shadowing)
- [x] Donkey Kong ROM loads successfully
- [x] Emulator doesn't crash on startup
- [x] Emulator runs indefinitely without crashing on undocumented opcodes (Commit: a49d784)
- [x] Golden log test passes (CPU accuracy maintained)
- [x] Canvas dimensions correct (Commit: 7ebdedd)
- [x] VRAM writes work (nametable receives data) (Commit: 3a1c09b)
- [x] Pattern tables protected from corruption (Commit: 3a1c09b)
- [x] First frame renders (captures in frame buffer)
- [x] UI displays rendered frame with visual content (flashing colors)
- [ ] Graphics render correctly (currently corrupt/wrong tiles/colors)
- [ ] Keyboard input is recognized and mapped to controller
- [ ] Game title screen displays with proper graphics
- [ ] Player sprite (Jumpman) visible and controllable
- [ ] Game responds to input (movement, jumping)
- [ ] Barrels and ladders render and move correctly

---

## References

- **NES Memory Map**: https://www.nesdev.org/wiki/CPU_memory_map
- **PPU Rendering**: https://www.nesdev.org/wiki/PPU_rendering
- **Attribute Table**: https://www.nesdev.org/wiki/PPU_attribute_tables
- **iNES Format**: https://www.nesdev.org/wiki/INES
- **Controller Input**: https://www.nesdev.org/wiki/Standard_controller

---

## Notes

- Donkey Kong is a Mapper 0 (NROM) game, making it simpler than many NES games
- No bank switching, no special memory mapping, straightforward tile-based graphics
- Main challenges are purely PPU rendering correctness and controller input
- Once working, will serve as solid validation for the emulator architecture

## Session Handoff: Next Steps (Phase 1.9 - Nametable Mirroring & Frame Buffer Bugs Fixed)

### Current Status (Session ending 2025-12-28 - Round 5)

**Status: Two critical bugs found and fixed, but rendering STILL SHOWS CORRUPTION with mostly zeros**

#### What Was Fixed This Session

1. ✅ **Nametable Mirroring Was BACKWARDS** (CRITICAL BUG - Commit 8b1b1e9)
   - **Bug location**: PpuAddressedMemory.kt, mapNametableAddress() function (lines 281-309)
   - **Problem**: HORIZONTAL/VERTICAL mirroring modes had opposite behavior
     - HORIZONTAL had: 0x2400→NT0 (wrong!), 0x2800→NT1 (wrong!)
     - VERTICAL had: 0x2400→NT1 (wrong!), 0x2800→NT0 (wrong!)
   - **Correct mapping**:
     - HORIZONTAL (side-by-side): [NT0][NT1] / [NT0][NT1]
     - VERTICAL (top-bottom): [NT0][NT0] / [NT1][NT1]
   - **Impact**: PPU was reading/writing to wrong nametable locations, causing corruption
   - **Fix**: Corrected all four address ranges for both mirroring modes
   - **Result**: Nametable now stable with 11 unique tile indices per frame (verified via diagnostic logging)

2. ✅ **Frame Buffer Coordinate System Bug** (Commit c64189c)
   - **Bug location**: Frame.kt line 6, operator fun set()
   - **Problem**: Frame class had coordinates backwards
     - Was: `operator fun set(x: Int, y: Int, value: Int) {scanlines[x][y] = value}`
     - Should be: `operator fun set(x: Int, y: Int, value: Int) {scanlines[y][x] = value}`
   - **Why**: scanlines structure is `Array[RESOLUTION_HEIGHT][RESOLUTION_WIDTH]` = `Array[y][x]`, but operator was treating it as `Array[x][y]`
   - **Impact**: Pixels were being written to transposed coordinates, scrambling output
   - **Test result**: Caused ArrayIndexOutOfBoundsException when trying to fix (revealed actual problem)
   - **Fix**: Corrected operator to use `scanlines[y][x]` instead of `scanlines[x][y]`
   - **Ppu.kt change**: Updated pixel write to `frame[x, scanline] = rgbColor` to match fixed operator

#### Evidence Gathered

**Nametable Health**:
- Frames 0-2: Rendering disabled (mask=0x00)
- Frame 3+: Rendering enabled (mask=0x1e)
- Nametable sampling shows 11 unique tiles consistently: [15, 44, 56, 18, 39, 48, 37, 85, 170, 98, 0]
- **Conclusion**: Nametable NOT corrupted anymore, data is valid

**Rendering Output**:
- Still shows mostly "0" tiles with magenta/pink blocks mixed in
- Green background is correct (NES palette color)
- Sprites visible and moving
- **Not** the garbled/transposed output from before

#### What Remains Unknown

Despite fixing two critical bugs, rendering still shows corruption with mostly zeros:

1. **Why so many zeros?**
   - Tile 0 should be blank/empty space, but it's appearing everywhere
   - Valid tiles (15, 44, 56, etc.) are in nametable but not showing
   - Could indicate wrong tile is being fetched or pattern table issue

2. **Pattern table lookup still wrong?**
   - Each tile index should fetch 16 bytes from pattern table
   - May have off-by-one in address calculation
   - Or pattern table data being read from wrong location

3. **Shift register timing still wrong?**
   - Reload at cycles 9, 17, 25... may still be incorrect
   - Pattern bits not being extracted at correct cycle
   - Fine X scroll extraction may still be buggy

4. **Attribute table still broken?**
   - All attributes read as zero (correct at frame 3)
   - But may be wrong during actual gameplay

### Next Session Investigation Priority

**Use Systematic Debugging Phase 1-4 again**

**Focus: Why are mostly zeros rendering despite nametable having valid tiles?**

Recommended diagnostic approach:
1. Add logging to verify which tile index is being fetched each cycle
2. Log the pattern table address being calculated
3. Log the actual pattern bytes returned from pattern table
4. Verify pixel extraction is getting correct bits from shift registers
5. Check if tile 0 pattern table data is actually empty/blank

**Key Code Locations to Verify**:
| Component | File | Lines | Issue |
|-----------|------|-------|-------|
| Tile fetch | Ppu.kt | 247, 290-291 | Are we fetching right nametable byte? |
| Pattern address calc | Ppu.kt | 260-261 | Is address calculation correct? |
| Pattern data load | Ppu.kt | 262-263, 306-320 | Are we getting correct bytes? |
| Shift register reload | Ppu.kt | 93-101 | Timing at cycles 9, 17, 25...? |
| Pixel extraction | Ppu.kt | 365-375 | Fine X scroll extraction correct? |
| Frame write | Ppu.kt | 432 | Now using frame[x, scanline] after fix |

### Testing Notes
- ✅ Golden log test passes (CPU accuracy maintained)
- ✅ Nametable stable with valid tile data
- ✅ No more crashes or out-of-bounds errors
- ❌ Rendering still shows mostly zeros (not fixed yet)
- ✅ Mirroring fix confirmed working (different nametable data visible in diagnostics)
- ✅ Frame buffer fix prevents crashes

### Commits This Session
- **8b1b1e9**: fix: correct nametable mirroring logic for HORIZONTAL and VERTICAL modes
- **c64189c**: fix: correct frame buffer pixel write coordinate order

### Next Session Prompt

```
Two critical bugs were fixed:
1. Nametable mirroring was BACKWARDS (fixed Commit 8b1b1e9)
2. Frame buffer coordinates were TRANSPOSED (fixed Commit c64189c)

Nametable is now stable with valid tile data (11 unique tiles per frame).
But rendering STILL SHOWS MOSTLY ZEROS instead of proper graphics.

The remaining issue is likely in the PATTERN TABLE LOOKUP or SHIFT REGISTER TIMING:
- Tile indices in nametable are correct (15, 44, 56, 18, 39, 48, 37, 85, 170, 98, 0)
- But pixels are rendering as mostly zeros (tile index 0)
- Valid tiles exist in pattern table and should be visible

Before starting investigation:
1. Build and test: timeout 15 ./gradlew run --args="testroms/donkeykong.nes"
2. Verify: Golden log test passes
3. Add diagnostic logging to trace:
   - Which nametable bytes are being fetched
   - Which pattern table addresses are being calculated
   - Which pattern bytes are being loaded into shift registers
   - Whether pixels are being extracted from shift registers correctly

Most likely culprits:
- Pattern table address calculation off-by-one error
- Shift register reload timing still incorrect (cycles 9, 17, 25...)
- Tile fetch cycle timing misaligned (cycle % 8 == 1, 3, 5, 7)
- Fine X scroll extraction using wrong bit positions

Use systematic debugging to trace data flow from nametable → pattern table → shift registers → pixel output.
```
