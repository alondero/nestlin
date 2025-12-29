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
14. **CHR ROM Mirroring**: ✅ Small cartridges (8KB CHR) now correctly mirror data across both pattern tables (Commits: a551003, a24076a)
15. **Nametable Address Calculation**: ✅ PPU now uses vRamAddress bits instead of CTRL bits for nametable selection (Commit: 06218b5)
16. **PPU Diagnostics**: ✅ Comprehensive --ppu-diag logging for tracing tile fetches, pattern data, and pixel output

---

## ⚠️ Critical Issues Blocking Donkey Kong Display

### 1. ✅ MISSING OPCODE - RESOLVED
- **Problem**: Donkey Kong crashed at ~15-20 seconds on unimplemented opcodes
- **Fix**: Implemented CLI (0x58), added undocumented opcode fallback (Commit: a49d784)
- **Result**: Donkey Kong now runs much further without crashing
- **Note**: Game encounters ~25 unique undocumented SKB/NOP variants which are logged but don't crash
- **Next blocker**: Now hitting PPU rendering issues as game waits for display output

### 2. ⚠️ PPU Rendering Issues (CRITICAL PROGRESS - Phase 1.11 blocker)

**FIXED:**
- ✅ Canvas dimensions swapped (Commit: 7ebdedd) - was (height, width), now (width, height)
- ✅ VRAM writes not implemented (Commit: 3a1c09b) - $2007 writes now stored to VRAM
- ✅ Pattern tables being corrupted (Commit: 3a1c09b) - writes to 0x0000-0x1FFF now ignored
- ✅ **CHR ROM MIRRORING** (Commits: a551003, a24076a) - 8KB ROMs correctly mirror to both pattern tables!
- ✅ Nametable mirroring (Commit: 8b1b1e9) - HORIZONTAL/VERTICAL modes now correct
- ✅ **NAMETABLE ADDRESS CALCULATION** (Commit: 06218b5) - PPU now uses vRamAddress bits, not CTRL bits
- ✅ Frame buffer coordinates (Commit: c64189c) - pixels written to correct locations

**Current Status (Session 2025-12-29)**: Pattern table fully working! Pattern data loads with correct HIGH bytes!
- Game loads without crashing ✅
- Pattern table contains valid tile graphics (verified: 0xc6, 0x64, 0x38, 0x4c, ...) ✅
- **Pattern HIGH bytes now non-zero** (0xf0, 0xf8, 0xac, 0x80, 0x10, etc.) ✅
- **Tile indices vary correctly** (15, 170, 98, 1, ...) - NOT stuck at 0 ✅
- Frame 300+ diagnostics confirm data availability after extended run ✅
- **BUT**: Nametable still returns 0x00 (every background tile is blank) ❌

**The Write/Read Paradox**:
- Game DOES write tile indices (0x0f, 0x2c, 0x38, 0x12, etc.) to 0x2402-0x27xx ✅
- Immediate PPU reads at those addresses return the written values! ✅
- BUT at frame 300+, nametable fetches show `tileIdx=0` consistently ❌
- This suggests writes don't persist OR diagnostics are reading sprite table, not background

**Root Cause of Current Issue**:
- **Pattern table working**: All fixes confirmed, HIGH bytes loading correctly
- **Nametable address calculation fixed**: Uses vRamAddress bits correctly
- **Nametable writes DO work**: Game writes are stored and readable
- **BUT**: Either writes are being lost/cleared, OR game isn't writing to background tiles at frame 300+
- Most likely: Game uses sprite-based rendering for title screen, not background tiles

**Critical Question**:
- Is the game actually writing background nametable at frame 300+, or only using sprites?
- If writes exist: Are they being written to correct addresses per H-mirroring?
- If no writes: Game may rely on boot ROM initialization or sprite-only rendering

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
| CHR ROM | 8KB | ✅ Mirrored to both pattern tables (Commits a551003, a24076a) |
| Mirroring | Horizontal (H-mirror) | ✅ Correct mapping (Commit 8b1b1e9) |
| Pattern Table | Bit 4 of CTRL selects $0000 or $1000 | ✅ All tile graphics load correctly |
| Nametable Addressing | $2000-$2FFF with 4x mirroring | ✅ vRamAddress selection correct (Commit 06218b5) |
| Nametable Data | Background tile indices | ⚠️ Writes work but don't appear in background rendering |
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

