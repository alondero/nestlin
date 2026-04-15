# NES Mapper Support Status

## Mapper 0 (NROM)
**Status:** Working

- **Games:** Donkey Kong, Super Mario Bros, Mega Man 1-6
- **Behavior:** No bank switching. PRG ROM at $8000 is always PRG[0x0000-0x3FFF]
- **Notes:** Simple, direct mapping. Full support.

## Mapper 1 (MMC1/SxROM)
**Status:** Working

- **Games:** Tetris, Lolo 1, Chip 'n Dale
- **Behavior:**
  - 5-bit shift register for bank switching
  - PRG mode 3 (default): $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
  - PRG mode 2: $8000-$BFFF fixed to bank 0, $C000-$FFFF switchable
  - PRG mode 0/1: 32KB mode (both 16KB banks switch together)
  - CHR ROM loaded via mapper delegate into pattern tables
- **Banking Fix Applied (2026-04-15):** Mode 3 and mode 2 had inverted fixed/switchable assignments per MMC1 spec

## Mapper 2 (UxROM)
**Status:** Not Implemented

- **Behavior:** Simple 16KB PRG bank switching at $8000-$BFFF

## Mapper 3 (CNROM)
**Status:** Not Implemented

- **Behavior:** 8KB CHR ROM bank switching

## Mapper 4 (MMC3)
**Status:** Not Implemented

- **Games:** Many later NES games (Mega Man 4-6, Contra, etc.)
- **Behavior:** Complex bank switching with IRQ support

---

## Adding New Mappers

1. Implement in `gamepak/` directory
2. Extend `Mapper` base class or trait
3. Update `GamePak.kt` to instantiate new mapper
4. Add entry to `MAPPER_SUPPORT.md`
5. Test with known-good ROM
