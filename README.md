# Nestlin
An NES emulator written in Kotlin.

## Building

```bash
./gradlew build
```

This produces a runnable distribution in `build/distributions/`.

## Running

```bash
# Run the emulator
./build/distributions/nestlin/bin/nestlin path/to/rom.nes

# Run tests
./gradlew test
```

## Controls

**Menu**
- **File → Load Game...** (Ctrl+O): Load a new ROM
- **File → Hard Reset Game** (Ctrl+R): Reload and restart the current ROM
- **File → Exit** (Ctrl+Q): Exit the emulator

**Keyboard**
- **S**: Capture screenshot
- **Ctrl+T**: Toggle frame rate throttling

## Supported Formats

- `.nes` - iNES ROM format
- `.7z` - 7-Zip archives containing ROM files
- **Mapper 0** (NROM) and **Mapper 1** (MMC1) supported

## Key Mappings

Key mappings are configurable in `~/.config/nestlin/input.json`