## Session Handoff: Next Steps (Phase 1.11 - Nametable Address Calculation Fixed!)

### Current Status (Session ending 2025-12-29 - Round 7)

**Status: TWO CRITICAL BUGS FIXED! Pattern table now fully working with correct HIGH bytes. Nametable addressing corrected. Remaining issue: nametable content persistence during game rendering.**

#### What Was Fixed This Session

##### 1. ✅ **Nametable Address Calculation Error** (CRITICAL BUG - Commit 06218b5)

**Bug Discovery Process:**
- Added nametable read/write logging to trace data flow
- Systematic debugging revealed PPU using wrong register bits
- Compared expected vs. actual nametable fetch addresses

**The Bug:**
- `Ppu.kt` was using `controller.baseNametableAddr()` (CTRL bits 0-1) to calculate nametable fetch addresses
- Should use vRamAddress bits 10-11 (which reflect the current nametable during rendering)
- CTRL bits only determine INITIAL nametable at reset, not during scroll operations
- Result: PPU read from wrong nametable when vRamAddress had selected a different nametable

**The Fix:**
```kotlin
// WRONG (old code at lines 277, 324):
ppuInternalMemory[controller.baseNametableAddr() or (vRamAddress.asAddress() and 0x0FFF)]

// CORRECT (fixed code):
ppuInternalMemory[0x2000 or (vRamAddress.asAddress() and 0x0FFF)]
```

**Impact:**
- ✅ PPU now correctly reads nametable data written by game
- ✅ Game writes verified: reads return written values (0x0f, 0x2c, 0x38, 0x12, etc.)
- ✅ Nametable addressing now follows NES specification

##### 2. ✅ **CHR ROM Mirroring Confusion** (CLARIFICATION - Commit a24076a)

**The Issue:**
- Previous session split 8KB CHR ROM into two halves (bytes 0-4KB to PT0, 4-8KB to PT1)
- But NROM cartridges should mirror the entire 8KB across both pattern tables
- CTRL bit 4 doesn't swap which CHR data is visible in Mapper 0; both tables see same data

**The Fix:**
```kotlin
if (chrRom.size <= 0x2000) {
    // 8KB or less: mirror pattern table 0 data to pattern table 1
    patternTable0.copyInto(patternTable1)
} else {
    // 16KB+: load second half of CHR ROM to pattern table 1
    // (existing code for larger ROMs)
}
```

**Evidence of Fix:**
- Frame 300+ diagnostics: Pattern HIGH bytes now non-zero (0xf0, 0xf8, 0xac, 0x80, 0x10)
- Tile indices vary correctly (15, 170, 98, 1, ...) - NOT stuck at 0
- Pattern data fetches show real graphics from tile indices, not blanks

**Impact:**
- ✅ Pattern table data fully accessible (both LOW and HIGH bytes)
- ✅ Real tile graphics now loading correctly
- ✅ Applies to all 8KB Mapper 0 cartridges

#### Diagnostic Logging Infrastructure

Added `--ppu-diag` command-line flag for detailed PPU debugging:

```bash
timeout 15 ./gradlew run --args="testroms/donkeykong.nes --ppu-diag"
```

**Logs to:** `/tmp/ppu_diagnostics.log` (14,000+ lines per 10-frame run)

**Captured Data:**
- CTRL register value and bit state at frame start
- Every nametable byte fetch (which tile index)
- Every attribute byte fetch (which palette)
- Every pattern table low/high fetch (actual pattern bytes)
- Every shift register reload with data
- First 16 pixels per scanline with shift register state

**Example Diagnostic Output:**
```
CTRL = 0x90 (binary: 10010000)
Bit 4 (BG pattern table): true
Background pattern table address: 0x1000

FRAME 3 PRELOAD Tile 0 (scanline 0): nametable=0x00 tileIdx=0
  patternAddr=0x1003 patternLow=0xc6 patternHigh=0x00 palette=0
```

#### Current Test Results (Session 2025-12-29)

**✅ Golden Log Test**: PASSES (CPU accuracy maintained)

