# Mapper Implementation Details

## iNES File Format

### Header Structure (16 bytes)

- **Bytes 0-3**: Constant "NES" followed by EOF character ($1A)
- **Byte 4**: PRG ROM size in 16 KB units
- **Byte 5**: CHR ROM size in 8 KB units (0 = CHR RAM)
- **Byte 6**: Configuration flags 1
  - Bits 0-3: Mapper number (low nibble)
  - Bit 1: CIRAM A10 (0=vertical, 1=horizontal mirroring)
  - Bit 2: Battery-backed RAM present
  - Bit 3: Trainer present (512 bytes at file offset 16)
- **Byte 7**: Configuration flags 2
  - Bits 4-7: Mapper number (high nibble)
  - Bits 0-3: Console type / system (0=NES, 1=Arcade, 2=VT02, etc.)
- **Bytes 8-15**: Padding (should be $00)

### File Organization

1. **Trainer** (optional, 512 bytes) - if byte 6 bit 3 set
2. **PRG ROM data** (size from header byte 4 × 16 KB)
3. **CHR ROM data** (size from header byte 5 × 8 KB)
4. **PlayChoice data** (optional for arcade cabinets)

### Mapper Number Encoding

```
mapper = ((header[7] & 0xF0) << 4) | ((header[6] & 0xF0) >> 4)
```

Lower nibble from byte 6 bits 4-7, upper nibble from byte 7 bits 4-7.

## Mapper 0 (NROM) - No Bank Switching

- **Maximum size**: 40 KB (32 KB PRG + 8 KB CHR)
- **Features**: None (simplest mapper)
- **Memory layout**:
  - $6000-$7FFF: Unused (optional cartridge RAM)
  - $8000-$FFFF: PRG ROM (mirrors if <32 KB)

## Mapper 1 (MMC1) - Shift Register Interface

### Protocol

Mapper uses serial shift register protocol (reduces pin count):

- Writes to $8000-$FFFF send data bit-by-bit
- Writing $80+ (bit 7 set) clears shift register and resets state
- Five sequential writes with bit 7 clear accumulate 5-bit value
- Only lowest bit of each write matters

### Register Organization

- **$8000-$9FFF** (Register 0): Control register
- **$A000-$BFFF** (Register 1): CHR bank 0
- **$C000-$DFFF** (Register 2): CHR bank 1
- **$E000-$FFFF** (Register 3): PRG bank and RAM control

### Control Register ($8000-$9FFF)

- **Bits 0-1**: Mirroring mode
  - 0 = one-screen lower (use lower nametable for all quadrants)
  - 1 = one-screen upper (use upper nametable for all quadrants)
  - 2 = vertical (use pair of nametables for left/right)
  - 3 = horizontal (use pair of nametables for top/bottom)
- **Bit 2**: PRG bank mode
  - 0 = 32 KB mode (single swappable bank)
  - 1 = 16 KB mode (two swappable banks)
- **Bit 3**: CHR bank mode
  - 0 = 8 KB mode (single swappable bank)
  - 1 = dual 4 KB mode (two swappable 4 KB banks)
- **Bit 4**: PRG-RAM enable (varies by chip revision)

### Default Memory Layout

- **PRG**: $8000-$BFFF (16 KB switchable), $C000-$FFFF (16 KB fixed to last bank)
- **CHR**: Two 4 KB banks or one 8 KB bank
- **PRG-RAM**: Optional 8 KB at $6000-$7FFF

### Critical MMC1 Quirks

#### Consecutive-Cycle Ignoring
- Mapper ignores writes on consecutive CPU cycles
- Occurs during read-modify-write instructions (INC, DEC, ROR, ROL, etc.)
- **Example**: `INC $8000` does three accesses (read, modify, write) but shift register only processes non-consecutive writes
- **Game exploit**: Bill & Ted's Excellent Adventure uses this for reset behavior

#### Multiple Chip Revisions
At least 7 versions exist (MMC1A, MMC1B, MMC1B1, MMC1C, etc.) with differences:
- Bit 4 register $E000 behavior varies
- Affects PRG-RAM enable/disable and PRG-ROM addressing
- Some versions have different power-up states

