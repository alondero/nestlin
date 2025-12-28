# Donkey Kong Rendering Plan

## Overview
This document outlines the current state of the Nestlin NES emulator and the path forward to successfully boot and display Donkey Kong (donkeykong.nes) with proper rendering.

---

## ✅ What's Working Well

Your emulator has a solid foundation:

1. **CPU Implementation**: ~90 opcodes with proper addressing modes, cycle counting, and interrupt handling (NMI edge-triggered detection)
2. **Memory Map**: Correct RAM mirroring ($0000-$1FFF), PPU register mirroring, cartridge space mapping
3. **ROM Loading**: iNES format parsing, Mapper 0 (NROM) support—exactly what Donkey Kong needs
4. **PPU Framework**: Timing structure (scanlines/cycles), VRAM address register with scroll logic, tile/sprite fetching
5. **Sprite System**: OAM buffer, sprite evaluation, priority handling, flip support
6. **Rendering Infrastructure**: Shift registers (pattern+palette), frame buffer, JavaFX UI pipeline with pixel update
7. **Test Framework**: Golden log validation working
8. **Nametable Mirroring**: ✅ HORIZONTAL/VERTICAL mirroring implemented and configured from iNES header (Commit: 89b5004)
9. **Sprite Coordinate System**: ✅ Fixed sprite pixel extraction to use correct coordinate space (Commit: 89b5004)

---

## ⚠️ Critical Issues Blocking Donkey Kong Display

### 1. ❌ MISSING OPCODE - IMMEDIATE BLOCKER (NEW)
- **Problem**: Donkey Kong runs for ~15-20 seconds then crashes on unimplemented opcode
- **Impact**: Prevents any further execution/testing
- **Fix Required**: Identify which opcode is missing and implement it
- **Investigation**: Need to add error logging to capture opcode value when exception occurs

### 2. Controller Input Not Implemented (CRITICAL - Phase 2 blocker)
- **Problem**: Donkey Kong won't respond to input—game may hang waiting for button presses
- **Location**: $4016 (strobe/data) and $4017 (data) registers
- **Impact**: Hard blocker for interactive testing
- **Fix Required**: Full implementation of strobe signal handling and serial data shifting

### 3. ✅ PPU Rendering Path (RESOLVED)
**Fixed in Commit 89b5004:**
- ✅ **Sprite X position calculation** - Now correctly converts from PPU cycle (1-341) to pixel coordinate (0-255)
- ✅ **Nametable Mirroring** - Properly enforced based on iNES header (horizontal for Donkey Kong)

**Still potential issues:**
- **Fine X scroll extraction** (line 298): May have off-by-one errors in bit shifting logic
- **Palette index calculation** (line 242): Complex attribute table formula might be wrong

### 4. PPU Memory Access Timing
- **Expected**: Each memory access should take 2 PPU cycles (read then write)
- **Current**: Code does fetches at cycle % 8 = {0,2,4,6} but doesn't explicitly model the 2-cycle delay
- **Risk**: Attribute table or pattern table reads could occur at wrong times, causing tile data misalignment

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

### Phase 1.5: Fix Missing Opcode (NEW - BLOCKING)

**Immediate Priority**: Identify and implement missing opcode
- Donkey Kong crashes after ~15-20 seconds on UnhandledOpcodeException
- Need to capture opcode value and cross-reference with 6502 instruction set
- Once fixed, Donkey Kong should run longer and we can proceed to rendering validation

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
- [ ] Emulator runs to completion without missing opcode exception
- [ ] First frame renders (captures in frame buffer)
- [ ] UI displays rendered frame
- [ ] Keyboard input is recognized
- [ ] Game title screen displays
- [ ] Player sprite visible and controllable
- [x] Golden log test passes (CPU accuracy maintained)

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