**Pattern Table Health (FULLY FIXED)**
- Frames 0-2: Rendering disabled (PPUMASK=0x00)
- Frame 3+: Pattern LOW bytes present: `0xc6, 0x64, 0x38, 0x4c, ...` ✅
- Frame 300+: Pattern HIGH bytes now non-zero: `0xf0, 0xf8, 0xac, 0x80, 0x10` ✅
- Pattern table 1 (0x1000) correctly mirrors pattern table 0 data ✅
- Tile indices vary: 15, 170, 98, 1, ... (not stuck at 0) ✅

**Nametable Address Calculation (FIXED)**
- Reads now use correct vRamAddress bits (10-11) ✅
- Game writes are stored and immediately readable: `nametable=0x0f, 0x2c, 0x38...` ✅
- mapNametableAddress() correctly maps all four nametables with H-mirroring ✅

**Remaining Problem: Write/Read Paradox**
- Game writes tile indices to nametable ✅
- Immediate reads at those addresses return written values ✅
- **BUT** at frame 300+, background nametable fetches show `tileIdx=0` consistently ❌
- Sprites render with correct tile indices; background stuck on tile 0
- Suggests: Game may not be writing background nametable at frame 300+ OR using sprite-only rendering

### What Remains Unknown

Pattern table is fully working! Nametable addressing is fixed! But why don't background tiles render?

**The Central Mystery:**
- Pattern data accessible ✅
- Nametable addressing correct ✅
- Writes and immediate reads work ✅
- **YET**: Background renders with all tile 0 (blank) at frame 300+

**Possible Explanations:**

1. **Game uses sprite-only title screen** (most likely)
   - Donkey Kong title screen might use only sprites, not background
   - Background nametable could be intentionally left at zeros
   - Background tiles only needed for gameplay levels

2. **Nametable written after diagnostics window** (possible)
   - Game initializes early but diagnostics sample frames 300-310
   - By frame 300, game has reset/cleared nametable

3. **PPUMASK background rendering disabled** (check state)
   - PPUMASK bit 3 enables background rendering
   - If bit 3 = 0, background won't render even if nametable has data
   - Verify mask state during frame 300+

4. **Scroll position or coordinate issue** (possible)
   - Game might be writing to correct nametable but PPU reading from wrong position
   - Or fine scroll causing tile address calculation error
   - mapNametableAddress() might have edge case bug

### Next Session Investigation Priority

**FOCUS: Determine whether background nametable writes exist at frame 300+**

1. **Check game state at extended runtime:**
   - Run with logging at frames 100-110 and 300-310
   - Does nametable contain non-zero values at frame 100?
   - If yes at 100 but no at 300, game clears it (design choice)
   - If no at both, game uses sprite-only rendering

2. **Verify PPUMASK background enable:**
   - Check bit 3 of PPUMASK ($2001) during rendering
   - If bit 3 = 0, background won't render regardless of nametable data
   - Log PPUMASK state changes to `--ppu-diag` output

3. **Check scroll/addressing during background rendering:**
   - Verify vRamAddress state during nametable fetches
   - Confirm correct nametable selected (bits 10-11)
   - Test edge cases: X=255, Y=239 (boundary wrapping)

4. **Investigate title screen structure:**
   - Look for pattern in sprite OAM data (frame 300+)
   - Count active sprites, verify they form title screen
   - If many sprites + zero background = sprite-based rendering confirmed

### Testing Notes

**✅ PASSING:**
- Golden log test passes (CPU accuracy maintained)
- Pattern table data fully correct (both LOW and HIGH bytes)
- Nametable addressing calculation fixed
- Nametable writes work and are readable
- No crashes or out-of-bounds errors
- Diagnostic logging captures multi-frame data (0-310+)

**⚠️ PARTIALLY WORKING:**
- Nametable writes work but don't appear in background rendering
- Sprite rendering works correctly with valid tile indices
- Game runs stably for 300+ frames

**✅ DIAGNOSTIC TOOLS:**
- `--ppu-diag` flag for comprehensive PPU tracing
- Diagnostic log captures frames 0-10 and 300-310
- Can log pattern fetches with actual data values
- Captures nametable, attribute, and palette information

### Commits This Session

- **06218b5**: fix: correct nametable address calculation during PPU rendering
- **a24076a**: fix: restore proper CHR ROM mirroring for 8KB cartridges

