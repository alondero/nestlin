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
- **cpu/Opcodes.kt** - 6502 opcode implementations (151 opcodes including unofficial, 604 pages)
- **ppu/Ppu.kt** - Picture Processing Unit with full background/sprite rendering (~590 lines)
- **ppu/PpuAddressedMemory.kt** - PPU registers ($2000-$2007) and VRAM addressing
- **Memory.kt** - CPU memory map with proper mirroring
- **gamepak/GamePak.kt** - ROM loading and iNES format parsing
- **gamepak/Header.kt** - iNES header parsing including NO-INTRO ROM name detection
- **Apu.kt** - Audio Processing Unit with 5 channels (Pulse×2, Triangle, Noise, DMC)

### Audio Channels (apu/)
- **PulseChannel.kt** - Pulse wave generator (×2 channels)
- **TriangleChannel.kt** - Triangle wave generator
- **NoiseChannel.kt** - Pseudo-random noise generator
- **DmcChannel.kt** - Delta modulation channel
- **FrameCounter.kt** - APU frame sequencer
- **Envelope.kt** - Volume envelope generator
- **Sweep.kt** - Pulse sweep unit
- **LengthCounter.kt** - Sound length counter
- **AudioBuffer.kt** - Circular audio sample buffer
- **AudioResampler.kt** - Linear resampling for consistent audio rate

### Input (input/)
- **GamepadInput.kt** - Gamepad/controller input handling
- **InputConfig.kt** - Input configuration
- **JInputNatives.kt** - JInput native library loader for controller support

