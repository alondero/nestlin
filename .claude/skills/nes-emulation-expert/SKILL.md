---
name: nes-emulation-expert
description: Provides comprehensive NES/Famicom architecture expertise for emulator development, debugging, and feature implementation. Covers 6502 CPU, PPU rendering, memory systems, and APU. Use when developing NES emulators, debugging behavior discrepancies, identifying unimplemented features, or planning emulation components.
---

<objective>
Guide NES emulator development with authoritative technical knowledge of CPU, PPU, memory, and APU. Use when developers need to understand how NES hardware works, debug issues, and plan features.
</objective>

<quick_start>
**Identify the problem area**, then navigate to the appropriate reference:

**CPU Issues**: 6502 processor, instruction timing, interrupts, flags
→ See [references/cpu-detailed.md](references/cpu-detailed.md)

**PPU Issues**: Graphics rendering, scrolling, sprite priority, timing
→ See [references/ppu-detailed.md](references/ppu-detailed.md)

**Mapper Issues**: Bank switching, ROM loading, mirroring, scanline IRQ
→ See [references/mappers-detailed.md](references/mappers-detailed.md)

**Hardware Quirks**: Edge cases, bugs, game-specific issues
→ See [references/hardware-quirks.md](references/hardware-quirks.md)

**Key Facts**: The NES CPU (2A03) runs at 1.79 MHz, PPU runs 3x faster (3:1 ratio). Frame = 89,342 PPU cycles. Vblank = ~2,273 CPU cycles. Every CPU cycle is a read or write (no idle cycles).
</quick_start>

<critical_bugs>
These issues cause real problems:

**Interrupt Hijacking**: NMI during first 4 ticks of BRK instruction hijacks execution to NMI vector.

**Sprite 0 Hit at X=255**: Sprite 0 hit detection fails when sprite at X=255. Games must avoid this position.

**Sprite Overflow Bug**: Hardware detection fundamentally broken. Flag behavior unreliable.

**PPU Startup Delay (NES-001)**: After power-on, PPU refuses register writes for ~1 frame. Games must wait before configuring.

**MMC1 Consecutive-Cycle Ignoring**: Mapper ignores writes on consecutive CPU cycles (read-modify-write operations).

**MMC3 Revision Differences**: Sharp MMC3 generates IRQ every scanline when latch=$00, NEC only generates single IRQ.

**VRAM Address Wraparound**: Coarse Y wraps at 30 (not 32). Values 30-31 produce special attribute behavior.

**Palette Data Buffering**: PPUDATA reads return previously buffered data (delayed 1 read), except palette reads.

**OAM DMA Extra Cycle**: DMA takes ~513 cycles + 1 extra if write occurs on odd CPU cycle.
</critical_bugs>

<common_issues>
**"Game won't run"**
- Check mapper support (mapper 0/1/4 most common)
- Verify reset vector pointing to correct address
- Confirm ROM loading at correct addresses

**"Graphics glitched"**
- Sprite 0 hit timing (watch for X=255 edge case)
- Palette corruption from mid-screen rendering changes
- Scroll register desynchronization (write toggle state)
- OAM DMA timing affecting sprite data

**"Audio weird/timing off"**
- Check APU frame counter sequencing
- Verify DMC DMA doesn't interfere with other operations
- Confirm CPU-PPU cycle synchronization

**"Input not working"**
- DMC DMA can interfere with controller reads
- Check open bus behavior on unmapped addresses
- Verify strobe signal handling

**"Cycle timing issues"**
- Page boundary crossing adds cycle to reads
- Store instructions always add cycle on page crossing
- Branch instructions add cycle if taken
- Interrupts polled at END of instruction
</common_issues>

<memory_maps>
**CPU Address Space** ($0000-$FFFF):
- $0000-$07FF: 2KB RAM (mirrors to $1FFF)
- $2000-$2007: PPU registers (mirrors to $3FFF)
- $4000-$4017: APU/I/O registers
- $6000-$7FFF: Battery RAM (optional)
- $8000-$FFFF: ROM/Mapper

**PPU Address Space** ($0000-$3FFF):
- $0000-$1FFF: Pattern tables (CHR ROM/RAM)
- $2000-$2FFF: Nametables (1 KB each × 4)
- $3F00-$3F1F: Palette RAM (32 bytes)

**Interrupt Vectors**:
- $FFFA-$FFFB: NMI
- $FFFC-$FFFD: Reset
- $FFFE-$FFFF: IRQ/BRK
</memory_maps>

<implementation_order>
**MVP (Minimum Viable Emulator)**:
1. CPU with instruction timing
2. RAM, ROM loading, basic memory map
3. PPU basic rendering (even if stubbed)
4. Mapper 0 (NROM) support
5. VBlank timing/NMI
6. Basic controller input

**Compatibility (fixes most games)**:
1. Accurate PPU rendering (scanlines, cycles)
2. Sprite 0 hit detection (except X=255)
3. Correct tile fetch and shift register timing
4. Mapper 1 (MMC1) support
5. Palette and attribute table handling
6. OAM DMA cycle-accurate handling

**Accuracy (high compatibility)**:
1. Cycle-perfect CPU-PPU coordination
2. All hardware quirks and edge cases
3. Mapper 4 (MMC3) with scanline IRQ
4. Interrupt hijacking and delayed response
5. Open bus behavior
6. PPU startup delays (NES-001)

**Advanced (extreme accuracy)**:
1. Mapper revisions and variants
2. PAL vs NTSC timing differences
3. Full APU with all envelope behaviors
4. Multiple chip revisions (MMC1A vs B)
5. Palette corruption simulation
</implementation_order>

<reference_documents>
Detailed specifications and implementation guides:

- **[cpu-detailed.md](references/cpu-detailed.md)** - All 13 addressing modes, flag behaviors, interrupt timing, unofficial opcodes
- **[ppu-detailed.md](references/ppu-detailed.md)** - Register documentation, VRAM addressing, mirroring modes, shift registers, sprite system
- **[mappers-detailed.md](references/mappers-detailed.md)** - iNES format, MMC1/MMC3 details, bank switching, chip revisions
- **[hardware-quirks.md](references/hardware-quirks.md)** - PPU errata, CPU edge cases, game-specific requirements

Each document provides deep technical detail for its topic area.
</reference_documents>

<external_resources>
- **[NESdev Wiki](https://www.nesdev.org/wiki/)** - Authoritative documentation
- **[6502 Opcode Reference](https://www.oxyron.de/html/opcodes02.html)** - Instruction details with cycle counts
- **[Test ROM Collection](https://github.com/christopherpow/nes-test-roms)** - Validation tests
- **Reference Emulators**: [TetaNES](https://github.com/lukexor/tetanes), [LaiNES](https://github.com/AndreaOrru/LaiNES), [ANESE](https://github.com/daniel5151/ANESE)
</external_resources>

<success_criteria>
This skill is effective when:
- You quickly find answers to CPU, PPU, memory questions
- Reference documents provide exact technical specs when needed
- Critical bugs and edge cases are documented
- Implementation order guides development priorities
- Games run with expected behavior (no crashes, correct graphics/sound)
</success_criteria>