### Next Session Prompt

```
🎯 SESSION 7 COMPLETE: Two Critical Bugs Fixed! Pattern Table Fully Working!

## Major Achievements
✅ **Fixed Nametable Address Calculation** (Commit 06218b5)
   - PPU was using CTRL bits instead of vRamAddress bits
   - Now correctly selects nametable based on scroll position

✅ **Clarified CHR ROM Mirroring** (Commit a24076a)
   - 8KB ROMs properly mirror to both pattern tables (NROM standard)
   - Pattern HIGH bytes now load correctly (0xf0, 0xf8, 0xac, etc.)
   - Tile indices vary correctly (15, 170, 98, 1, ...)

## What We Know NOW
✅ Pattern table data fully working (both LOW and HIGH bytes)
✅ Pattern tile graphics render with real tile indices
✅ Nametable addressing calculation correct
✅ Nametable writes work and are immediately readable
✅ Game runs stably for 300+ frames
✅ Golden log test passes (CPU accuracy maintained)
❌ Background tiles still render as tile 0 (blank) at frame 300+
❌ But sprites render with correct tile indices

## The Remaining Mystery
Game shows correct pattern data and tile indices in sprite rendering, but
background nametable stuck on zeros. Two likely scenarios:
1. **Donkey Kong title screen is sprite-only** (most probable)
   - No background needed for title; only visible in gameplay
2. **PPUMASK background enable bit is off** (verify state)
   - Bit 3 of PPUMASK ($2001) = 0 would hide background

## Investigation Strategy for Next Session
1. **Verify game state at frame 100 vs frame 300**
   - Check if nametable has data at frame 100
   - If yes→no pattern: game intentionally clears background
   - If no→no pattern: sprite-only rendering confirmed

2. **Log PPUMASK state during rendering**
   - Add bit 3 state logging to `--ppu-diag` output
   - Verify background rendering is actually enabled

3. **Analyze sprite OAM at frame 300+**
   - Count active sprites forming title screen
   - Confirms sprite-based rendering hypothesis

## Run Tests
```bash
./gradlew build
./gradlew test --tests GoldenLogTest
timeout 30 ./gradlew run --args="testroms/donkeykong.nes --ppu-diag"
grep "PATTERN-HIGH" /tmp/ppu_diagnostics.log | head -20
grep "NAMETABLE" /tmp/ppu_diagnostics.log | head -20
```

## Files Modified This Session
- Ppu.kt: Fixed nametable address calculation (lines 277, 324)
- PpuAddressedMemory.kt: Clarified CHR ROM mirroring logic (lines 367-374)
- Application.kt: Updated diagnostic logging range (frame 300-310)
```

---

## Session 8: Systematic Root Cause Investigation Complete (2025-12-29)

### 🔍 Investigation Methodology
Used **Systematic Debugging** skill to conduct Phase 1-3 root cause analysis WITHOUT attempting fixes.

### ✅ Phase 1: Root Cause Evidence Gathered

**Finding 1: PPUMASK is ENABLED**
- Frame 300+ shows: PPUMASK=0x1e with bit 3 (background enable) = true
- Rendering: true (both background AND sprites enabled)
- Conclusion: Background rendering is NOT disabled

**Finding 2: Nametable Content Discrepancy**
- **nameTable0**: 0/1024 entries non-zero (completely EMPTY)
- **nameTable1**: 195/1024 entries non-zero (has valid tile indices!)
- **PPU scroll state**: Always points to NT0 (bits 10-11 = 00)
- **Result**: PPU renders from empty NT0 while game data sits unused in NT1

**Finding 3: Game Write Distribution**
- 17 writes to address range 0x2800-0x2BFF (should mirror to NT0)
- 18 writes to address range 0x2C00-0x2FFF (should mirror to NT1)
- 0 writes to direct NT0/NT1 ranges (0x2000-0x27FF)
- **Problem**: Writes to NT0's mirror range but NT0 stays empty!

### ⚠️ Phase 2: Pattern Analysis - Critical Discrepancy

**The Mystery:**
```
Game writes:  0x2800+ range (17 entries) → should go to NT0
Game reality: NT0 is completely empty (0 entries)
             NT1 has 195 entries (from 18 writes to 0x2C00+ range)
```

