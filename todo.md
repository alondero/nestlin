# NES Emulator Debugging Tasks

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

## Status: In Progress

Last session ended with:
- PRG banking fixed (reset vector now correct at PC=0xFF00)
- But still black screen for Mapper 1 games
- GoldenLogTest passes (no regression)