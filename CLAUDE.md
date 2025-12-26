# Nestlin - NES Emulator in Kotlin

## Project Overview
An NES emulator written in Kotlin for learning purposes. The emulator simulates the Nintendo Entertainment System's CPU (Ricoh 2A03), PPU (Picture Processing Unit), and APU (Audio Processing Unit).

## Common Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run a specific test
./gradlew test --tests GoldenLogTest

# Run the emulator with a ROM
./gradlew run --args="testroms/nestest.nes"

# Run with debug logging
./gradlew run --args="testroms/nestest.nes --debug"

# Clean build artifacts
./gradlew clean
```

## Architecture & File Locations

### Core Components
- **Nestlin.kt** - Main emulator orchestration (CPU:PPU:APU tick ratio is 1:3:1)
- **cpu/Cpu.kt** - 6502 CPU implementation with registers and processor status
- **cpu/Opcodes.kt** - All 6502 opcode implementations (~90 opcodes, ~592 lines)
- **ppu/Ppu.kt** - Picture Processing Unit (scanline/cycle renderer)
- **ppu/PpuAddressedMemory.kt** - PPU registers ($2000-$2007) and VRAM addressing
- **Memory.kt** - CPU memory map with proper mirroring
- **gamepak/GamePak.kt** - ROM loading and iNES format parsing
- **Apu.kt** - Audio (currently a stub)

### UI & Testing
- **ui/Application.kt** - JavaFX UI with 256x224 canvas
- **GoldenLogTest.kt** - Validates CPU execution against nestest.log golden output
- **testroms/** - Test ROMs (nestest.nes for CPU validation)

## NES Architecture Essentials

### Timing
- CPU runs at 1.79 MHz (~559 ns per cycle)
- PPU runs 3x faster than CPU (3 PPU cycles per CPU cycle)
- Each frame = 262 scanlines × 341 cycles = 89,342 PPU cycles
- Runs at ~60 FPS (NTSC)

### Memory Maps

**CPU Address Space ($0000-$FFFF)**
- `$0000-$07FF`: 2KB internal RAM (mirrored to $1FFF)
- `$2000-$2007`: PPU registers (mirrored to $3FFF)
- `$4000-$4017`: APU and I/O registers
- `$4020-$FFFF`: Cartridge space (PRG ROM)
- `$FFFC-$FFFD`: Reset vector (program start address)

**PPU Address Space ($0000-$3FFF)**
- `$0000-$1FFF`: Pattern tables (CHR ROM - tile graphics)
- `$2000-$2FFF`: Nametables (4×1KB background maps)
- `$3F00-$3F1F`: Palette RAM (colors)

### PPU Rendering Phases (per scanline)
1. **Cycles 1-256**: Visible scanline - fetch tiles and render pixels
2. **Cycles 257-320**: Sprite evaluation for next scanline
3. **Cycles 321-336**: Fetch first 2 tiles for next scanline
4. **Cycles 337-340**: Unused fetches

Each tile fetch takes 8 cycles (4 memory accesses × 2 cycles each):
- Nametable byte (which tile)
- Attribute table byte (palette/color)
- Pattern table low byte (pixel bitmap bit 0)
- Pattern table high byte (pixel bitmap bit 1)

### PPU Registers (VRAM Address)
The 15-bit VRAM address register has this structure:
```
yyy NN YYYYY XXXXX
||| || ||||| +++++-- coarse X scroll (0-31)
||| || +++++-------- coarse Y scroll (0-29)
||| |+-------------- horizontal nametable select
||| +--------------- vertical nametable select
+++----------------- fine Y scroll (0-7)
```

## Code Style & Conventions

### Kotlin Guidelines
- Use `data class` for simple structs (Registers, ProcessorStatus)
- Extension functions for bit operations (isBitSet, clearBit, letBit)
- Operator overloading for memory access (`memory[addr]`)
- When/ranges for memory mapping (`in 0x0000..0x1FFF`)

### Naming Conventions
- CPU opcodes use descriptive names: `branchOp`, `storeOp`, `addToA`
- Addressing modes suffixed: `indirectX()`, `zeroPaged()`, `absoluteAdr()`
- PPU timing constants: `PRE_RENDER_SCANLINE`, `POST_RENDER_SCANLINE`

### Unsigned Integer Handling
Kotlin doesn't have unsigned primitives (pre-1.3), so use extension functions:
- `Byte.toUnsignedInt()` - Convert signed byte to 0-255 range
- `Short.toSignedShort()` / `Int.toSignedByte()` - Safe conversions
- Always mask with `and 0xFF` or `and 0xFFFF` after arithmetic

## Current Implementation Status

### ✅ Working
- CPU: ~90 opcodes implemented with proper addressing modes
- Memory system with correct mirroring
- ROM loading (iNES format, mapper 0)
- Golden log test framework (validates CPU execution)
- Basic PPU timing/cycle tracking
- VRAM address register logic

### ⚠️ Incomplete / Known Issues
- **PPU rendering is stubbed**: Currently renders random pixels (Ppu.kt:179-181)
- **Tile fetching incomplete**: fetchData() doesn't populate shift registers
- **No sprite rendering**: Structures exist but not used
- **CPU cycle timing inaccurate**: Many "TODO: Takes X cycles" comments
- **APU empty**: Just a stub
- **No controller input**: $4016/$4017 not implemented
- **Only mapper 0**: No support for MMC1, MMC3, etc.

## Testing Procedures

### Running the Golden Log Test
The test compares CPU execution against a known-good log from nestest.nes:

```bash
./gradlew test --tests GoldenLogTest
```

The test will fail when it encounters:
1. An unimplemented opcode (UnhandledOpcodeException)
2. A register mismatch (PC, A, X, Y, P, SP)
3. A flag discrepancy (zero, carry, negative, overflow)

**Test ROM Details:**
- Located at: `testroms/nestest.nes`
- Expected output: `src/test/resources/nestest.log`
- Runs in "automation mode" starting at $C000 instead of reset vector
- CRC32: 0x9e179d92

### Adding New Opcodes
1. Add to `Opcodes` map in init block (Opcodes.kt)
2. Use helper functions: `branchOp`, `storeOp`, `load`, `opWithA`, etc.
3. Set correct `workCyclesLeft` (check 6502 reference)
4. Run golden log test to verify

## Development Workflow

### Explore-Plan-Code Pattern
When adding significant features (like PPU rendering):
1. Read relevant files first without modifying
2. Create implementation plan in this file or comments
3. Implement incrementally with tests
4. Verify against test ROMs

### Branch Strategy
- Feature branches: `feature/ppu-rendering`, `feature/mapper1`
- Test after each logical unit (don't batch unrelated changes)
- Keep commits atomic and descriptive

## Important Warnings

### PPU Implementation Gotchas
- **Shift registers shift every cycle**, but reload every 8 cycles
- **Attribute table addressing is complex** - see comment at Ppu.kt:152
- **Cycle timing is critical** - off-by-one errors cause visual glitches
- **V-blank flag** must be set at exact cycle (241, cycle 1)

### CPU Edge Cases
- **BRK vs IRQ**: B flag handling differs (see ProcessorStatus.asByte())
- **JMP indirect bug**: 6502 has page boundary bug (implemented at Opcodes.kt:212)
- **RTS increments PC**: Returns to address+1, not exact return address
- **Decimal mode**: NES CPU ignores this flag (no BCD arithmetic)

### Memory Mirroring
- RAM mirrors every $0800 bytes
- PPU registers mirror every 8 bytes
- Nametables have complex mirroring (depends on cartridge)

## Useful References
- [NESdev Wiki](https://www.nesdev.org/wiki/) - Comprehensive NES documentation
- [6502 Instruction Reference](http://www.6502.org/tutorials/6502opcodes.html) - Opcode details
- [PPU Rendering](https://www.nesdev.org/wiki/PPU_rendering) - Scanline timing
- [nestest.nes Guide](https://www.qmtpro.com/~nes/misc/nestest.txt) - Test ROM info

## Environment Setup

### Requirements
- Kotlin 1.1.0
- Gradle (wrapper included)
- Java 8+ (for JavaFX)

### Dependencies
- kotlin-stdlib 1.1.0
- TornadoFX (JavaFX wrapper)
- Apache Commons Compress (for .7z ROMs)
- JUnit + Hamkrest (testing)

### IDE Setup
IntelliJ IDEA recommended:
- Import as Gradle project
- Kotlin plugin should auto-configure
- Run configurations: point to Application.kt main()
