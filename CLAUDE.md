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

# Diagnose "game renders wrong / doesn't boot": side-by-side Nestlin-vs-Mesen2 state
# at frame N (PPUCTRL/MASK, NMI+IRQ per frame, banks, OAM/palette/CHR/RAM diffs)
# plus automated LIKELY-CAUSE classification. Run this BEFORE forming a hypothesis.
./gradlew diverge -Prom=X:/src/nestlin/testroms/kirby.nes -Pframe=120

# Oracle-free boot smoke (no Mesen2 / no ROM library needed): boot a ROM headless N frames and
# print BOOTCHECK VERDICT: PASS|WARN|FAIL from loaded/rendered/non-blank/banks-moved/NMI+IRQ.
# The mandatory self-check for any new/changed mapper — the Mesen2 gates skip when the oracle is
# absent, so a green `test` proves nothing about a real game. (A Stop hook blocks ending a session
# that edited gamepak/Mapper*.kt without a PASS/WARN here. MapperCoverageLintTest fails the build
# if a mapper lacks a GamePak arm / MAPPER_SUPPORT section, or if a Mapper*RegressionTest isn't in
# the tag-driven 'mesen' lane. Test lanes are @Tag-based now, not hand-listed: @Tag("mesen") +
# @Tag("externalRom") are excluded from `test`; testMesenComparison runs @Tag("mesen").)
./gradlew bootcheck -Prom=X:/src/nestlin/testroms/kirby.nes -Pframes=120

# Print test-environment diagnostics (MESEN2_PATH resolution, ROM availability,
# strict-mode flag) — run when a comparison test reports SKIPPED unexpectedly
./gradlew verifyTestEnv

# Run ONE test class/method and print only its output (no Gradle noise, cache defeated,
# failure messages + system-out extracted from the result XML). Use for diagnostic loops.
tools/run-diag.ps1 -TestClass GoldenLogTest            # or tools/run-diag.sh from bash
tools/run-diag.ps1 -TestClass Mapper10RegressionTest -Mesen

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

## Step 0 of any mapper task — `tools/rom_info.py`

Before writing a single line of mapper code, run the header decoder against your target ROM. Five known-broken conventions are encoded as warnings:

```bash
# What mapper is this, really?
python tools/rom_info.py info path/to/rom.nes

# Walk the dev library, filtered to a single mapper number
python tools/rom_info.py scan S:\\Media\\Nintendo\\NES\\Games --mapper 33

# NO-INTRO Namco 108 dumps are mislabelled as mapper 4; this emits a sibling COPY
# with the byte6/byte7 patch applied (never touches the source).
python tools/rom_info.py patch-namco108 path/to/gauntlet.nes

# Read NMI/RESET/IRQ from the fixed last bank
python tools/rom_info.py vectors path/to/rom.nes

# CPU addr <-> file offset math + hexdump (for disassembly / reading NMI handlers)
python tools/rom_info.py addr path/to/rom.nes --cpu-addr 0xEA40 --bank last
```

