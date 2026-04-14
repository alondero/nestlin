# NES Mapper Support Status

## Mapper 0 (NROM)
**Status:** Working

- **Games:** Donkey Kong, Super Mario Bros, Mega Man 1-6
- **Behavior:** No bank switching. PRG ROM at $8000 is always PRG[0x0000-0x3FFF]
- **Notes:** Simple, direct mapping. Full support.

## Mapper 1 (MMC1/UxROM)
**Status:** In Progress (Issues)

- **Games:** Tetris, Lolo 1, Chip 'n Dale
- **Behavior:**
  - 5-bit shift register for bank switching
  - PRG mode 3: $C000 switchable, $8000 mirrors $C000
  - CHR ROM loaded into pattern tables
- **Known Issues:**
  - Games show black screens
  - PPUMASK stays at 0 (rendering never enabled)
  - Root cause unknown (suspected PPU timing/VBlank issue)
- **PRG Banking Fix Applied:** PRG offset now calculated correctly using `address and 0x3FFF`

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
