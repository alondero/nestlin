# NES Emulator - Current Focus & Status

## Current Status (2026-04-14)

**Main Issue:** Mapper 1 games (Tetris, Lolo 1, Chip 'n Dale) show black screens

**What's Working:**
- Donkey Kong (Mapper 0) runs correctly
- GoldenLogTest passes (CPU is correct)
- PPU rendering infrastructure is in place

**What Has Been Tried:**
1. Fixed Mapper 1 PRG banking offset calculation (was causing wrong reset vector reads)
2. Added PPU warm-up tracking infrastructure (disabled - breaks VBlank wait loops)
3. Added CHR write notification system
4. PPUMASK stays at 0 - PPU never receives initialization writes

**Current Hypothesis:**
Games may be waiting for VBlank during initialization, and our VBlank timing may not match real NES behavior. The first ~15 seconds of frames may be empty.

**Next Steps:**
1. Instrument PPU register writes (trace $2000-$2007)
2. Compare frame output between working (Donkey Kong) and broken (Tetris)
3. Investigate why Donkey Kong takes ~15s to produce valid screenshots

---

# Detailed Debugging Notes

## Priority: Resolve Mapper 1 Black Screen Issue

### Symptom
- Tetris (Mapper 1) shows black screen
- Donkey Kong (Mapper 0) works correctly
- Mapper 1 PRG banking is now correct (PC=0xFF00 confirmed)
- CHR ROM loaded correctly (16KB)
- PPUMASK stays 0 throughout execution

### Root Cause Hypothesis
The PPU never receives initialization writes because either:
1. CPU executes an infinite loop before PPU initialization
2. PPU warm-up delay (NES-001) not implemented - but this shouldn't affect execution
3. CHR bank initialization issue for 16KB CHR ROM

### Next Steps

1. **Instrument execution to trace PC at regular intervals**
   - Add logging every ~1 million cycles to see where execution goes
   - Check if Tetris is hitting the PPU initialization code at all

2. **Verify PPU register writes are happening**
   - Log writes to $2000-$2007 (PPU registers)
   - Track PPUMASK specifically

3. **Check if Tetris initializes the PPU at all**
   - Look for writes to $2000 (PPUCTRL) and $2001 (PPUMASK)
   - If these never happen, the game is stuck in an early init loop

4. **Consider PPU warm-up delay implementation**
   - NES-001: PPU ignores writes for ~1 frame after power-on
   - This might cause games to wait in loops that our emulator doesn't implement

### Test ROMs
- `tetris.nes`: Mapper 1, 32KB PRG, 16KB CHR ROM, reset at $FF00
- `lolo1.nes`: Mapper 1 (user confirmed)
- `chipndale.nes`: Mapper 1 (user confirmed)
