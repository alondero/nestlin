# Hardware Quirks and Edge Cases

## PPU Errata and Bugs

### Sprite 0 Hit Failure at X=255

- Sprite 0 hit detection fails when sprite positioned at X=255
- Flag never sets even with proper overlapping conditions
- Games must avoid this position or accept missing hit detection
- Not fixable in software (hardware limitation)

### Sprite Overflow Bug

- Sprite overflow detection fundamentally broken
- Hardware glitch in sprite evaluation circuitry
- Counts wrong number of sprites per scanline
- Flag behavior unpredictable and implementation-dependent
- Cannot be relied upon for accurate emulation

### Palette Corruption

- Toggling rendering mid-screen corrupts palette RAM
- Occurs when rendering is enabled/disabled during palette RAM access
- **Critical detail**: Palette RAM at $3F00-$3F1F is affected
- Games using mid-screen palette changes must time carefully

### Scroll Glitches

- Writing PPUSCROLL at precise timing windows causes artifacts
- Related to shift register operation timing
- Shift register reloads every 8 cycles, creating timing windows
- Affects fine scrolling and pixel-exact positioning

### OAM Decay (Especially PAL)

- Sprite data corrupts if rendering disabled too long
- OAM uses dynamic RAM requiring refresh cycles
- NTSC systems tolerate longer without refresh
- **PAL systems**: Forced refresh needed to prevent corruption
- Critical for accurate PAL emulation

### NES-001 Startup Quirk (Frontloader Only)

- After power-on/reset, PPU refuses register writes for ~1 frame
- Affects PPUCTRL ($2000), PPUMASK ($2001), PPUSCROLL ($2005), PPUADDR ($2006)
- Writes during startup period are silently ignored
- Games must wait for first NMI or use delay loop before writing registers
- Not present on NES-101 (toploader)

### Palette Address Mirroring

- Palette RAM address $3F10 mirrors $3F00 (backdrop color)
- Palette RAM address $3F14 mirrors $3F04
- Palette RAM address $3F18 mirrors $3F08
- Palette RAM address $3F1C mirrors $3F0C
- **Critical**: Some games rely on this mirroring behavior

## CPU Edge Cases

### JMP Indirect Bug (Page Boundary)

```assembly
JMP ($1FFF)  ; BUG: Reads low from $1FFF, high from $1F00 (not $2000)
```

- 6502 inherited quirk, also present in 2A03
- When target address crosses page boundary, low byte wraps
- Examples:
  - JMP ($1FFF): Low from $1FFF, high from $1F00
  - JMP ($2FFF): Low from $2FFF, high from $2F00
- Games relying on this behavior: Direct3D effect emulation

### Zero Page Address Wrapping

- Indexed addressing modes wrap within zero page
- **Example**: `LDA ($FF,X)` with X=1 reads address from ($00,$01), not ($FF,$00)
- Affects indirect indexed modes: ($dd,X) and ($dd),Y
- Critical for correct memory access in some games

### Unofficial (Illegal) Opcodes

- **Stable**: Execute reliably on 2A03 (SLO, LAX, DCP, etc.)
- **Less stable**: Minor timing differences between chip batches (XAA, ANE, etc.)
- **All 256 opcodes execute** (no undefined opcodes on NES)
- Fewer games rely on these than on original 6502 (Famicom compatibility)

**Exploitations**:
- **Shinsenden**: Uses illegal instruction to trigger mapper reset
- Various puzzle games: Use undocumented timing behaviors

### Decimal Mode (D Flag)

- D flag is **observable** but **non-functional**
- Unlike original 6502, 2A03 lacks BCD arithmetic hardware
- ADC/SBC ignore decimal mode and perform binary arithmetic
- Some games use D flag as general-purpose storage

### DMC DMA Interference with Controller Reads

- DMC samples cause CPU bus stalls
- Can interfere with controller reads via DMA cycles
- **Effect**: May force certain bits high (weak pullup effects)
- **Workaround**: Games sometimes read controllers multiple times
- **Game example**: Games using both DMC and controller input

### Stack Pointer Edge Cases

- Stack wraps within page $01 (addresses $0100-$01FF)
- SP decrements for PUSH, increments for POP
- Starting value after reset: $FD
- Stack overflow wraps to $01FF (highest address in stack page)

## APU Quirks

### Sweep Unit Behavior

- Differs between pulse channels in subtle ways
- Specific sweep sequences cause inaudible clicks
- Writing period value mid-note causes audible artifacts
- **Critical timing**: Sweep updates on frame counter steps