`info` warns about NO-INTRO header mislabels (Namco 108 / DxROM is the canonical one; the patch recipe lives in the tool's `--help` and the test suite). Library path defaults to `S:\Media\Nintendo NES\Games` (override with `ROMS_PATH` or `--library`).

Run `python tools/test_rom_info.py` to exercise the decoder; the test suite cross-checks the byte equations against `Header.kt` and the in-repo `nestest.nes`.

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
├── rewind/                 # RewindBuffer ring of per-frame savestates (hold-Backspace scrub, issue #52)
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
- **Testing:** JUnit 5 + Hamkrest (no `kotlin-test` on classpath). **Default to state diff, not pixel diffs** (see `docs/TESTING_STRATEGY.md`). For bugs: write the failing test first, see it fail, fix, see it pass.
- **Exception assertions:** use `testutil.assertThrowsWithMessage<T>("substr") { ... }` (returns the throwable) and `testutil.failTest("message")` (the phantom-`<V>`-safe replacement for `Assertions.fail(String)`). `TestAssertsLintTest` fails the build for new `import kotlin.test` or raw `Assertions.fail(<string>)` — the lambda overload `Assertions.fail { ... }` is allowed.
- **Memory mirroring** is encoded as a `when` over the address — see `Memory.kt` for the canonical example.
- **Bugs always get a regression test, even pre-existing ones** (per global CLAUDE.md).
- **Line endings:** Project policy is **CRLF** for `*.kt`, `*.kts`, `*.gradle`, `*.md`, `*.py`, `*.lua`, `*.json`, `*.yml`, `*.yaml`, `*.toml`, and `.github/**`; `*.sh` is LF. Pinned in `.gitattributes` with `text eol=crlf` (and `text eol=lf` for `*.sh`). Windows is the dominant dev platform (see `CLAUDE.local.md`); Linux/macOS contributors will see `^M` in some diff tools but most (IntelliJ, GitHub web) hide them. After updating `.gitattributes`, run `git add --renormalize` to bring the index in sync. `git diff --ignore-cr-at-eol` should be empty for any clean worktree.

## Current Status (2026-06)

**Working:**
- CPU: all 151 opcodes including unofficial; `GoldenLogTest` is the regression bar.
- PPU: full background + sprite rendering, sprite-0 hit, 8x16 sprites, A12 edge to mapper.
- APU: 5 channels (Pulse×2, Triangle, Noise, DMC), PAL/NTSC tables, mixer.
- Mappers: **0, 1, 2, 3, 4, 5 (stub), 7, 9, 10, 11, 16, 19, 24, 26, 30, 33, 34, 64, 65, 66, 68, 69, 113, 119, 153, 206, 228.** Details + per-mapper game coverage in `MAPPER_SUPPORT.md`.
- Region: NTSC + PAL auto-detect (iNES header → NO-INTRO filename → user override).
- Save state (`.nstl`, F5/F8 + menu) and save RAM (`.sav`, FCEUX-compatible).
- Battery RAM persistence for mappers 1/4/5 (mappers with `$6000-$7FFF` PRG-RAM).
- JavaFX UI: Canvas-based 1x/2x/3x/4x/Fit scaling, fullscreen (F11), hold-Tab fast-forward, hold-Backspace rewind (issue #52), pause (Ctrl+P), throttle toggle (Ctrl+T), screenshot (S), gamepad via JInput.

**Known limits:**
- Mapper 5 is a stub — Castlevania III not yet playable (needs bank-select decode rewrite, fill mode, multiplier output, scanline IRQ).
- Some mappers (5) lack audio expansion.
- JInput native libs need platform-specific setup (documented in `CLAUDE.local.md`).

## Testing Strategy (one-paragraph version)

Read `docs/TESTING_STRATEGY.md` before adding tests. Test-fixture rules:

- **Never hand-roll a 16-byte iNES header** — use `testutil.TestRomBuilder` (`testRom { mapper = 33; prgKb = 128; ... }`). `HeaderConstructionLintTest` fails the build for new raw `ByteArray(16)` headers (grandfathered files are baselined; the list only shrinks).
- **New mapper regression tests subclass `compare.MapperRegressionTestBase`** (~25 lines: bank-moves-during-boot guard + render-output compare vs Mesen2) and annotate `@RequiresMesen2` (loud skip naming the resolved path; set `NESTLIN_REQUIRE_MESEN2` to turn skips into failures, e.g. on a runner that must have Mesen2).
- **New mapper work follows the `new-mapper` skill checklist** (oracle ordering: nesdev wiki → Mesen2 source → issue spec is intent only; game-usage trace test; IRQ fire-count assertions for scanline mappers).

The pyramid, top to bottom:

1. **Structured state diff** against Mesen2 — `Mesen2StateCapturer` → JSON of CPU/PPU/RAM/OAM/palette; byte-compare with `StateComparator`. This is the workhorse. `Mapper10RegressionTest` is the worked example.
2. **Hook-based behaviour assertions** — `emu.addMemoryCallback` for cycle-sensitive bugs (MMC3 A12, NMI counts, `$2002` polling).
3. **Pixel diffs are a last resort** — `ScreenshotComparisonTest` exists but tells you *nothing about why*. Don't add new pixel-diff cases.
4. **Anti-patterns:** `assumeTrue(mesen2Available)` silent skips; dumping files to `build/` expecting eyeballs; reflection into private `Cpu`/`Ppu` fields. Each false-greens CI or breaks on rename.

## Documentation

- `MAPPER_SUPPORT.md` — per-mapper game coverage and known quirks. Actively maintained.
- `docs/TESTING_STRATEGY.md` — the test pyramid and how to add a regression test. Actively maintained.
- `README.md` — build/test/run entry points and the full tooling list.
- `tools/dump_analyzer.py` — parse 64KB CPU memory dumps (`.dmp`) from debug sessions and query them by region, register, or address. Useful for post-mortem debugging.
- `tools/mesen-trace/` — checked-in, v2.1.1-verified Mesen2 Lua instruments (mapper write-watch, NMI/IRQ-per-frame counter, PPUCTRL transition log, CHR dump). Use these instead of writing fresh Lua; see its README for the invocation and the installed binary's API quirks.
- `tools/run-diag.ps1` — single-test diagnostic runner (see Build/Test/Run above).

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
- **PPU `preloadFirstTwoTiles` substitutes for hardware 321–336 fetches:** real NES advances `v.coarseX` twice during the end-of-line 320–335 BG fetches, then feeds those 2 tiles into the shift registers as the first 16 pixels of the next scanline. Nestlin's `preloadFirstTwoTiles()` at cycle 0 of each rendering line reads from `v` instead — which means `v` must still equal `t` at that point, i.e. `incrementHorizontalPosition` must NOT fire at 320–335. Adding it back (for NES-accuracy) re-introduces the 16-px left shift seen in SMB3 ("ORLD 1" instead of "WORLD 1") and was the regression that followed the issue-#227 v←t timing fix. See `PpuBackgroundScrollAlignmentTest` and the comment above the gated `incrementHorizontalPosition` call in `Ppu.kt`.
- **Worktree scope:** when running inside `.claude/worktrees/<name>/`, the parent repo's `testroms/` and `tools/` are accessed via absolute paths in Kotlin/Lua; `.worktreeinclude` copies `CLAUDE.local.md` only.
- **Line-ending phantom diffs (Issue #112):** if `git status` flags a `*.kt` (or other in-scope) file as modified and `git diff --ignore-cr-at-eol` is empty, the index blob is in legacy un-normalized form. Fix: `git add --renormalize <file>`. Root cause was `.gitattributes` declaring `text` without `eol=` — now pinned to `eol=crlf` (see Conventions above). The one-command cure is `tools/sync-master.ps1` (or `tools/sync-master.sh` from Git Bash) — it detects phantom-CRLF, renormalizes, and ff-merges master, and refuses to do anything destructive.
- **Sync master + report stale worktrees (Issue #153):** `tools/sync-master.ps1` is the one-command `fetch → renormalize → ff-merge → stale-worktree-report` for the parent repo. Detects phantom-CRLF (issue #112), refuses to run on a real-dirty tree, never `reset --hard`s, never `stash`es, never removes worktrees without `-PruneWorktrees`. **Always** emits the stale-worktree report (path, branch, age, dirty, unpushed) so the recurring "what's in `.claude/worktrees/` again?" archaeology doesn't happen. Idempotent (running twice is a no-op the second time). Tests: `tools/tests/run-tests.sh` (Pester 3.4, 18 cases). Use from PowerShell directly or via the bash wrapper; run with `-DryRun` first if you're not sure.
