# Nestlin — NES Emulator (Kotlin)

Personal learning project. 6502 CPU + 2C02 PPU + 2A03 APU. JavaFX 21 UI, Mesen2 as reference oracle.

## Build / Test / Run

```bash
# Build (produces runnable JAR via shadowJar at build/libs/nestlin-all.jar)
./gradlew build

# Run the unit-test suite (CPU, PPU, APU, mapper, region tests)
./gradlew test

# Run the Mesen2-comparison suite (needs Mesen2 on disk — see CLAUDE.local.md)
./gradlew testMesenComparison

# Run the emulator UI
./gradlew run --args="path/to/rom.nes"

# Optional flags on the run command
./gradlew run --args="rom.nes --debug"             # verbose CPU logging
./gradlew run --args="rom.nes --region=pal"        # override NTSC/PAL auto-detect
./gradlew run --args="rom.nes --no-audio"          # mute audio

# Headless replay-as-test (issue #62): deterministically replay an FM2 against a ROM,
# print state=/frame= SHA-256 fingerprints + write a PNG of the frame reached. No display.
# This is the "agent takes a ROM + an .fm2 bug repro and lands the exact state" entry point.
java -jar build/libs/nestlin-all.jar replay rom.nes bug.fm2            # record: print hashes + PNG
java -jar build/libs/nestlin-all.jar replay rom.nes bug.fm2 --frame N  # capture a mid-movie frame
java -jar build/libs/nestlin-all.jar replay rom.nes bug.fm2 \
    --expect-state <sha> --expect-frame <sha>                          # verify: exit 0 match / 1 mismatch
# Exit codes: 0 ok/match · 1 hash mismatch · 2 usage/checksum · 3 emulator threw mid-replay.
```

**Requirements:** JDK 21 (Gradle toolchain pinned in `build.gradle.kts`), Kotlin 1.9.22, JavaFX 21.0.1.

## File Layout

```
src/main/kotlin/com/github/alondero/nestlin/
├── Nestlin.kt              # top-level orchestrator; stepCpuCycle() is the test seam
├── EmulatorConfig.kt       # user-tweakable runtime flags (throttle, pause, region)
├── Region.kt               # NTSC vs PAL timing constants — single source of truth
├── Memory.kt               # CPU bus: RAM, PPU regs, APU regs, mapper dispatch
├── Controller.kt           # $4016/$4017 + gamepad state
├── Apu.kt                  # 5 channels + frame counter + mixer
├── SaveState.kt            # .nstl binary format
├── SaveRam.kt              # .sav battery-backed PRG-RAM persistence
├── cli/                    # headless `replay` subcommand (ReplayCli args → ReplayCommand): FM2 replay-as-test
├── cpu/                    # 6502 core + 151 opcodes + addressing modes
├── ppu/                    # 2C02: rendering, OAM, palette, vram address, regs
├── apu/                    # channels, envelope, sweep, length, frame counter, resampler
├── gamepak/                # iNES header + Mapper0..69 dispatch (see MAPPER_SUPPORT.md)
├── input/                  # keyboard + JInput gamepad (config at ~/.config/nestlin/input.json)
├── ui/                     # JavaFX Application (Canvas-based nearest-neighbour scaling), menus, scaling, fast-forward, screenshots
├── file/                   # .nes + .7z loading
└── log/                    # CPU trace logger

src/test/kotlin/.../compare/  # Mesen2 oracle tests (state diff, not pixels) — see docs/TESTING_STRATEGY.md
testroms/                    # nestest.nes is the only ROM in git
```

## Conventions

- **Kotlin idiom:** `data class` for value types, extension functions for bit ops (`isBitSet`, `clearBit`), operator overloading for memory (`memory[addr]`), `when/ranges` for memory mapping.
- **Unsigned handling:** Kotlin's lack of unsigned primitives means `and 0xFF` / `and 0xFFFF` after arithmetic; use `Byte.toUnsignedInt()` when widening.
- **Subsystem ownership:** CPU, PPU, APU, mapper each own their own `saveState`/`loadState`. `SaveState` orchestrates — never reach into a subsystem's internals.
- **Testing:** JUnit 4 + Hamkrest. **Default to state diff, not pixel diffs** (see `docs/TESTING_STRATEGY.md`). For bugs: write the failing test first, see it fail, fix, see it pass.
- **Memory mirroring** is encoded as a `when` over the address — see `Memory.kt` for the canonical example.
- **Bugs always get a regression test, even pre-existing ones** (per global CLAUDE.md).
- **Line endings:** Project policy is **CRLF** for `*.kt`, `*.kts`, `*.gradle`, `*.md`, `*.py`, `*.lua`, `*.json`, `*.yml`, `*.yaml`, `*.toml`, and `.github/**`; `*.sh` is LF. Pinned in `.gitattributes` with `text eol=crlf` (and `text eol=lf` for `*.sh`). Windows is the dominant dev platform (see `CLAUDE.local.md`); Linux/macOS contributors will see `^M` in some diff tools but most (IntelliJ, GitHub web) hide them. After updating `.gitattributes`, run `git add --renormalize` to bring the index in sync. `git diff --ignore-cr-at-eol` should be empty for any clean worktree.

## Current Status (2026-06)

