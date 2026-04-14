# NES Emulator Development Knowledge

## Mapper 1 (MMC1/UxROM) PRG Banking

### Key Discovery: PRG Offset Calculation
When calculating PRG bank offsets, using `address - 0x8000` produces **negative values** for addresses >= 0x8000 in Kotlin's signed Int arithmetic. This causes out-of-bounds access.

**The Fix**: Use bit masking `address and 0x3FFF` to get the offset within a 16KB bank directly.

### Mode 3 Banking (NROM-256 Compatible After Reset)
- `$8000-$BFFF`: Fixed to first 16KB bank (PRG offset = `address and 0x3FFF`)
- `$C000-$FFFF`: Switchable bank (PRG offset = `bankOffset + (address and 0x3FFF)`)
  - where `bankOffset = (shiftRegister & 0x1F) % (prgRom.size / 16KB) * 16KB`

### MMC1 Reset Behavior
On CPU reset, Mapper 1 sets:
- `shiftRegister = 0x01`
- `prgMode = 3` ($C000 switchable, $8000 mirrors $C000)
- `chrMode = 0`
- `currentMirroring = initialMirroring`

### Why This Matters
In mode 3 with reset state (bank=1), reading `$FFFC` gives:
- `bankOffset = (1 % 2) * 16KB = 0x4000`
- `withinBankOffset = 0xFFFC and 0x3FFF = 0x3FFC`
- `PRG offset = 0x4000 + 0x3FFC = 0x7FFC` → correct reset vector bytes

**Before fix**: Using `offset = address - 0x8000` gave `prgRom[(0x4000 + 0x7FFC) % 0x8000] = prgRom[0x3FFC]` → wrong bytes

## NES Hardware Quirks

### PPU Warm-up Delay (NES-001)
After power-on, the PPU refuses register writes for approximately the first frame. Games must wait before configuring the PPU. This is NOT implemented in the current emulator, which may cause issues for games that expect this behavior.

### PPU Register Write Timing
The PPU has complex timing requirements:
- $2006 (VRAM address) and $2007 (PPU data) have specific timing requirements
- Palette writes at $3F00-$3F1F bypass the address increment that applies to other PPUDATA reads

### Mapper 1 Shift Register Behavior
- 5-bit shift register loaded LSB-first on CPU writes to $8000-$FFFF
- Bit 7 set = reset register (bit 4 = 1, clear bits 0-3)
- **Important**: Reset does NOT update prgMode - that only happens after 5 bits loaded

## CHR ROM/RAM Architecture

### Pattern Table Layout
- Pattern table 0: $0000-$0FFF (4KB)
- Pattern table 1: $1000-$1FFF (4KB)
- For 8KB CHR ROM: same data mirrors to both tables (NROM behavior)
- For 16KB+ CHR ROM: second 4KB goes to pattern table 1

### chrReadDelegate Pattern
For games with CHR RAM (no CHR ROM), the PPU reads pattern data through a callback:
```kotlin
ppuMemory.setChrReadDelegate { addr ->
    chrRam!![addr % 0x2000]
}
```
This is used in Mapper 1 when `chrRom.isEmpty()`.

## Donkey Kong vs Tetris: Mapper 0 vs Mapper 1

### Why Donkey Kong Works
- Mapper 0 (NROM) has no bank switching
- PRG ROM at $8000 is always PRG[0x0000-0x3FFF]
- Simple, direct mapping with no offset arithmetic issues

### Why Tetris (Mapper 1) Shows Black Screen
Even though PRG banking is now correct (PC=0xFF00), the screen remains black. Possible causes:
1. PPU warm-up delay not implemented (NES-001)
2. CHR bank not properly initialized (16KB CHR ROM split across pattern tables)
3. Timing issues with PPUCTRL/PPUMASK initialization
4. Missing mid-frame rendering enabling (PPUMASK shows 0 throughout)

## Test ROM Verification
- `nestest.nes`: Mapper 0, 16KB PRG, works correctly (golden log test passes)
- `tetris.nes`: Mapper 1, 32KB PRG, 16KB CHR ROM, reset vector $FF00
- `donkeykong.nes`: Mapper 0, 16KB PRG, works correctly

## Useful External Resources
- [NES ROM Database](https://nesdir.github.io/) - Lookup game mapper/checksum by CRC32 for any NES ROM