#### Board Variants
- **SNROM**: PRG-ROM and RAM configurations
- **SOROM**: Extended ROM support
- **SXROM**: Can support 512 KB PRG-ROM or multiple RAM banks
- Physical boards repurpose address lines for additional capacity

## Mapper 3 (CNROM) - Simple CHR Bank Switching

- **Features**: CHR bank switching only
- **Address range**: $8000-$FFFF
- **Registers**: Single register (lower bits select CHR bank)
- **Typical layout**: 32 KB PRG (fixed), switchable CHR (8 KB at a time)

## Mapper 4 (MMC3) - Advanced Bank Switching with Scanline IRQ

### Register Interface

Paired register address scheme:

- **$8000-$9FFF** (even/odd): Bank select and data
- **$A000-$BFFF** (even/odd): Mirroring
- **$C000-$DFFF** (even/odd): IRQ latch and counter reload
- **$E000-$FFFF** (even/odd): IRQ disable/enable

### Bank Select Register ($8000)

- **Bits 0-2**: Select which register to configure (R0-R7)
- **Bit 6**: CHR/PRG addressing mode inversion
  - 0 = R0-R1 for 2 KB, R2-R5 for 1 KB, R6-R7 for 8 KB
  - 1 = Inverted mode (swaps bank sizes)
- **Bit 7**: PRG banking mode
  - 0 = R6 swappable at $8000, R7 fixed to $E000
  - 1 = R6 fixed to $8000, R7 swappable at $E000

### Banking Modes

**R0-R5** (CHR banks):
- Normal mode: R0-R1 select two 2 KB banks, R2-R5 select four 1 KB banks
- Inverted mode: R0-R5 select six 1 KB banks (R6-R7 unused)

**R6-R7** (PRG banks):
- Normal mode: 16 KB PRG banks (8 KB each half is selected separately)
- Top 2 bits ignored (6 PRG ROM address lines only)

### PRG Memory Layout

- **$8000-$9FFF**: 8 KB (R6/R7 per bit 7)
- **$A000-$BFFF**: 8 KB (opposite of R6/R7)
- **$C000-$DFFF**: 8 KB (R6/R7 per bit 7)
- **$E000-$FFFF**: Fixed to last 8 KB bank

### IRQ Mechanism (Scanline Counter)

**Based on PPU A12 rising edge detection**:

- When PPU A12 rises: Counter either reloads from latch or decrements
- IRQ fires when counter reaches zero (if enabled)
- Latch ($C000) sets reload value
- Counter ($C001) initiates reload on next A12 rise

**Chip Revisions**:
- **Sharp MMC3**: Generates IRQ every scanline when latch = $00
- **NEC MMC3**: Generates single IRQ when latch = $00

**Critical for accurate implementation**:
- PPU A12 behavior affects MMC3 IRQ counting
- Pre-render scanline A12 transitions must be tracked
- Games like StarTropics require cycle-perfect IRQ timing

## Mapper Selection Priority

### For MVP Emulator
- Mapper 0 (NROM) - required for basic functionality
- Most early NES games use this

### For Compatibility
- Mapper 1 (MMC1) - most flexible and widely used
- Mapper 3 (CNROM) - simple bank switching
- Mapper 4 (MMC3) - advanced but critical for many games

### For Extended Support
- VRC mappers (Konami) - expansion audio
- MMC5 - advanced features, expansion audio
- Additional discrete logic mappers

## Implementation Strategy

1. **Detect mapper** from iNES header
2. **Load ROM banks** into memory
3. **Implement bank switching** via write handlers at mapper address ranges
4. **Handle mirroring** via cartridge or mapper control
5. **Generate IRQ** for mappers supporting scanline counting
6. **Track PPU A12** for MMC3 and similar mappers

## Common Game Requirements

- **MMC1 games**: Bill & Ted, Castlevania, Metroid, Legend of Zelda
- **MMC3 games**: Star Tropics, Crystalis, Kirby's Adventure
- **NROM games**: Super Mario Bros., Donkey Kong, Pac-Man
- **CNROM games**: Ghouls'n Ghosts, Gradius

See main skill for more details on games requiring accurate implementations.
