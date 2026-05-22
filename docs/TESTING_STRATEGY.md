# Nestlin Testing Strategy

**Status:** Proposal for review (2026-05-20). Replaces the de-facto status quo where most cross-emulator verification relies on PNG screenshot comparison against Mesen2.

**Audience:** Future Adam, future Claude sessions, and anyone touching `src/test/kotlin/.../compare/`.

---

## 1. The problem in one paragraph

Today's regression net for rendering/mapper bugs is heavily weighted toward **pixel-level PNG diffing against Mesen2**. A typical run boots a full Mesen2 GUI process per test, emulates N frames cold, takes a screenshot, writes a PNG, then a Kotlin test decodes both PNGs and compares pixels. The approach has three structural problems: it is **slow** (~10–30s per Mesen2-backed test because of cold GUI boot + frame-by-frame emulation to the target), it is **brittle** (a 1-pixel scroll difference fails a test that is semantically correct), and it **silently passes** when Mesen2 isn't available because tests use `assumeTrue()` to skip rather than fail. We can do dramatically better by treating Mesen2 as a *structured-state oracle* (compare OAM, palette, VRAM, CPU regs as bytes — not pixels), using Mesen2's headless `--testRunner` flag instead of GUI boot, and using savestates as fixtures to skip the cold-boot phase.

---

## 2. Inventory of what exists today

### 2.1 Screenshot/pixel tests (the brittle layer)

| Test | File | What it checks | Cost per run | Brittleness |
|---|---|---|---|---|
| `ScreenshotComparisonTest.compareFrames` (×3) | `src/test/kotlin/.../compare/ScreenshotComparisonTest.kt:1-70` | Tetris@60, Lolo1@60, Kirby@150 — pixel diff | ~10–30s per case (Mesen2 GUI boot + emulation) | Tetris/Lolo1 at **0.0% threshold** (sub-pixel differences fail); Kirby at 20% (loose, low value). Silently skips on missing Mesen2. |
| `KirbyScreenshotTest` (×4) | `src/test/kotlin/.../compare/KirbyScreenshotTest.kt:17-211` | Captures Kirby frames 1–20 as PNGs, prints non-black pixel counts | Multiple emulation passes | **No assertions** — outputs files for human inspection. Test passes regardless of correctness. |
| `ScreenshotCapture.captureScreenshots` | `.../compare/ScreenshotCapture.kt:20-79` | Ad-hoc capture from env-var ROM list | Variable | Dev helper, not a regression test. Silently no-op without env vars. |

### 2.2 State/OAM tests (the model to expand)

| Test | File | What it checks | Cost | Notes |
|---|---|---|---|---|
| `GoldenLogTest` | `src/test/kotlin/.../GoldenLogTest.kt:10-91` | CPU instruction-by-instruction trace vs `src/test/resources/nestest.log` | ~10–30s | The gold standard for CPU. Byte-precise, no display, no Mesen2 process. **This is the shape every test should aspire to.** |
| `StateComparisonTest.compareStates` (×3) | `.../compare/StateComparisonTest.kt:1-68` | CPU + PPU regs + RAM + OAM + palette at target frame | ~30s for 3 cases (Mesen2 boots once per case) | Already non-pixel. Just needs the runner to stop booting cold per test. |
| `StateCaptureIntegrationTest` (×3) | `.../compare/StateCaptureIntegrationTest.kt:1-57` | Same shape as above, Tetris @ frame 60 | ~10s per case | Diagnostic variant. |
| `KirbyOamSnapshotTest` | `.../compare/KirbyOamSnapshotTest.kt:20-106` | Writes Nestlin OAM dump to `build/kirby-oam-snapshots.txt` at many frames | ~5–10s (1500 emulated frames) | **No comparison — human reads the file.** |
| `KirbyMesenVsNestlinOamTest` | `.../compare/KirbyMesenVsNestlinOamTest.kt:14-42` | Same as above, but Mesen2 side. Outputs to `build/kirby-oam-mesen2/frameNNNN.txt` | ~10–30s | **No comparison — human diffs the two directories by eye.** |

### 2.3 Helper infrastructure