### DMC Sample Alignment

- Samples must reside in CPU address space $C000-$FFFF
- Sample address wraps from $FFFF to $8000 (not $C000)
- Wraparound behavior critical for long samples
- Affects games with extensive audio samples

### Frame Counter Timing

- Clocked at ~240 Hz (every ~7,457 NTSC cycles)
- Mode 0 (4-step): Has optional IRQ at step 3
- Mode 1 (5-step): No IRQ, additional step
- Writing $4017 affects immediate behavior (timing-dependent)

## Input Device Quirks

### Open Bus Behavior

**Core concept**: Reading unmapped CPU addresses returns previously-read bus data

- **Absolute addressing**: Returns high byte of address operand
- **Indexed addressing**: Returns high byte of base address (pre-index)
- **Indirect addressing**: Returns high byte of pointer value

**Controller ports ($4016-$4017)**:
- Bits 4-0 contain controller data when read
- Bits 5-7 return open bus decay state
- **Game requirement**: Mindscape titles expect exactly $41

### DMC-Controller Interaction

- DMC DMA cycles can interfere with button readout
- Sequential reading affected by DMC timing
- **Result**: Controller may appear to skip buttons
- **Workaround**: Read multiple times or disable DMC during input

## Cartridge Issues

### Bus Conflicts

- Some mapper boards have competing outputs on same pins
- Occurs when cartridge ROM and mapper registers share address space
- **Result**: Data corruption and unpredictable behavior
- **Prevention**: Use mappers with proper address decoding

### ROM Mirroring Issues

- Small ROM sizes mirror into upper address space
- **Example**: 16 KB PRG mirrors to fill 32 KB space
- Incorrect mirroring causes games to access wrong banks
- Especially critical for mapper 0 implementations

### Trainer Data

- iNES header bit 3 indicates 512-byte trainer
- Trainer loads at $7000-$71FF in CPU address space
- Games using trainers overwrite game code on load
- Emulator must handle correctly or games won't work

## Memory Access Patterns

### OAM DMA Timing Quirk

- Write to $4014 transfers 256 bytes from CPU RAM to OAM
- Takes ~513 CPU cycles (257 read-write pairs)
- **Extra cycle**: If write occurs during odd CPU cycle, additional cycle added
- **Critical**: Exact timing affects sprite rendering on next scanline

### VRAM Access During Rendering

- PPUADDR and PPUDATA writes forbidden during rendering
- Reading from VRAM safe but buffered (delayed response)
- Palette reads return immediately (not buffered)
- **Game technique**: Disable rendering before VRAM updates

## Game-Specific Edge Cases

### Battletoads

- Demands precise CPU-PPU cycle timing
- Cycle penalties critical for correct execution
- Requires robust sprite 0 detection
- Hangs on first stage without proper timing

### StarTropics

- Requires **exact** MMC3 IRQ timing
- Even 1-2 cycle delays cause sprite flickering
- Palette changes synchronized to scanline IRQ
- Tests for accurate emulation

### Super Mario Bros.

- Correct palette mirroring required
- Sprite 0 detection essential
- 1-byte PPUDATA read delay critical
- Title screen freezes without proper PPUDATA buffering

### Marble Madness / Pirates

- Mid-scanline CHR bank switching
- Requires fairly precise cycle-accurate timing
- Visual glitches on timing errors

### Crystalis

- MMC3 scanline IRQ for vertical map splits
- Incorrect IRQ timing creates visible seam during gameplay
- Tests for accurate scanline counting

## Implementation Priorities

### Must Have (Basic Compatibility)
- Correct CPU instruction cycle counts
- Basic PPU rendering (even if not pixel-perfect)
- Mapper 0 and Mapper 1 support
- VBlank timing for synchronization

### Should Have (Good Compatibility)
- Accurate sprite 0 hit detection (except X=255)
- Correct tile fetch timing
- Proper OAM DMA handling
- Mapper 4 (MMC3) support
- Controller input handling

### Nice to Have (High Accuracy)
- Cycle-perfect CPU-PPU coordination
- All hardware quirks (palette corruption, OAM decay)
- PPU startup delays (NES-001)
- Unofficial opcode support
- DMC DMA interference

### Advanced (Extreme Accuracy)
- Multiple chip revision differences (MMC1A vs B)
- PAL vs NTSC timing differences
- DMC sample wraparound edge cases
- Open bus decay simulation
- Sound channel implementation
