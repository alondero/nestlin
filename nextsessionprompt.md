# Next Session: Resolve Mapper 1 Black Screen Issue

## Session Context
Previous session debugging ended with:
- Fixed Mapper 1 PRG banking offset calculation bug
- Reset vector now correctly reads PC=0xFF00 (not 0x3131)
- But Tetris and other Mapper 1 games still show black screen
- Donkey Kong (Mapper 0) works perfectly

## The Problem
Mapper 1 games (Tetris, Lolo 1, Chip 'n Dale) show black screens even though:
- PRG banking is now correct
- CHR ROM (16KB) is loaded into pattern tables
- Reset vector points to valid code at 0xFF00

PPUMASK stays 0 throughout execution, suggesting the game never enables rendering.

## Systematic Debugging Plan

### Phase 1: Verify CPU is Executing Game Code
**Before adding any instrumentation:**
1. Read `knowledge/nes-mapper1-debugging.md` to understand previous findings
2. Review todo.md for the full diagnostic plan

### Phase 2: Trace PPU Register Writes
The key diagnostic needed:
```
Every write to $2000-$2007 (PPU registers), log:
- Address written to
- Value written
- Current PC
```

This will reveal:
- Does Tetris ever write to $2000 (PPUCTRL)?
- Does Tetris ever write to $2001 (PPUMASK)?
- If not, the game is stuck in an early init loop

### Phase 3: Instrument CPU Execution
If no PPU writes are happening, instrument every Nth million cycles:
```
Every 1M cycles: log PC, A, X, Y registers
```

To determine:
- Is CPU executing a loop?
- Is it in the initialization code?
- Is it hitting the PPU initialization routine?

## Files to Review Before Starting
1. `knowledge/nes-mapper1-debugging.md` - Previous findings
2. `todo.md` - Full diagnostic plan
3. `src/main/kotlin/.../cpu/Cpu.kt` - For adding instrumentation
4. `src/main/kotlin/.../gamepak/Mapper1.kt` - Current implementation

## Success Criteria
- Tetris shows correct graphics (not black screen)
- Lolo 1 and Chip 'n Dale also work
- No regression in GoldenLogTest

## Commands to Run After Each Change
```bash
./gradlew test --tests GoldenLogTest  # Ensure no regression
./gradlew run --args="testroms/tetris.nes"  # Test Tetris
```

## If This Is A PPU Timing Issue
If the systematic approach shows the CPU is running but PPU never initializes, the issue may be PPU warm-up delay (NES-001). This requires implementing:
1. PPU ignores writes for ~1 frame after power-on
2. Games typically wait in a loop for PPU to be ready

This would require studying NESdev Wiki's PPU power-up documentation.