**Most Likely Root Cause:**
Nametable mirroring logic bug in `mapNametableAddress()` function:
- Addresses 0x2800-0x2BFF should map to NT0 via horizontal mirror
- But something is routing them differently or writes aren't persisting
- Detailed trace shows writes happen but data doesn't appear in NT0

**Secondary Hypothesis:**
- Game initializes NT1 with 195 entries
- Game has scroll bug or initialization issue keeping vRamAddress at NT0
- This would be a game bug, not emulator bug

### 📋 Phase 3: Hypothesis Formation

**Root Cause Candidates** (ranked by probability):
1. **Nametable mirroring bug** (HIGH) - mapNametableAddress() incorrectly routes 0x2800+ writes
2. **Mirror address calculation bug** (HIGH) - `addr % 0x400` may produce wrong offsets
3. **Game scroll initialization** (MEDIUM) - vRamAddress bits 10-11 stay at 00 unintentionally
4. **Sprite-only rendering** (LOW) - Game deliberately uses only sprites, ignores background

### 🎯 What We Know for CERTAIN
✅ Pattern table data loads correctly (both LOW and HIGH bytes)
✅ Nametable addressing calculation is mathematically correct
✅ PPUMASK is enabled (background should render if data exists)
✅ Game writes to nametable memory (35 total writes across frames 0-10)
❌ NT0 receives writes to mirror addresses but data doesn't persist
❌ PPU reads from NT0 which is empty, so blank background renders

### Next Session: Implement Root Cause Fix

**Priority 1: Verify Nametable Mirroring**
1. Add unit test for `mapNametableAddress()`:
   - Verify 0x2000 → nameTable0[0]
   - Verify 0x2400 → nameTable1[0]
   - Verify 0x2800 → nameTable0[0] (should be SAME as 0x2000!)
   - Verify 0x2C00 → nameTable1[0] (should be SAME as 0x2400!)
2. If test fails: Fix mirroring logic
3. If test passes: Investigate game scroll/initialization (Priority 2)

**Priority 2: Trace vRamAddress Initialization**
- Log vRamAddress at frame 0-10 at each PPU cycle
- Check if game ever sets bits 10-11 to non-zero
- Verify scroll write sequence in game code

**Testing Command:**
```bash
./gradlew build
./gradlew test --tests GoldenLogTest
timeout 30 ./gradlew run --args="testroms/donkeykong.nes --ppu-diag"
```

**Evidence Artifacts:**
- `/tmp/ppu_diagnostics.log` - Contains frame-by-frame nametable state
- Emulator source - Diagnostic logging infrastructure in place (Ppu.kt lines 323-368)

### Files Modified This Session (Investigation Only - No Fixes)
- Ppu.kt: Added enhanced diagnostic logging for PPUMASK and nametable contents (lines 323-368)
- Application.kt: Adjusted diagnostics range to frames 3-12 for early-game analysis
- PpuAddressedMemory.kt: Temporarily added write logging (removed - clean state restored)

### Commits
- None (investigation phase only - no fixes attempted per systematic debugging discipline)

---

## Session 9: TDD Verification of Nametable Mirroring (2025-12-29)

### 🎯 Objective
Verify using TDD that nametable mirroring logic is correct, as identified in Priority 1 from previous investigation.

### ✅ Phase 1: Test-Driven Development Cycle

**RED Phase - Write Failing Tests:**
- Created `NametableMirroringTest.kt` with comprehensive test cases
- Tests verify horizontal mirroring: 0x2000↔0x2800 map to same NT0, 0x2400↔0x2C00 map to same NT1
- Tests verify vertical mirroring: 0x2000↔0x2400 map to same NT0, 0x2800↔0x2C00 map to same NT1

**GREEN Phase - Tests Pass:**
- All nametable mirroring tests PASS ✓
- Confirmed: Mirroring logic is mathematically correct per NES spec
- Created `VramAddressTest.kt` to verify address computation

**Key Finding:**
- `VramAddress.asAddress()` correctly computes 15-bit relative addresses
- Calling code properly ORs with 0x2000 base offset (Ppu.kt lines 277, 372)
- Formula is correct: `(((((fineYScroll << 2) | getNameTable()) << 5) | coarseYScroll) << 5) | coarseXScroll`

