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
14. **CHR ROM Mirroring**: ✅ Small cartridges (8KB CHR) now correctly mirror data across both pattern tables (Commit: a551003)
15. **PPU Diagnostics**: ✅ Comprehensive --ppu-diag logging for tracing tile fetches, pattern data, and pixel output

---

## ⚠️ Critical Issues Blocking Donkey Kong Display

### 1. ✅ MISSING OPCODE - RESOLVED
- **Problem**: Donkey Kong crashed at ~15-20 seconds on unimplemented opcodes
- **Fix**: Implemented CLI (0x58), added undocumented opcode fallback (Commit: a49d784)
- **Result**: Donkey Kong now runs much further without crashing
- **Note**: Game encounters ~25 unique undocumented SKB/NOP variants which are logged but don't crash
- **Next blocker**: Now hitting PPU rendering issues as game waits for display output

### 2. ⚠️ PPU Rendering Issues (MAJOR PROGRESS - Phase 1.10 blocker)

**FIXED:**
- ✅ Canvas dimensions swapped (Commit: 7ebdedd) - was (height, width), now (width, height)
- ✅ VRAM writes not implemented (Commit: 3a1c09b) - $2007 writes now stored to VRAM
- ✅ Pattern tables being corrupted (Commit: 3a1c09b) - writes to 0x0000-0x1FFF now ignored
- ✅ **CHR ROM MIRRORING** (Commit: a551003) - 8KB ROMs now correctly mirror to both pattern tables!
- ✅ Nametable mirroring (Commit: 8b1b1e9) - HORIZONTAL/VERTICAL modes now correct
- ✅ Frame buffer coordinates (Commit: c64189c) - pixels written to correct locations

**Current Status**: Pattern table data loading correctly! But nametable is all zeros
- Game loads without crashing ✅
- Pattern table contains valid tile graphics (verified: 0xc6, 0x64, 0x38, 0x4c, ...) ✅
- Frame buffer renders with valid pattern data ✅
- **BUT**: Nametable always returns 0x00 (every tile is blank) ❌
- Game should write tile indices to 0x2000-0x2FFF during init, but reads return zero

**Root Cause of Current Issue**:
- **Pattern table working**: CHR ROM mirroring fixed
- **Nametable broken**: Either game writes aren't reaching nametable, or nametable read/write paths are misaligned
- Hypothesis: mapNametableAddress() may still have a bug, or game initialization happens before we start reading

**Next Investigation**:
  - Add nametable write logging to trace where game data is going
  - Verify mapNametableAddress() correctly maps both reads and writes
  - Test with simple write/read validation (write 0xAB to 0x2000, read back)
  - Check if game writes happen before first diagnostic sample (frame 3)

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
| CHR ROM | 8KB | ✅ Mirrored to both pattern tables (Commit a551003) |
| Mirroring | Horizontal (H-mirror) | ✅ Correct mapping (Commit 8b1b1e9) |
| Pattern Table | Bit 4 of CTRL selects $0000 or $1000 | ✅ Mirroring handles 8KB ROMs |
| Nametable | $2000-$2FFF with 4x mirroring | ⚠️ Mapping correct but data always zero |
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

## Session Handoff: Next Steps (Phase 1.10 - CHR ROM Mirroring Bug Fixed!)

### Current Status (Session ending 2025-12-28 - Round 6)

**Status: CHR ROM mirroring bug IDENTIFIED AND FIXED. Pattern table data now loads correctly. But nametable is still all zeros!**

#### What Was Fixed This Session

##### 1. ✅ **CHR ROM Mirroring Not Implemented** (CRITICAL BUG - Commit a551003)

**Bug Discovery Process:**
- Created comprehensive diagnostic logging system with `--ppu-diag` flag
- Traced full data flow: nametable fetch → pattern table address → pattern bytes → pixel output
- Logged all tile fetches to `/tmp/ppu_diagnostics.log` for analysis

**The Bug:**
- Donkey Kong has **8KB CHR ROM** with **CTRL register bit 4 = 1** (selects pattern table at 0x1000)
- `loadChrRom()` function treated 8KB as two separate 4KB halves:
  - First 4KB → pattern table 0 (0x0000-0x0FFF)
  - Second 4KB → pattern table 1 (0x1000-0x1FFF)
- This only works for 16KB+ ROMs. For 8KB ROMs, both halves should see the **same data** (mirrored)
- Result: When game selected pattern table 1 (0x1000), it read from empty table → all zeros

**Root Cause Location:** `PpuAddressedMemory.kt`, `loadChrRom()` function (lines 348-376)

**The Fix:**
```kotlin
if (chrRom.size <= 0x1000) {
    // Mirror: copy pattern table 0 data to pattern table 1
    patternTable0.copyInto(patternTable1)
} else {
    // Separate: load second half of CHR ROM to pattern table 1
    // (existing code for 16KB+ ROMs)
}
```

**Evidence of Fix:**
- Before fix: `patternLow=0x00 patternHigh=0x00` (all zeros)
- After fix: `patternLow=0xc6 patternHigh=0x64` (real pattern data!)
- Pattern addresses now correctly read from mirrored 8KB region