**Working:**
- CPU: all 151 opcodes including unofficial; `GoldenLogTest` is the regression bar.
- PPU: full background + sprite rendering, sprite-0 hit, 8x16 sprites, A12 edge to mapper.
- APU: 5 channels (Pulse×2, Triangle, Noise, DMC), PAL/NTSC tables, mixer.
- Mappers: **0, 1, 2, 3, 4, 5 (stub), 7, 9, 10, 11, 34, 69.** Details + per-mapper game coverage in `MAPPER_SUPPORT.md`.
- Region: NTSC + PAL auto-detect (iNES header → NO-INTRO filename → user override).
- Save state (`.nstl`, F5/F8 + menu) and save RAM (`.sav`, FCEUX-compatible).
- Battery RAM persistence for mappers 1/4/5 (mappers with `$6000-$7FFF` PRG-RAM).
- JavaFX UI: Canvas-based 1x/2x/3x/4x/Fit scaling, fullscreen (F11), hold-Tab fast-forward, pause (Ctrl+P), throttle toggle (Ctrl+T), screenshot (S), gamepad via JInput.

**Known limits:**
- Mapper 5 is a stub — Castlevania III not yet playable (needs bank-select decode rewrite, fill mode, multiplier output, scanline IRQ).
- Some mappers (5) lack audio expansion.
- JInput native libs need platform-specific setup (documented in `CLAUDE.local.md`).

## Testing Strategy (one-paragraph version)

Read `docs/TESTING_STRATEGY.md` before adding tests. The pyramid, top to bottom:

1. **Structured state diff** against Mesen2 — `Mesen2StateCapturer` → JSON of CPU/PPU/RAM/OAM/palette; byte-compare with `StateComparator`. This is the workhorse. `Mapper10RegressionTest` is the worked example.
2. **Hook-based behaviour assertions** — `emu.addMemoryCallback` for cycle-sensitive bugs (MMC3 A12, NMI counts, `$2002` polling).
3. **Pixel diffs are a last resort** — `ScreenshotComparisonTest` exists but tells you *nothing about why*. Don't add new pixel-diff cases.
4. **Anti-patterns:** `assumeTrue(mesen2Available)` silent skips; dumping files to `build/` expecting eyeballs; reflection into private `Cpu`/`Ppu` fields. Each false-greens CI or breaks on rename.

## Documentation

- `MAPPER_SUPPORT.md` — per-mapper game coverage and known quirks. Actively maintained.
- `docs/TESTING_STRATEGY.md` — the test pyramid and how to add a regression test. Actively maintained.
- `README.md` — build/test/run entry points and the full tooling list.
- `tools/dump_analyzer.py` — parse 64KB CPU memory dumps (`.dmp`) from debug sessions and query them by region, register, or address. Useful for post-mortem debugging.

`docs/` also contains older design notes (`PPU_RENDERING_PLAN.md`, `DONKEY_KONG_RENDERING_PLAN.md`) that describe specific milestones. They're kept for context but not actively maintained — reference them from commit messages or PR descriptions when relevant, not from this file.

## Local-only context

- Per-machine ROM/emu paths, env vars, and worktree quirks live in `CLAUDE.local.md` (gitignored). Read it on first session.

## Common gotchas

- **Gradle daemon may not inherit shell env vars** between invocations. If a test reports `SKIPPED` unexpectedly, `./gradlew --stop` then re-run. The `testMesenComparison` task explicitly forwards `MESEN2_PATH`.
- **Save state semantics:** `performWithEmulationPaused` (in `Application.kt`) stops the emulation thread before serialising CPU/PPU/APU state. Don't bypass it from menu handlers.
- **Cycle accounting:** every mapper IRQ either counts A12 edges (MMC3) or CPU cycles (FME-7) — never both. `Mapper.tickCpuCycle()` is the per-CPU-cycle hook; default no-op.
- **PPU vblank latch:** `nmiOccurred` is cleared at the pre-render scanline AND on `$2002` read; games that poll `$2002` only at the wrong cadence (Gimmick!) used to deadlock — see `PpuAddressedMemory.clearVBlankAtPreRender` and `gimmick-nmi-prerender-latch-fix-2026-06-01`.
- **NMI dispatch latency:** `Cpu.checkAndHandleNmi` arms a pending NMI and dispatches it one instruction later (`nmiArmed`), modelling the 6502's ~1-instruction NMI latency. This lets a "poll `$2002` for vblank" loop win the race against an enabled NMI (the in-flight poll reads the flag and the read suppresses the NMI). Removing it re-hangs Camerica/Codemasters mapper-71 titles (Big Nose, Micro Machines) — see `BigNoseHangTest`.
- **Forced-blank output:** when rendering is disabled mid-frame (PPUMASK bits 3/4 clear), `Ppu.tick` still scans out the backdrop colour for visible pixels; otherwise stale pixels freeze into a "band". See `PpuForcedBlankBackdropTest`.
- **Worktree scope:** when running inside `.claude/worktrees/<name>/`, the parent repo's `testroms/` and `tools/` are accessed via absolute paths in Kotlin/Lua; `.worktreeinclude` copies `CLAUDE.local.md` only.
- **Line-ending phantom diffs (Issue #112):** if `git status` flags a `*.kt` (or other in-scope) file as modified and `git diff --ignore-cr-at-eol` is empty, the index blob is in legacy un-normalized form. Fix: `git add --renormalize <file>`. Root cause was `.gitattributes` declaring `text` without `eol=` — now pinned to `eol=crlf` (see Conventions above).