### 📊 Results

**Mirroring Verification: PASS ✓**
- Horizontal: NT0=0x2000-0x23FF/0x2800-0x2BFF, NT1=0x2400-0x27FF/0x2C00-0x2FFF
- Vertical: NT0=0x2000-0x23FF/0x2400-0x27FF, NT1=0x2800-0x2BFF/0x2C00-0x2FFF
- All write/read round-trips work correctly

**VramAddress Computation: VERIFIED ✓**
- Address components properly positioned in 15-bit register
- NES bits 10-11 (nametable select) correctly computed
- NES bits 12-14 (fine Y) correctly positioned

### ⚠️ Critical Conclusion

**The nametable mirroring logic is NOT the root cause.**

Since Priority 1 (mirroring bug) is now ruled out, the actual root cause must be:
1. **Game doesn't write to background nametable** (most likely)
   - Donkey Kong title screen may be sprite-only
   - Game initializes NT1 for other purposes but NT0 stays empty by design

2. **Game never sets vRamAddress to point to NT1** (possible)
   - Game writes valid data to NT1 but PPU always reads from NT0
   - Would require investigating scroll register writes

3. **Writes to NT0 are being cleared** (unlikely, but possible)
   - Some memory clearing routine runs after writes
   - Or writes are happening but game never finalizes scroll state

### 🔄 Next Session: Focus on Game Behavior

Based on the systematic debugging investigation from Session 8, **Priority 2 should be the focus:**

**Investigation Steps:**
1. Log all CPU writes to VRAM (via $2006/$2007)
2. Trace game's scroll register initialization sequence
3. Check if game ever writes to vRamAddress to select NT1
4. Analyze OAM at frame 100 vs frame 300 to confirm sprite-only rendering

**Key Question:**
- Is the game TRYING to render background on title screen, or intentionally using sprites only?

### Artifacts Created
- `src/test/kotlin/com/github/alondero/nestlin/ppu/NametableMirroringTest.kt` - Comprehensive mirroring verification
- `src/test/kotlin/com/github/alondero/nestlin/ppu/VramAddressTest.kt` - Address computation verification

### Test Results
```bash
./gradlew test
# All tests pass:
# - NametableMirroringTest: 5/5 tests pass
# - VramAddressTest: 4/4 tests pass
# - GoldenLogTest: PASS (CPU accuracy maintained)
```

### Commits This Session
- **5610e2d**: test: add comprehensive nametable mirroring and vram address tests
- **c368ba6**: test: remove diagnostic test (investigation artifact)

### Key Insight
**The problem is NOT with PPU rendering logic or PPUMASK - it's a data availability issue.**
The game's nametable writes don't match the PPU's read addresses. Either:
1. Writes go to the wrong place (mirroring bug)
2. Reads happen from the wrong place (scroll initialization bug)
3. Game intentionally uses sprites without background (unusual but possible)

---

## Session 10-11: Systematic Debugging - VRAM & Palette Bug Fixes (2025-12-29)

### 🎯 Objective
Fix rendering bugs causing chaotic colors in background tiles. Tiles are rendering but with completely wrong palette colors.

### ✅ Bug #1: VRAM Write Address (FIXED - Commit b5d603f)

**Root Cause Investigation:**
- Added diagnostic logging to trace VRAM writes
- Found game writes 0x24 to 1024 nametable addresses in Frame 1
- But nametable diagnostics showed NT0 as completely empty (0/1024 entries)
- CONTRADICTION: Writes logged but data wasn't persisting

**Root Cause Found:**
- `vRamAddress.asAddress()` returns 15-bit relative offset (0x0000-0x3FFF)
- Code was using offset directly instead of adding 0x2000 base
- Result: Writes went to pattern table (read-only) instead of nametable

**Fix Applied:**
```kotlin
// BEFORE (buggy):
val writeAddr = vRamAddress.asAddress()

// AFTER (fixed):
val writeAddr = 0x2000 or vRamAddress.asAddress()
```

**Verification:**
- Before: NT0 = 0/1024 entries
- After: NT0 = 1024/1024 entries ✅

### ⚠️ Bug #2: Palette Shift Formula (PARTIALLY FIXED - Commit 03d2e9b)

