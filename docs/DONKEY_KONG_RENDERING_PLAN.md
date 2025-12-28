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

---

## Session Handoff: Next Steps (Phase 1.7 - Pattern Table Address Bug Found)

### Current Status (Session ending 2025-12-28 - Round 3)

**Status: Pattern table address ranges bug identified and fixed. Rendering corruption likely caused by additional issues.**

#### What Was Fixed This Session
1. ✅ **Pattern table address ranges were incomplete** (PRIMARY BUG)
   - **Bug location**: PpuAddressedMemory.kt, PpuInternalMemory.get() operator (lines 312-313)
   - **Problem**: Ranges only covered 0x0000-0x0999 and 0x1000-0x1999
   - **Should be**: 0x0000-0x0FFF and 0x1000-0x1FFF (full 4KB each)
   - **Impact**: Addresses 0x0A00-0x0FFF and 0x1A00-0x1FFF returned garbage from palette RAM
   - **Fix**: Extended ranges to full 4KB and fixed indexing (removed unnecessary modulo operations)

#### Debugging Methodology
Used **systematic debugging** to find root cause:
1. ✅ **Phase 1: Root Cause Investigation**
   - Added diagnostic logging to preload function
   - Added shift register state logging
   - Added pixel extraction logging
   - Verified nametable was loading valid tile indices
   - Verified CHR ROM loaded 8192 bytes correctly into both pattern tables
   - Discovered addresses being accessed fell outside defined ranges

2. ✅ **Phase 2-3: Pattern Analysis & Hypothesis**
   - Traced address calculations (patternTableBase + tileIndex*16 + fineY)
   - Found the range definitions were incomplete
   - Confirmed fix would allow full pattern table access

3. ✅ **Phase 4: Implementation**
   - Fixed address ranges in PpuAddressedMemory.kt
   - Removed diagnostic logging
   - All tests pass

#### Evidence Gathered
- Donkey Kong CHR ROM: 8192 bytes loaded correctly
- Pattern table 0 (0x0000-0x0FFF): First tile = 0x00/0x00
- Pattern table 1 (0x1000-0x1FFF): First tile = 0x38/0x00
- Preload is being called every scanline
- Tile addresses calculated: 0x1120, 0x1128 for tile index 0x12
- These addresses are NOW within corrected pattern table ranges

#### What Remains Unknown
Despite the pattern table address fix, rendering still appears corrupt. This suggests:
1. **Possible secondary bugs still present** (most likely)
   - Palette data still not loading correctly (palette shift registers always 0x00)
   - Fine X scroll extraction might still be wrong
   - Attribute table addressing formula might be incorrect
   - Nametable addressing might have issues

2. **What we need to verify next**:
   - Are palette shift registers getting loaded with valid data?
   - Is palette attribute calculation correct?
   - Is fine X scroll being applied correctly?
   - Are we reading from the right palette table entries?

### Next Session Investigation Priority

**Focus: Palette data and pixel color lookup pipeline**

The pattern table bug fix was necessary but not sufficient. The rendering corruption persists because:
- Palette shift registers stay at 0x00 (seen in diagnostics)
- This means all pixels use palette index 0 (no variation between tiles)
- Need to verify palette fetch and reload cycle

**Recommended Debugging Path**:
1. Add detailed logging to palette fetch cycle (cycle % 8 == 3)
2. Log attribute table reads and palette bit extraction
3. Verify palette shift register reloads at cycles 9, 17, 25, ...
4. Verify fine X scroll is extracting from correct bit positions
5. Test with test ROM that has multiple tile palettes to see variation

**Key Code Locations to Re-examine**:
| Component | File | Issue |
|-----------|------|-------|
| Palette fetch | Ppu.kt:306-307 | Might not be saving to paletteLatch correctly |
| Palette reload | Ppu.kt:100-101 | Reload logic at cycles 9,17,25... |
| Fine X scroll | Ppu.kt:368-370 | Extraction of palette bits with fine X |
| Attribute table | Ppu.kt:249-253 | Formula correctness |

### Testing Notes
- ✅ Golden log test still passes (CPU accuracy maintained)
- ✅ All unit tests pass
- ⚠️ Rendering still shows corruption (pattern table bug fix alone insufficient)
- 📋 Need visual test with known good Donkey Kong screenshot for comparison

### Commits History This Session
- **(This session)**: Fix pattern table address ranges in PpuInternalMemory
- **5dfb49f**: Fix shift register preload address handling
- **bea2adb**: Critical PPU rendering bug fixes (5 major bugs)
