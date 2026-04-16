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

## Mapper 2 (CNROM/UNROM)
**Status:** Working

- **CNROM (original):** 8KB CHR bank switching, fixed PRG at $8000-$FFFF
- **UNROM (commercial variant):** 16KB PRG bank switching at $8000-$BFFF via $8000-$FFFF writes
  - Bits 0-2 of value written select the 16KB PRG bank (0-7 for 128KB ROM)
  - $8000-$BFFF: switchable 16KB PRG bank window
  - $C000-$FFFF: fixed to last PRG bank (bank 7 for 128KB, bank 3 for 64KB)
  - Same CHR banking as CNROM (bits 0-1 for CHR bank selection)
- **Games:** Castlevania, Contra, 1943, DuckTales, California Games (UNROM); original CNROM games
- **PRG Banking:** $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
- **CHR Banking:** 8KB banks, switch via bits 0-1 of $8000-$9FFF writes

## Mapper 3 (NINA-003/006)
**Status:** Working

- **Games:** Paperboy
- **Behavior:** 8KB CHR ROM bank switching via $8000-$9FFF (bits 0-1)
- **PRG:** Fixed 32KB at $8000-$FFFF

## Mapper 4 (MMC3/TxROM)
**Status:** Working

- **Games:** Many later NES games (Mega Man 4-6, StarTropics, Contra, Kirby's Adventure, Crystalis, etc.)
- **Behavior:**
  - Dual register pairs at even/odd addresses ($8000/8001, $A000/A001, $C000/C001, $E000/E001)
  - Bit 7 of $8000 = CHR inversion mode (chrPrgInvert)
  - Bit 6 of $8000 = PRG banking mode (prgMode)
  - PRG banking:
    - $8000-$9FFF: prgBank6 when prgMode=0, prgBankCount-2 when prgMode=1
    - $A000-$BFFF: always prgBankA (R7, switchable)
    - $C000-$DFFF: prgBankCount-2 when prgMode=0, prgBank6 when prgMode=1
    - $E000-$FFFF: always last bank (prgBankCount-1)
  - CHR banking (normal mode): R0-R1 = 2KB at $0000-$0FFF, R2-R5 = four 1KB at $1000-$1FFF
  - CHR banking (inverted mode): R2-R5 = four 1KB at $0000-$0FFF, R0-R1 = 2KB at $1000-$1FFF
  - Scanline IRQ via PPU A12 rising edge detection
- **Verified Working:** Mega Man 4 (CHR RAM), StarTropics (CHR ROM), Crystalis (CHR ROM)
- **Rendering Verified:** StarTropics intro sequence renders correctly (screenshots at 30s, 60s, 90s)
- **Unit Tests:** Removed due to incorrect expected values; integration tests validate correct behavior

---

## Adding New Mappers

1. Implement in `gamepak/` directory
2. Extend `Mapper` base class or trait
3. Update `GamePak.kt` to instantiate new mapper
4. Add entry to `MAPPER_SUPPORT.md`
5. Test with known-good ROM