- `NestlinHeadlessRunner` (`.../compare/NestlinHeadlessRunner.kt:1-82`) — in-process emulator with `captureFrame()` / `captureFrames(List<Int>)`. Fast, no subprocess. Already supports batch capture.
- `NestlinStateCapturer` (`.../compare/NestlinStateCapturer.kt:1-149`) — reflection-based snapshot of CPU, PPU, RAM, OAM, palette. Fast. Reflection is fragile to internal renames.
- `Mesen2ReferenceRunner` (`.../compare/Mesen2ReferenceRunner.kt:1-121`) — boots Mesen2 GUI with a generated Lua script for a single screenshot. **One subprocess per call.** Uses `--doNotSaveSettings` but NOT `--testRunner` — requires a display.
- `Mesen2StateCapturer` (`.../compare/Mesen2StateCapturer.kt:1-150+`) — same shape as the screenshot runner but extracts `emu.getState()` via Lua → JSON. Manual regex JSON parse.
- `Mesen2OamDumpRunner` (`.../compare/Mesen2OamDumpRunner.kt:14-141`) — multi-frame OAM + pattern-table + screenshot dump. Encodes pattern data as hex text, dumps a PNG **even when the caller only wants OAM**.
- `EmulatorStateSnapshot` + `StateComparator` — payload-agnostic comparison layer. Already supports adding new memory regions without rewriting the runner.

### 2.4 Concrete pain-point findings

1. **Mesen2 GUI boot per test.** Every Mesen2-backed test starts a fresh process, loads the ROM cold, emulates to the target frame, exits. Six Mesen2-backed tests = six full boots = several minutes wall time.
2. **`assumeTrue()` silent skips.** `ScreenshotComparisonTest:33-34`, `StateComparisonTest:35`, `StateCaptureIntegrationTest:25-35`, `KirbyMesenVsNestlinOamTest:19-26`. When Mesen2 is missing, when a display is detached, when I/O permissions are off, when a ROM isn't on disk — the test passes. CI on a host without Mesen2 gives a false green.
3. **0.0% pixel threshold** on Tetris/Lolo1 means any sub-pixel rounding, palette LUT change, or 1-cycle scroll skew fails. The PPU pixel pipeline has a known latent off-by-one between background `x = cycle - 2` and sprite `pixelX = cycle - 1` (see `mmc3-sprite-pipeline-fix-2026-05-18` memory) — fixing that *correctly* could currently fail these tests.
4. **Manual eye-diffing.** `KirbyOamSnapshotTest` and `KirbyMesenVsNestlinOamTest` produce file trees in `build/` and expect a human to compare them. This caught Kirby bugs once; it does not protect against regressions.
5. **Reflection-based PPU state extraction.** `NestlinStateCapturer:112-128` reaches into private `Ppu` fields by name. Rename `cycle` → `dot` and the test silently captures zeros.
6. **Lua → JSON → regex pipeline** in `Mesen2StateCapturer:246-280` has no validation. Missing fields parse as `0`, silently producing false matches.
7. **PNG round-trip when only bytes are needed.** `Mesen2OamDumpRunner` writes a 60KB screenshot per target frame even though the caller filters for `.txt` outputs.

---

## 3. What Mesen2 actually gives us

