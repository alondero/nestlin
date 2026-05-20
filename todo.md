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
- Audio DMC channel needs further validation.

**Next Steps:**
1. Add support for battery-backed PRG-RAM (saving/loading .sav files).
2. Complete the Mapper 5 (MMC5) stub — Castlevania III (needs $5113-$5117/$5120-$5127 bank-select decode rewrite, real fill mode, multiplier output, PPU-scanline IRQ; expansion audio is a separate effort).
3. Implement Mapper 10 (MMC4 — Fire Emblem JP, Famicom Wars JP). Same CHR latch as Mapper 9, just 16KB PRG banking instead of 8KB.
4. Visual validation: run Mesen2 screenshot comparison on Kirby/MM4-6/SMB3 to confirm the 2026-05-18 MMC3 IRQ-timing fix resolves HUD jitter end-to-end.

**Recently Completed:**
- **Mapper 9 (MMC2 — Punch-Out!!) (2026-05-20):** PRG (8KB switchable + 24KB fixed), CHR latch on PPU pattern-table reads, mirroring register. Unit-test coverage in `Mapper9Test`. Read-then-flip latch ordering verified.
- **Mapper 7 (AOROM):** Implemented and verified (see MAPPER_SUPPORT.md).

**Recently Resolved:**
- **MMC3 IRQ doubling (2026-05-18):** PPU sprite-fetch pipeline rewritten to match real hardware. Sprite evaluation moved to cycle 65 (no pattern reads), tile fetches moved to cycles 257-320 of previous scanline. Removed eager cycle-0 sprite reads that produced a second A12 rising edge per scanline. Also fixed 8x16 pattern table to be resolved per-sprite. MMC3 IRQ now clocks at 241 A12 rising edges per frame (was ~480). Validated by `A12EdgeRateTest`.

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
