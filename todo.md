# NES Emulator - Current Focus & Status

## Current Status (2026-04-27)

**Main Goal:** Improve mapper support and compatibility.

**What's Working:**
- Mapper 0 (NROM): Full support (Donkey Kong, SMB1)
- Mapper 1 (MMC1): Full support with PRG-RAM (Tetris, Zelda)
- Mapper 2 (CNROM/UNROM): Full support (Castlevania, Contra)
- Mapper 3 (NINA-003/006): Support for Paperboy
- Mapper 4 (MMC3): Supported with PRG-RAM and IRQs (Mega Man 4-6, Kirby's Adventure)
- Mapper 11 (Color Dreams): Fixed CHR/PRG banking (Bible Adventures)

**Recent Fixes:**
- **Kirby's Adventure (MMC3):** Fixed black screen crash caused by missing PRG-RAM at $6000-$7FFF.
- **Mapper 4 (MMC3):** Added PRG-RAM support and RAM protect register ($A001).
- **Mapper 1 (MMC1):** Added PRG-RAM support.
- **State Capture:** Updated MapperStateSnapshot to include PRG-RAM for debugging.

**Current Issues:**
- Some MMC3 games might have IRQ timing glitches (e.g., status bar jitter).
- Audio DMC channel needs further validation.

**Next Steps:**
1. Implement Mapper 7 (AOROM).
2. Refine MMC3 IRQ timing - specifically the A12 toggle behavior.
3. Add support for battery-backed PRG-RAM (saving/loading .sav files).
4. Implement Mapper 9 (MMC2 - Punch-Out!!).

---

# Detailed Debugging Notes

## Resolved: Kirby's Adventure Black Screen (MMC3)

### Symptom
- Game would boot but eventually crash/jump to $0000.
- Trace analysis revealed a `JMP ($6038)` jumping to $0000.

### Root Cause
- Mapper 4 (MMC3) was missing PRG-RAM at $6000-$7FFF.
- Kirby's Adventure uses this RAM for jump tables.

### Fix
- Implemented 8KB PRG-RAM in `Mapper4.kt`.
- Properly implemented $A001 control register for RAM enabling/protection.

### Verification
- `KirbyScreenshotTest` now shows valid gameplay frames (e.g., 61200 non-black pixels).
- CPU PC correctly stays in PRG-ROM range ($8000-$FFFF) instead of jumping to zero page.
