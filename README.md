# Nestlin
An NES emulator written in Kotlin for learning purposes. The emulator simulates the Nintendo Entertainment System's CPU (Ricoh 2A03), PPU (Picture Processing Unit), and APU (Audio Processing Unit).

## Features
- **6502 CPU**: 151 opcodes implemented (including unofficial opcodes)
- **Full PPU rendering**: Background with shift registers, attribute tables, palette indexing
- **Full sprite rendering**: Sprite evaluation, 8-sprite-per-scanline limit, sprite 0 hit detection
- **Full APU audio**: 5 channels (2 Pulse, Triangle, Noise, DMC)
- **Controller input**: Gamepad and keyboard support
- **Mapper support**: Mapper 0 (NROM) and Mapper 1 (MMC1)
- **File menu**: Load Game, Hard Reset Game, Exit

## Running

```bash
# Build the project
./gradlew build

# Run the emulator with a ROM
./gradlew run --args="path/to/rom.nes"

# Run with debug logging
./gradlew run --args="path/to/rom.nes --debug"

# Run tests
./gradlew test
```

## Controls

### Menu
- **File → Load Game...** (Ctrl+O): Load a new ROM
- **File → Hard Reset Game** (Ctrl+R): Reload and restart the current ROM
- **File → Exit** (Ctrl+Q): Exit the emulator

### Keyboard
- Configurable key mappings in `~/.config/nestlin/input.json`
- **S**: Capture screenshot
- **Ctrl+T**: Toggle frame rate throttling

## Supported Formats
- `.nes` - iNES ROM format
- `.7z` - 7-Zip archives containing ROM files

## Architecture
- **CPU**: Ricoh 2A03 (MOS 6502 variant without decimal mode)
- **PPU**: Picture Processing Unit with 256×240 resolution
- **APU**: Audio Processing Unit with 5 channels
- **Timing**: CPU 1.79 MHz, PPU 3× faster (1:3:1 tick ratio)

## Documentation
- [CLAUDE.md](CLAUDE.md) - Detailed architecture and development documentation
- [MAPPER_SUPPORT.md](MAPPER_SUPPORT.md) - Mapper compatibility status