**Problem Identified:**
- Background tiles now rendering with data (not blank)
- But colors are chaotic - repeating pattern of wrong colors
- Suggests palette index calculation is still incorrect

**Investigation:**
- Attribute table contains four 2-bit palette values per byte
- Which value to use depends on tile's position in 2×2 block
- Position determined by coarseX bit 0 and coarseY bit 0
- Original formula: `((v >> 4) & 4) | (v & 2)` ← WRONG
- Attempted fix: `((v >> 4) & 4) | ((v >> 1) & 2)` ← MAY STILL BE WRONG

**Evidence of Remaining Issue:**
- Screenshots show repeating color pattern across entire screen
- Every 8 pixels seems to use same palette (cycling through all 4)
- Tile data patterns look correct, only colors are wrong
- This cyclic behavior suggests palette shift formula still incorrect

**Root Cause Still Unknown:**
The attribute table formula needs deeper investigation:
```
Attribute byte layout (bits 7-0):
Q11 palette | Q10 palette | Q01 palette | Q00 palette
  (bits)     (bits)        (bits)        (bits)
   7-6        5-4           3-2           1-0

Where Q = quadrant in 2×2 tile block
Q00 = coarseX[0]=0, coarseY[0]=0
Q10 = coarseX[0]=1, coarseY[0]=0
Q01 = coarseX[0]=0, coarseY[0]=1
Q11 = coarseX[0]=1, coarseY[0]=1
```

The shift to select correct 2-bit pair should be:
- shift = (coarseY[0] << 2) | (coarseX[0] << 1)

In terms of vRamAddress.asAddress():
- coarseX is bits 4-0, coarseX[0] at bit 0
- coarseY is bits 9-5, coarseY[0] at bit 5

**Current Formula Analysis:**
Need to verify if the fix properly extracts these bits. The cyclic pattern in screenshots suggests the palette shift might be:
- Always using same palette pair for all tiles in a scanline
- OR using palette in wrong order (e.g., rotated or inverted)
- OR not updating paletteLatch between tile fetches

### Current Status (End of Session 11)

**✅ FIXED:**
- VRAM write address calculation (nametable data now persists)
- Nametable content fully populated (1024/1024 entries)
- Pattern table data loading correctly
- Canvas rendering displaying frames

**⚠️ RENDERING OUTPUT:**
- Tiles render with correct patterns (not blank)
- Colors completely wrong and repeating
- Background shows chaotic but structured color cycling
- No sprites visible (may be behind wrong-colored background)

### 📊 Next Session Investigation Priority

**CRITICAL: Palette Color Selection Bug**

The palette shift formula needs systematic verification:

1. **Trace Palette Bit Extraction:**
   - Add diagnostic logging for palette shift calculation
   - Log attribute byte value and calculated shift at each tile
   - Verify which quadrant position each palette fetch covers
   - Compare expected vs. actual shift values

2. **Verify Palette Shift Register Loading:**
   - Check if paletteShiftLow/High are properly loaded from paletteLatch
   - Verify they maintain correct values during 8-cycle tile period
   - Log shift register state at pixel rendering time

3. **Test Palette RAM Initialization:**
   - Verify palette RAM contains expected default colors
   - Check if game writes correct palette data to 0x3F00-0x3F1F
   - Confirm palette lookups use correct indices

4. **Manual Calculation Verification:**
   - Pick a specific tile position (e.g., x=0, y=0)
   - Manually calculate what coarseX[0] and coarseY[0] should be
   - Verify shift formula produces expected value
   - Write unit test to validate formula

5. **Cross-Reference Against Real NES:**
   - Compare screenshot against known Donkey Kong rendering
   - Identify exact color pattern that should appear
   - Determine if issue is systematic (all colors wrong by same offset) or chaotic

