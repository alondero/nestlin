# User guide

This page is for people running Nestlin for the first time or configuring an existing installation.

## What Nestlin is

Nestlin is a from-scratch Nintendo Entertainment System emulator written in Kotlin. It implements the 6502 CPU, 2C02 PPU, 2A03 APU, cartridge mappers, input, save states, battery-backed save RAM, and NTSC/PAL timing in this repository. It is a personal learning project, not a production compatibility promise.

The compatibility source of truth is [`MAPPER_SUPPORT.md`](../MAPPER_SUPPORT.md). A mapper being listed means that its cartridge logic exists; it does not mean every game using that board is fully playable. Mapper 5 is explicitly a stub, and game-level compatibility still needs a tested ROM and a reproducible result.

## Requirements

- JDK 21. The Gradle toolchain is pinned to Java 21.
- A desktop display server for the JavaFX application.
- A legally obtained NES ROM in iNES or NES 2.0 format. Nestlin does not distribute commercial ROMs.
- Optional: a JInput-supported gamepad.

## Launching a game

From the repository root:

```bash
./gradlew run --args="path/to/game.nes"
```

Windows:

```bat
gradlew.bat run --args="path\\to\\game.nes"
```

The convenience wrappers `nestlin.sh` and `nestlin.bat` build and launch the emulator. A built fat JAR can also be run directly:

```bash
java -jar build/libs/nestlin-all.jar path/to/game.nes
```

Supported input files are `.nes` files with iNES or NES 2.0 headers and single-ROM `.7z` archives.

Useful launch flags:

| Flag | Effect |
| --- | --- |
| `--debug` | Print verbose CPU instruction logging. |
| `--region=ntsc` or `--region=pal` | Override automatic region detection. |
| `--no-audio` | Disable audio output. |

## Controls

| Action | Default |
| --- | --- |
| NES A / B | `Z` / `X` |
| Select / Start | `Space` / `Enter` |
| D-pad | Arrow keys |
| Quick save / load | `F5` / `F8` |
| Pause | `Ctrl+P` |
| Toggle 60 FPS throttle | `Ctrl+T` |
| Fast-forward while held | `Tab` |
| Rewind while held | `Backspace` |
| Screenshot | `S` |
| Fullscreen | `F11` |

Keyboard and gamepad bindings are stored in `~/.config/nestlin/input.json` and the default file is created on first run. The exact path follows the host user's home directory; do not commit this file.

## Saves and files

- Quick save states use `.nstl` files under `savestates/` by default.
- Battery-backed cartridge RAM uses `.sav` files under `saves/`.
- Screenshots are written under `screenshots/`.
- These generated files are ignored by Git. Copy them elsewhere before deleting a checkout.

Save states are emulator snapshots, not interchangeable with another emulator's save-state format. Battery-backed `.sav` behavior is intended to be compatible with common NES emulator layouts, but a backup is recommended before testing an unfamiliar dump.

## RetroAchievements

RetroAchievements is optional, softcore-only, and disabled when its native capability is unavailable. The default build does not require a network connection; sign-in and achievement services are described in [`RA_INTEGRATION.md`](../RA_INTEGRATION.md). The native library may be built locally or fetched as a release asset when building an `uberJar`. The emulator falls back to a no-op service when that library is absent.

Do not include RetroAchievements credentials, tokens, or private account data in bug reports. For the manual acceptance flow, see [`docs/MANUAL_RA_ACCEPTANCE.md`](MANUAL_RA_ACCEPTANCE.md).

## ROM and support policy

Nestlin does not provide copyrighted ROMs, BIOS files, or download links. A useful compatibility report identifies the exact dump without uploading it: filename, mapper/submapper, region, CRC32 or another checksum, Nestlin version/commit, and the first reproducible symptom. See the [issue templates](../.github/ISSUE_TEMPLATE/) and [troubleshooting guide](TROUBLESHOOTING.md).