### UI & Testing
- **ui/Application.kt** - JavaFX UI with Canvas-based nearest-neighbor scaling (see `knowledge/javafx-pixel-scaling.md`), scale modes (1x-4x, Fit), fullscreen, and menus
- **GoldenLogTest.kt** - Validates CPU execution against nestest.log golden output
- **testroms/** - Test ROMs (nestest.nes for CPU validation)

### Developer Tools
- **dump_analyzer.py** - Parse 64KB NES CPU memory dumps (`.dmp` files) and query memory regions, registers, I/O state. Run from project root:
  ```python
  from dump_analyzer import parse_dump, analyze_multiple_dumps
  a = parse_dump("kirbyframe3-cpumem.dmp")
  a.cpu.summary()       # PC, SP, A, X, Y, P registers
  a.ppu.as_dict()       # PPU registers $2000-$2007
  a.apu.summary()       # APU registers $4000-$401F
  a.hexdump_region(0x6000, 0x6100)  # hex view of any address range
  ```
  Memory map: `$0000-$07FF` RAM, `$2000-$2007` PPU, `$4000-$401F` APU/I/O, `$8000-$FFFF` PRG ROM.

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

### Working
- CPU: 151 opcodes implemented (including unofficial opcodes) with proper addressing modes
- Memory system with correct mirroring and open-bus behavior
- ROM loading (iNES format, mapper 0)
- Golden log test framework (validates CPU execution)
- **Full PPU rendering**: Background with shift registers, attribute tables, palette indexing
- **Full sprite rendering**: Sprite evaluation, 8-sprite-per-scanline limit, sprite 0 hit detection
- **Full APU audio**: 5 channels (2 Pulse, Triangle, Noise, DMC) with proper mixing formulas
- **Controller input**: $4016/$4017 implemented with strobe functionality
- **Frame rate throttling**: Configurable speed throttling with toggle
- **NTSC and PAL timing**: Region auto-detected from the iNES/NES 2.0 header and NO-INTRO filename, with a manual override (Emulation → Region menu, or `--region=pal|ntsc`). PAL uses 312 scanlines, 50 Hz, the 3.2:1 PPU:CPU ratio, and PAL APU frame-counter/noise/DMC tables. All region constants live in `Region.kt`; subsystems read them via `Nestlin.applyRegion()`. Covered by `RegionDetectionTest`, `RegionTimingTest`, `PalApuTimingTest`, and validated end-to-end by `KickOffPalSmokeTest` (Kick Off (Europe) auto-detects PAL and boots to a rendered screen — rendering on by frame 10, PPUMASK $FE) and `GimmickPalBootTest` (Mr. Gimmick (Europe) boots to a rendered image under PAL).
- **Screenshot capture**: PNG screenshot saving with timestamp
- **File menu**: Load Game, Hard Reset Game, and Exit via UI menu
- **Window title**: Displays game name (NO-INTRO field or filename) or "Nestlin" when no game loaded

### Incomplete / Known Issues
- **Only mapper 0**: No support for MMC1, MMC3, or other mappers
- **CPU cycle timing**: Some opcodes may not have exact cycle counts
- **JInput controller support**: Native libraries need platform-specific setup

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

### Cross-Emulator Regression Testing

**Read `docs/TESTING_STRATEGY.md` first.** It defines the test pyramid Nestlin
follows, with explicit recipes per bug symptom in Section 6. The summary:

**Default to structured-state comparison, not pixel diffs.** Mesen2 exposes
`emu.getState()`, `emu.read(addr, nesPpuMemory)`, `nesSpriteRam`,
`nesSecondarySpriteRam`, `nesPaletteRam`, `nesNametableRam`, `nesChrRom`,
`mapper.chrMemoryOffset*`. Byte-compare these between Nestlin and Mesen2 at a
frame boundary — that's the workhorse layer. The infra is in
`src/test/kotlin/.../compare/`:

- `Mesen2StateCapturer` — Lua → JSON CPU/PPU/RAM/OAM/palette snapshot at frame N (headless `--testRunner`, ~0.5s).
- `NestlinStateCapturer` — same shape, in-process.
- `EmulatorStateSnapshot` + `StateComparator` — payload-agnostic diff.
- `Mapper*.snapshot()` — per-mapper bank/register/IRQ state (extend it when adding a new mapper).

**Hook-based behaviour assertions** are the structured replacement for
"compare pixels and hope" on cycle-sensitive bugs (MMC3 A12, NMI counts,
$2002 polling). Use `emu.addMemoryCallback(cb, "read", "nesPpuMemory", 0x1000, 0x1FFF, …)`.

**Pixel diffs are the last resort.** `ScreenshotComparisonTest` exists and works:
```bash
./gradlew test --tests ScreenshotComparisonTest
```
but a passing pixel diff tells you nothing about *why* something is right, and a
failing one buries the cause. Reach for it only when the bug is genuinely
visual (palette glitch, raster effect) and you've already ruled out the structured
levels. Don't add new pixel-diff cases — extend state diff coverage instead.

**Anti-patterns to avoid** (see strategy doc §2.4):
- `assumeTrue(mesen2Available)` silent skips (false-greens CI without the oracle).
- Tests that dump files to `build/` expecting a human to eyeball them — these aren't regression tests, they're debugging aides; if you write one, mark it clearly and don't put it under `test/`.
- Reflection into private `Cpu`/`Ppu` fields — break on rename. Expose a stable testing API instead.

**When you find a bug, follow Section 6's recipe table** for the cheapest test
that reproduces it. CHR-banking bugs are state diffs on `mapper.chrMemoryOffset*` and
`nesChrRom` — not screenshots (see Star Soldier `Mapper3Test` /
`StarSoldierMapper3RegressionTest` for the worked example).

**Mesen2 setup:** `MESEN2_PATH` env var, or absolute fallback to
`X:\src\nestlin\tools\Mesen2\Mesen.exe`. Local-only paths and gotchas live in
`CLAUDE.local.md` and the `mesen2-capture-harness-gotchas-*` memory entry.

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

### PPU Implementation Details
- **Shift registers shift every cycle**, but reload every 8 cycles
- **Attribute table addressing**: Uses coarse X/Y scroll to select 2×2 tile quadrant
- **Cycle timing is critical** - off-by-one errors cause visual glitches
- **V-blank flag** must be set at exact cycle (241, cycle 1)
- **Sprite evaluation** happens during cycles 257-320 for next scanline
- **Pre-loading**: First two tiles pre-loaded at cycle 0 before rendering begins

### CPU Edge Cases
- **BRK vs IRQ**: B flag handling differs (see ProcessorStatus.asByte())
- **JMP indirect bug**: 6502 has page boundary bug (see Opcodes.kt JMP indirect implementation)
- **RTS increments PC**: Returns to address+1, not exact return address
- **Decimal mode**: NES CPU ignores this flag (no BCD arithmetic)

### Memory Mirroring
- RAM mirrors every $0800 bytes
- PPU registers mirror every 8 bytes
- Nametables have complex mirroring (depends on cartridge)

### iNES ROM Format
- Standard iNES 1.0 ROMs: 16-byte header, no embedded ROM name
- NO-INTRO format: 16-byte header + 128-byte ROM name field at offset 0x10 (only when byte 7 has bit 4/$10 set)
- ROM files smaller than 144 bytes cannot have a NO-INTRO name field
- Display name fallback uses filename (without .nes/.7z extension) when NO-INTRO name not present

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
- JavaFX/OpenJFX (UI) via `org.openjfx.javafxplugin`
- Apache Commons Compress (for .7z ROMs)
- JUnit + Hamkrest (testing)

### IDE Setup
IntelliJ IDEA recommended:
- Import as Gradle project
- Kotlin plugin should auto-configure
- Run configurations: point to Application.kt main()

## Documentation Approach

### Permanent Documentation (CLAUDE.md)
Contains stable, enduring information:
- Architecture and file locations
- Code conventions and patterns
- Build commands and environment setup
- NES hardware fundamentals

### Ephemeral Status (todo.md, knowledge/)
Contains transient session state:
- **todo.md** - Current focus, what's been tried, hypotheses, next steps
- **knowledge/** - Novel discoveries and debugging findings

**Rule:** Don't put transient status in CLAUDE.md. It bloat every session's context window. Use todo.md or knowledge/ instead.

### Mapper Support (MAPPER_SUPPORT.md)
Documents mapper compatibility status:
- What mappers are implemented
- Known issues per mapper
- Test ROMs for each mapper

When adding a new mapper, update MAPPER_SUPPORT.md.