Sources: [Mesen API reference](https://www.mesen.ca/docs/apireference.html), [LuaDocumentation.json](https://github.com/SourMesen/Mesen2/blob/master/UI/Debugger/Documentation/LuaDocumentation.json) (canonical), local install at `X:\src\nestlin\tools\Mesen2\`.

### 3.1 Memory peek (the byte-level oracle we want)

`emu.read(addr, memType, signed?)` and `emu.read16` / `emu.read32` — with NES memory types including:

- `nesMemory` / `nesDebug` — CPU bus. **Use `nesDebug`** in test scripts: it has no side effects (no vblank clear on $2002 read, no vram-addr increment on $2007 read).
- `nesPpuMemory` / `nesPpuDebug` — PPU bus, same pattern.
- `nesSpriteRam` — 256-byte OAM.
- `nesSecondarySpriteRam` — 32-byte secondary OAM (the 8-sprite-per-scanline cache — *this is gold for sprite-evaluation tests*).
- `nesPaletteRam` — 32 bytes.
- `nesNametableRam` — raw 2KB (or 4KB with 4-screen mirroring), unmirrored.
- `nesChrRom` / `nesChrRam` — pattern tables, **post-mapper offset** — meaning we can directly read what Mesen2 thinks the current CHR window is, perfect for mapper banking tests.
- `nesPrgRom`, `nesWorkRam`, `nesSaveRam`, `nesInternalRam`, `nesMapperRam`.

There is **no bulk-read API**, but a Lua `for i=0,255 do oam[i] = emu.read(i, ...) end` is sub-millisecond.

### 3.2 Structured state

`emu.getState()` returns a **flat** table with **dotted string keys** (confirmed by Phase 0 spike). Access via `state["cpu.pc"]`, NOT `state.cpu.pc` — the latter throws nil-index because `state.cpu` is not a sub-table. Confirmed keys (non-exhaustive — there are ~280):

- `cpu.pc`, `cpu.a`, `cpu.x`, `cpu.y`, `cpu.sp`, `cpu.ps`, `cpu.cycleCount` (note: it's `ps` for processor status, not `status`)
- `ppu.scanline`, `ppu.cycle`, `ppu.frameCount`, `ppu.videoRamAddr`, `ppu.tmpVideoRamAddr`, `ppu.xScroll`, `ppu.writeToggle`, `ppu.spriteRamAddr`, `ppu.ppuBusAddress`, `ppu.memoryReadBuffer`, `ppu.masterClock`, `ppu.lowBitShift`, `ppu.highBitShift`, `ppu.intensifyColorBits`
- `ppu.control.{nmiOnVerticalBlank, largeSprites, backgroundPatternAddr, spritePatternAddr, verticalWrite}` (these are booleans/ints — reconstruct PPUCTRL byte if needed)
- `ppu.mask.{spritesEnabled, backgroundEnabled, spriteMask, backgroundMask, grayscale, intensifyRed, intensifyGreen, intensifyBlue}` (booleans)
- `ppu.statusFlags.{verticalBlank, sprite0Hit, spriteOverflow}` (booleans)
- `ppu.paletteRam0..31` — per-byte palette RAM as separate keys (32 bytes total)
- `ppu.secondarySpriteRam0..31` — per-byte secondary OAM (the 8-sprite scanline cache, 32 bytes)
- `mapper.chrMemoryOffset0..63` — current CHR bank offsets (huge for mapper banking tests!)
- `apu.{square1,square2,triangle,noise,dmc}.…` — full APU envelope/sweep/timer state
- `frameCount`, `masterClock`, `region`, `clockRate`, `consoleType`

The complete enumeration is a one-shot Lua recon: `for k,v in pairs(emu.getState()) do print(k) end` — already captured in this session's `spike_getstate.lua` output (see PR #40 description for the full key list).

### 3.3 Savestates as fixtures — **the biggest single speedup**

- `emu.saveSavestate()` returns a binary blob.
- `emu.loadSavestate(blob)` restores it.
- Slot-based variants exist (`saveSavestateAsync(slot)` / `getSavestateData(slot)`).

We can cold-boot to "Kirby title-screen frame 300" **once**, persist the savestate to `src/test/resources/savestates/`, and every subsequent test that wants to assert behaviour from that point loads the state in milliseconds. This converts every test from O(target_frame) to O(frames_under_test).

⚠ Caveat: a Mesen2 savestate is **not** a Nestlin savestate. Using Mesen2 savestates as fixtures requires either (a) loading them on both sides — which means Nestlin must learn to import Mesen2's savestate format (non-trivial, possibly not worth it), or (b) a synthesised JSON state snapshot we use to seed *both* emulators. Path (b) is the realistic one and bounds what we can fixture: CPU+RAM+VRAM+OAM+palette+mapper-regs, not the full machine including APU phase.

### 3.4 Hooks (assert behaviour *during* execution, not at frame boundaries)

`emu.addMemoryCallback(cb, type, start, end, cpuType, memoryType)`:
- `type` ∈ `read | write | exec`.
- Crucially, `memoryType` accepts `nesPpuMemory` — so **PPU-bus reads and writes can be trapped**. This is exactly what we need for MMC3 A12-edge tests: register a `read` callback on `nesPpuMemory` range `0x1000-0x1FFF`, count fires, compare counts against Nestlin. Today the same check is done by stepping the emulator and reading a counter — Mesen2 gives us the ground truth in one process.
- `exec` callbacks let us assert "CPU reached PC=X with A=Y" without single-stepping.

`emu.addEventCallback(cb, eventType)` — confirmed enum (NES-relevant): `nmi`, `irq`, `startFrame`, `endFrame`, `reset`, `scriptEnded`, `inputPolled`, `stateLoaded`, `stateSaved`, `codeBreak`.

**Confirmed absent**: no `scanline` event, no A12-edge event, no `ppuRegisterWrite` event. Work around by using memory callbacks on the relevant address ranges.

### 3.5 Headless mode: `--testRunner` ✅ VERIFIED 2026-05-20

```
Mesen.exe --testRunner --doNotSaveSettings script.lua rom.nes
```

(Note: arg order matters in Mesen2 — script can come before or after ROM; both work in this build.) Runs at max speed, no GUI, exits with the code passed to `emu.stop(code)`. **Confirmed locally**: a 60-frame state capture completes in 0.5–0.6s. The v1 `emu.read` deadlock does NOT reproduce in Mesen2 v2.

**Use `emu.stop(code)` not `os.exit()`.** The old runners used `os.exit()` because of an inherited assumption from v1; in v2 `emu.stop` is cleaner and gives a meaningful exit code we can check in Kotlin.

**Always wrap callbacks in `pcall`.** A Lua error inside `onEndFrame` silently aborts the callback — the script never calls `emu.stop()` — the process keeps emulating until something else kills it. Pattern:

```lua
function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        local ok, err = pcall(captureWork)
        if not ok then print("error: " .. tostring(err)); emu.stop(2)
        else emu.stop(0) end
    end
end
```

This is the production pattern, not optional defensive programming. Lua silently swallowing errors in callbacks was the actual cause of the 101s zombie run in the first spike attempt.

### 3.6 Other useful outputs

- `emu.getAccessCounters(counterType, memoryType)` — per-address read/write/exec counts. Great for "PRG bank N executed K times" or "OAM byte 0 written exactly 64× this frame".
- `emu.getCdlData(memoryType)` — Code/Data Log: per-byte classification of PRG as code vs data. Cross-emulator CDL diff = strong regression net for CPU coverage.
- `emu.getScreenBuffer()` — raw ARGB array. **If** we ever need pixels, this skips PNG encode/decode entirely.

---

## 4. Proposed test pyramid

```
              ┌─────────────────────┐
              │  Pixel diffs (few)  │   ← last resort, golden ROMs only
              └─────────────────────┘
            ┌─────────────────────────┐
            │  Savestate-resume tests │   ← short, focused, fast
            └─────────────────────────┘
          ┌─────────────────────────────┐
          │ Structured state diffs vs    │   ← workhorse layer
          │ Mesen2 (OAM, VRAM, regs,    │
          │ palette, CHR window)         │
          └─────────────────────────────┘
        ┌──────────────────────────────────┐
        │  Hook-based behaviour assertions │   ← cycle/event-count
        │  (A12 edges, NMI count, $2002    │      assertions
        │  reads, PRG bank exec counts)    │
        └──────────────────────────────────┘
      ┌──────────────────────────────────────┐
      │  CPU golden log (nestest, expand)    │   ← already exists
      └──────────────────────────────────────┘
    ┌──────────────────────────────────────────┐
    │   Pure Kotlin unit tests (mappers,        │   ← cheapest, fastest
    │   addressing modes, register decode)     │
    └──────────────────────────────────────────┘
```

**The base of the pyramid is wide, the top is narrow.** Today the project has it inverted — pixel diffs at the front line.

---

## 5. Tactical migration plan

### Phase 0 — Settle one unknown ✅ DONE 2026-05-20
- [x] Spike confirmed: `Mesen.exe --testRunner --doNotSaveSettings rom.nes script.lua` runs headlessly, `emu.read` works for all NES memory types, `emu.getState()` works, `emu.takeScreenshot()` works (reads PPU framebuffer), `emu.getScriptDataFolder()` works, `emu.stop(code)` exits with that code. Total wall time for a 60-frame state capture: **~0.5s** (vs ~10–30s for the prior GUI invocation). All three Mesen2 runners now use `--testRunner`.

**Three correctness fixes uncovered by the spike (each was a latent bug masked by silent skips):**

1. `emu.getState()` returns a FLAT table with dotted string keys (`state["cpu.pc"]`, `state["ppu.frameCount"]`, …), not a nested table. The old `Mesen2StateCapturer` Lua used `state.cpu.pc` which throws nil-index — silently aborts the callback so `emu.stop()` never fires and the process zombies. Strategy doc section 3.2 had this wrong; corrected now.
2. `emu.getScriptDataFolder()` returns NO trailing separator. The old code concatenated `basePath .. "screenshot.png"` and wrote to `LuaScriptData/capturescreenshot.png` — Kotlin then looked at `LuaScriptData/capture/screenshot.png`, found nothing, threw. All three runners now insert `"/"` explicitly.
3. ROM paths passed to Mesen2 must be **absolute**. The Kotlin runners set the Mesen2 process working dir to the Mesen install dir, so a relative `testroms/nestest.nes` resolves to `<mesen>/testroms/nestest.nes` and Mesen exits non-zero. All three runners now call `romPath.toAbsolutePath()` before passing it through.

**New regression test added:** `Mesen2StateCapturerSmokeTest` — uses the in-repo `nestest.nes` so it's the first Mesen2-backed test that can actually run in CI when Mesen2 is available. Lives in the `testMesenComparison` task (not the default `test`).

### Phase 1 — Stop the silent skips (≤1 day)
- [ ] Replace every `assumeTrue(mesen2Available)` with a strict mode controlled by a single env var (`NESTLIN_REQUIRE_MESEN2`). CI sets it to `true` and fails loudly when Mesen2 is missing. Local devs can opt in. Today's pattern of "silently green if oracle absent" is the worst of both worlds.
- [ ] Same for missing ROMs: `assumeTrue(Files.exists(kirbyRom))` becomes a parameterized exclusion driven by a single config, not scattered silent returns.
- [ ] `KirbyScreenshotTest`, `KirbyOamSnapshotTest`, `KirbyMesenVsNestlinOamTest` — either gain assertions or move out of `test/` into a separate `debug/` source set so they don't masquerade as regression tests.

### Phase 2 — Reuse one Mesen2 process per suite (≤2 days)
- [ ] Build a `Mesen2Session` that launches Mesen2 once per JUnit suite (via `@BeforeAll`/`@AfterAll`), loads a Lua "test server" script that accepts commands over a file/stdin/socket: `load <rom>`, `runTo <frame>`, `snapshotState`, `snapshotOam`, `snapshotPalette`, `quit`. Each individual test issues commands and parses replies.
- [ ] This single change collapses the 6× cold-boot cost into 1×. Even if `--testRunner` doesn't pan out, the win is large.

### Phase 3 — Promote state diffs to the workhorse layer (≤3 days)
- [ ] Extend `EmulatorStateSnapshot` to include:
  - Nametable RAM (2KB or 4KB).
  - Current CHR bank windows (8× 1KB on MMC3, 2× 4KB on MMC1, etc.) — read via `nesChrRom`/`nesChrRam` at the *current* bank addresses.
  - Mapper state where the mapper exposes it (IRQ counter, reload, latch, mirroring).
  - APU channel enable bits + length counters (`$4015` shape).
- [ ] Rewrite `Mesen2StateCapturer` to use Lua → length-prefixed binary blob (or a real JSON library on the Lua side) instead of regex-parsed flat JSON. Validate every field; raise on missing keys.
- [ ] Delete `NestlinStateCapturer`'s reflection. Expose the same fields through a stable testing-only API on `Cpu` and `Ppu` (or `internal` accessors).
- [ ] Replace `ScreenshotComparisonTest`'s pixel diffs with state diffs at the same frames. Keep one pixel-diff test for a known-good ROM (Tetris) as a smoke regression net — but with a sensible threshold (~0.1%) so it catches real breakage, not 1-pixel scroll skew.

### Phase 4 — Add hook-based behaviour tests (≤3 days)
- [ ] `MMC3 A12-edge count test`: for Kirby/SMB3, count `nesPpuMemory` read callbacks in range `0x1000-0x1FFF` over N frames in Mesen2; assert Nestlin's internal A12-edge counter matches within tolerance. This is the structured replacement for the current "compare screenshots and hope" loop on MMC3 IRQ behaviour.
- [ ] `NMI count test`: count `nmi` event-callback fires per frame in Mesen2; assert Nestlin's NMI count matches. Catches missed-NMI and double-NMI bugs immediately.
- [ ] `PPU register write trace test`: log every `nesMemory` write in `0x2000-0x2007` from both emulators; diff the sequences. Catches PPU timing regressions that pixel diffs catch only by accident.

### Phase 5 — Savestate fixtures (≤2 days, depends on Phase 3)
- [ ] Build a *synthesised* state-fixture format (JSON or binary) that both emulators can ingest. Contents: CPU regs+RAM, full VRAM/nametable, OAM, palette, mapper register state.
- [ ] One-time generation: cold-boot Kirby to frame 300, write the fixture, check it into `src/test/resources/savestates/`.
- [ ] New tests load the fixture and assert behaviour from N frames after the savepoint, not from cold boot. Per-test wall time drops from 10s to 0.5s.

### Phase 6 — Retire the dead weight
- [ ] Delete `KirbyScreenshotTest`, `KirbyOamSnapshotTest`, `KirbyMesenVsNestlinOamTest` once Phase 3 + Phase 4 cover the same behaviour with assertions.
- [ ] Consolidate `Mesen2OamDumpRunner` into the `Mesen2Session` API from Phase 2 — the standalone runner stops writing PNGs callers don't want.

---

## 6. New-test recipes (future reference)

When adding a regression test for a new bug, pick the cheapest level that catches it:

| Symptom | Test recipe |
|---|---|
| CPU opcode bug | Extend `GoldenLogTest` golden file, or add a focused unit test with a tiny PRG fixture. |
| Mapper bank switching wrong | State diff: read `nesChrRom` (Mesen2) and Nestlin's current bank window at the same frame, byte-compare. |
| MMC3 IRQ count wrong | Hook test: count A12 edges via `nesPpuMemory` read callback over N frames. Compare. |
| Sprite-0 hit timing off | Hook test: trap CPU read of `$2002` and record cycle counts. Diff sequences. |
| Sprite evaluation wrong | State diff: snapshot `nesSecondarySpriteRam` (Mesen2) at a chosen scanline (use a memory callback on a known mid-scanline address as a trigger). |
| NMI missed/doubled | Hook test: count `nmi` events per frame. |
| Palette glitch | State diff: read `nesPaletteRam` (32 bytes), byte-compare. |
| Background scroll wrong | State diff: PPU `cycle/scanline/v/t/x/w` regs at frame boundary. |
| **Pixel-level visual regression** | Last resort: pixel diff with ≥0.1% threshold, on one canonical ROM (Tetris). Failure should prompt the dev to add a *structured* test that catches the root cause. |

---

## 7. Things this strategy deliberately does *not* attempt

- **Cycle-perfect CPU/PPU lockstep across emulators.** Mesen2 and Nestlin will diverge mid-frame on edge cases that don't matter for game correctness. We compare at *defined synchronisation points* (frame boundary, event-callback fires, specific PCs), not continuously.
- **Mesen2 savestate import into Nestlin.** Mesen2's binary savestate format is internal. We use *synthesised* state fixtures instead — a documented JSON/binary format both emulators understand.
- **APU audio comparison.** Out of scope here. The producer-starvation work in `memory/nestlin-audio-producer-starvation-2026-05-19.md` covers audio correctness from a different angle.
- **Replacing `GoldenLogTest`.** It's already the right shape. Expand it to cover unofficial opcodes more thoroughly; don't rewrite it.

---

## 8. Open questions

1. Does Mesen2 `--testRunner` actually work for `emu.read` in v2? (Phase 0 spike resolves this.)
2. Are `cpu` and `ppu` sub-fields of `emu.getState()` stable across Mesen2 versions? Pin a Mesen2 commit in `CLAUDE.local.md` or `tools/`?
3. Is `nesSecondarySpriteRam` updated continuously during sprite eval, or only at scanline boundary? Affects whether mid-scanline assertions are meaningful.
4. Does `addMemoryCallback` on `nesPpuMemory` with `read` callback fire on background tile fetches every scanline cycle 1–256? If yes, A12 trapping is exact. If filtered to CPU-initiated reads only, the MMC3 hook test needs an `exec`-callback workaround.

(2) and (3) and (4) are best answered with one Lua recon script — write once, paste results into this doc.

---

## 9. Definition of done for this strategy

- [ ] Phase 0 spike result recorded in this doc.
- [ ] No `assumeTrue()`-style silent skip in any `compare/` test (Phase 1).
- [ ] Mesen2 process starts ≤1× per JUnit suite (Phase 2).
- [ ] `ScreenshotComparisonTest` retains at most one pixel-diff case; the rest are state diffs (Phase 3).
- [ ] At least one hook-based test exists for MMC3 IRQ correctness (Phase 4).
- [ ] At least one savestate-fixture test exists (Phase 5).
- [ ] Total cross-emulator test wall time for the default `./gradlew test` run is below 60 seconds on Adam's machine.