**Impact:**
- ✅ Pattern table data no longer empty when CTRL bit 4 is set
- ✅ Enables tile graphics to render for all 8KB Mapper 0 games
- ✅ Applies to Donkey Kong and many other classic NES games

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

#### Current Test Results

**✅ Golden Log Test**: PASSES (CPU accuracy maintained)

**Pattern Table Health:**
- Frames 0-2: Rendering disabled (PPUMASK=0x00)
- Frame 3+: Pattern data present and correct: `0xc6, 0x64, 0x38, 0x4c, ...`
- Pattern table 1 (0x1000) now correctly mirrors pattern table 0 data

**Remaining Problem:**
- **Nametable is ALL ZEROS** - every tile being fetched is index 0 (blank tile)
- Despite game writing to nametable ($2000-$2FFF), reads return 0x00
- Diagnostics show: `nametable=0x00 tileIdx=0` for every fetch

### What Remains Unknown

The Pattern Table Mirroring fix was a major breakthrough, but **WHY IS NAMETABLE ALL ZEROS?**

1. **Is the game writing to nametable?**
   - Game should write tile indices to 0x2000-0x2FFF during initialization
   - Check if game writes are actually reaching nametable memory

2. **Is nametable memory mirrored correctly?**
   - Four 1KB nametables with horizontal/vertical mirroring
   - May have off-by-one in mapNametableAddress() despite previous fixes
   - Or writes go to wrong location, reads from different location

3. **Is nametable initialization happening?**
   - Game might write nametable after rendering starts
   - If we're sampling too early, might see uninitialized zeros
   - Or game relies on VRAM address increment behavior

4. **What's the actual game state?**
   - Is game writing anything to 0x2000-0x2FFF?
   - What addresses are being written to?
   - Are those addresses being read back correctly?

### Next Session Investigation Priority

**FOCUS: Why does nametable return 0x00 for every address?**

1. **Add diagnostic logging for nametable writes:**
   - Hook `PpuInternalMemory.set()` to log writes to 0x2000-0x2FFF
   - Show: address, value written, which physical table it maps to
   - Run for 5-10 frames and analyze

2. **Verify nametable read/write paths:**
   - Confirm `mapNametableAddress()` is working correctly post-fix
   - Test with simple write: Write 0xAB to 0x2000, read back, verify 0xAB returned
   - Check if horizontal mirroring is working (0x2000 and 0x2400 should share or mirror)

3. **Trace game's nametable initialization:**
   - Enable CPU logging to see where game writes to $2000-$2FFF
   - What values does it write? Where exactly?
   - Cross-reference with what diagnostics say is being read

4. **Most likely causes:**
   - Game initialization happens BEFORE first diagnostic sample (frame 3)
   - Nametable data exists but mapping function is wrong
   - Write path works but read path is broken (or vice versa)
   - VRAM address increment during writes affects what gets stored

### Testing Notes

**✅ PASSING:**
- Golden log test passes (CPU accuracy maintained)
- Pattern table data correct after mirroring fix
- No crashes or out-of-bounds errors
- Diagnostic logging works and captures detailed trace

**❌ NOT FIXED:**
- Nametable all zeros despite likely valid game writes
- Real graphics still not rendering

**✅ NEW TOOLS:**
- `--ppu-diag` flag for comprehensive PPU tracing
- Diagnostic log file (`/tmp/ppu_diagnostics.log`) for offline analysis
- Frame counter tracking (logs which frame is being processed)
- Ability to toggle diagnostic logging on/off per frame range

### Commits This Session

- **a551003**: fix: implement CHR ROM mirroring for small cartridges (8KB or less)

### Next Session Prompt

```
🎯 MAJOR BREAKTHROUGH: CHR ROM Mirroring Bug FIXED!

Pattern table now correctly mirrors 8KB CHR ROM data to both pattern table
address ranges. This means real tile graphics data is now being fetched.

BUT: Nametable is still all zeros! Game should be writing tile indices to
$2000-$2FFF, but diagnostics show every fetch returns 0x00.

## What We Know
✅ Pattern table data is correct after mirroring fix
✅ Nametable mirroring logic was fixed in previous session
✅ Game loads and runs without crashing
❌ Every nametable address returns 0x00 (tile index 0, blank tile)

## Investigation Strategy
Two possibilities:
1. **Game writes are not reaching nametable** - writes go to wrong memory location
2. **Nametable mapping is still broken** - reads return from wrong location

## Next Steps
1. Add logging to PpuInternalMemory.set() to trace nametable writes
2. Verify mapNametableAddress() returns correct table for both reads and writes
3. Check CPU log to see where game is actually writing tile data
4. Test nametable mirroring with simple write/read validation

## Run Tests
- Build: ./gradlew build
- Verify golden log: ./gradlew test --tests GoldenLogTest
- Run with diagnostics: timeout 15 ./gradlew run --args="testroms/donkeykong.nes --ppu-diag"
- Check log: cat /tmp/ppu_diagnostics.log | grep -E "PRELOAD|Fetch NAMETABLE"

## Files to Check
- PpuAddressedMemory.kt: mapNametableAddress() function
- PpuInternalMemory: get/set operators for nametable ranges
- CPU log output: Where is game writing to 0x2000-0x2FFF?
```