**Alternative Hypotheses to Rule Out:**
- Palette RAM not initialized to default colors
- Game doesn't write palette data (using all uninitialized values)
- Shift register loading timing is off (palette loads at wrong time)
- Fine X scroll affecting palette bits (shouldn't but verify)

### Testing Command
```bash
./gradlew build
./gradlew test --tests GoldenLogTest
timeout 15 ./gradlew run --args="testroms/donkeykong.nes"
```

### Commits This Session
- **b5d603f**: fix: correct VRAM write address calculation in PPU data register
- **03d2e9b**: fix: correct palette shift calculation for attribute table bit extraction

### Key Artifacts
- Diagnostic logs: /tmp/ppu_diagnostics.log (VRAM write traces, palette fetches)
- Screenshots: Show repeating color pattern, tile data correct, palette wrong
- Evidence: Palette shift likely still has bit extraction bug

---

## Session 12 (2025-12-29): PPU Pipeline & CHR ROM Fixes

### 🎯 Objective
Resolve the chaotic color cycling and missing tile issues identified in the previous session.

### ✅ Major Fixes Implemented

**1. 16-bit Palette Shift Registers (Ppu.kt)**
- **Problem**: Palette registers were 8-bit, causing them to lose "next tile" data when fetching new attributes, leading to color desynchronization mid-tile.
- **Fix**: Converted `paletteShiftLow` and `paletteShiftHigh` to `Int` (16-bit). They now correctly hold the current tile's palette (bits 0-7) and the next tile's palette (bits 8-15).

**2. PPU Pipeline Timing Correction (Ppu.kt)**
- **Problem**: Pixels were being shifted *before* extraction, causing the first pixel of every tile to be skipped.
- **Fix**: Reordered the rendering loop to **extract pixels first**, then shift.
- **Fix**: Implemented `preloadFirstTwoTiles` correctly to load Tile 0 into the High byte (Current) and Tile 1 into the Low byte (Next), matching the shift direction.

**3. CHR ROM Mirroring Fix (CRITICAL - PpuAddressedMemory.kt)**
- **Problem**: 8KB CHR ROMs (like Donkey Kong) were not loading the second 4KB bank of tile data. The emulator was mirroring the first 4KB bank to both pattern tables.
- **Fix**: Removed the incorrect mirroring logic. Now, if the CHR ROM is 8KB, the second 4KB is correctly loaded into `patternTable1`. This explains why background/sprites previously had wrong tiles.

**4. Fine X Scroll Application (Ppu.kt)**
- **Fix**: Applied `fineX` scrolling to **all** pixels in the scanline, ensuring smooth sub-tile scrolling across the entire screen.

**5. Attribute Decoding Formula (Ppu.kt)**
- **Fix**: Corrected the formula to extract the 2-bit palette index using bit 1 of coarse X and bit 1 of coarse Y.

### 🧹 Cleanup
- Removed diagnostic logging code from `Ppu.kt` and `PpuAddressedMemory.kt` to keep the codebase clean.

### 📊 Current Status
- **Rendering**: The PPU rendering pipeline is now theoretically sound. The shift registers, timing, and memory loading logic are corrected.
- **Visuals**: With the CHR ROM fix, the "wrong tiles" issue should be resolved. The color stability should be fixed by the 16-bit registers.
- **Tests**: `GoldenLogTest` passes (CPU accuracy maintained). `NametableMirroringTest` passes.

### ⏭️ Next Steps
1. **Verify Visuals**: Confirm if the game now renders correctly.
2. **Controller Input**: Implement $4016/$4017 handling to allow gameplay.
3. **Gameplay Testing**: Verify player movement and game logic.

---

## Session 13 (2025-12-29): Failed Attempts at Further CHR ROM Fixes

### ❌ Failed Attempt: Changing CHR ROM Size Comparison

**Attempted Fix:**
Changed the CHR ROM loading condition from `<` to `<=`:
```kotlin
// ATTEMPTED (doesn't work):
if (chrRom.size <= 0x2000) {  // Includes exactly 0x2000
    patternTable0.copyInto(patternTable1)
}
```

**Rationale:**
- 8KB Donkey Kong ROM is exactly 0x2000 bytes
- Thought `<` was excluding 8KB ROMs, making them use split logic instead of mirror
- `<=` should include 8KB in the mirror path

**Result:**
❌ Made rendering WORSE - reverted immediately

**Why It Didn't Work:**
Unknown. The rendering looked better with the original `<` condition, even though mathematically `<=` should be correct for 8KB ROMs. This suggests:
1. The CHR ROM logic may not be the actual issue
2. Other factors are affecting rendering more significantly
3. Further investigation needed with different approach

**Lesson Learned:**
Do NOT change the CHR ROM size comparison to `<=`. The current `<` condition works better, even if counterintuitive.
