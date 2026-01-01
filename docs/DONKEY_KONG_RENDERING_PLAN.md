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
16. **Controller Input**: ✅ Implemented $4016 read/write and keyboard integration (Session 2025-12-29)

---

## ⚠️ Critical Issues Blocking Donkey Kong Display

### 1. ✅ MISSING OPCODE - RESOLVED
- **Problem**: Donkey Kong crashed at ~15-20 seconds on unimplemented opcodes
- **Fix**: Implemented CLI (0x58), added undocumented opcode fallback (Commit: a49d784)
- **Result**: Donkey Kong now runs much further without crashing
- **Note**: Game encounters ~25 unique undocumented SKB/NOP variants which are logged but don't crash
- **Next blocker**: Now hitting PPU rendering issues as game waits for display output

### 2. ✅ PPU Rendering Issues - RESOLVED
- **Pattern Table**: Fully working, correct CHR ROM loading for 8KB cartridges.
- **Nametable Addressing**: Fixed in Session 7.
- **Nametable Content**: Fixed VRAM write address in Session 10.
- **Color Cycling**: Fixed 16-bit shift registers and pipeline order in Session 12.
- **Status**: Visual verification required, but theoretical blockers are cleared.

### 3. ✅ Controller Input Not Implemented - RESOLVED
- **Status**: Implemented in Session 2025-12-29.
- **Fix**: Added `Controller` class, wired to $4016/$4017 in `Memory`, integrated JavaFX keyboard events in `Application`.
- **Mapping**: Arrow keys for D-Pad, Z/X for A/B, Space/Enter for Select/Start.

---

## Session 14 (2025-12-29): Input Implementation & Final Checks

### 🎯 Objective
Implement controller input to allow gameplay interaction.

### ✅ Achievements

**1. Controller Implementation (Controller.kt)**
- Implemented standard NES controller logic:
  - 8-bit button state storage
  - Strobe handling (latch vs reload)
  - Serial shift register readout
  - Implemented `read()` returning bit 0 and shifting
  - Implemented `write()` handling strobe signal

**2. Memory Integration (Memory.kt)**
- Added `controller1` and `controller2` instances
- Intercepted writes to `$4016` (strobes both controllers)
- Intercepted reads from `$4016` (Controller 1) and `$4017` (Controller 2)
- Exposed `controller1` via getter for UI access

**3. UI Integration (Application.kt)**
- Added keyboard event listeners (`setOnKeyPressed`, `setOnKeyReleased`)
- Mapped keys:
  - Arrows -> D-Pad
  - Z -> A
  - X -> B
  - Space -> Select
  - Enter -> Start
- Updates `Controller` state in real-time

### 📊 Current Status
- **Input**: Fully implemented and wired up.
- **Rendering**: Believed to be fixed (Session 12).
- **Stability**: Build passes, Tests pass.

### ⏭️ Next Steps
1. **Play the Game!**: Verify if Donkey Kong is playable.
2. **Visual Verification**: Check for any remaining graphical glitches (sprites, scrolling).
3. **Audio**: APU is still a stub. Next major phase would be APU implementation.

---
