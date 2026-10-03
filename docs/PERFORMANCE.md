# Accuracy-preserving performance audit

For contributors measuring emulator performance: run `./gradlew coreBench` for
the repeatable rendering workload, then use the evidence and follow-ups below.

Audit date: 2026-10-02. Performance measurements compare the PR code tree at
`6254aa70e6a0c382024a2a19f5c7643a0892c468` with its exact master base,
`6b3fd58e035a77602042820e146e3fcd3da0c1c7`. Later review commits update
documentation/build guards and add nametable mirror coverage; they do not change
the measured emulator hot path.

The first three opportunities below were implemented in this session. They remove
temporary allocations while retaining the same emulated cycles, bus accesses,
mapper callbacks, sprite selection and save-state format. The remaining entries
are candidates requiring measurement in their own production paths.

| Rank | Opportunity and evidence | Accuracy constraints | Status |
| --- | --- | --- | --- |
| 1 | Give PPU CHR/nametable callbacks primitive method signatures. Generic Kotlin function callbacks boxed addresses on each read; allocation stacks point to `PpuInternalMemory.get` during background and sprite fetches. | Preserve callback ordering, CHR read updates to the CPU data bus, nullable nametable fall-through, and A12 detection. | Implemented: primitive functional interfaces in `PpuInternalMemory`, wired by `Memory`. |
| 2 | Remove temporary nametable address pairs and boxed offsets. The previous mapping helper returned a `Pair<ByteArray, Int>`; allocation samples showed boxed offsets even where the JVM eliminated the pair itself. | Resolve current mirroring on every access, retain all five modes, the full `$3000-$3EFF` mirror range, and mapper overrides. | Implemented: select the backing array directly and use the low ten address bits as its offset. |
| 3 | Check sprite Y before constructing a complete sprite record. `getSprite` ran for up to 64 candidates on every rendering scanline. | Retain rotated OAMADDR order, the eight-sprite limit, ninth-sprite overflow, height/flips, and immutable evaluation-time snapshots. | Implemented: construct `SpriteData` only after selecting a sprite. |
| 4 | Reuse selected-sprite scratch slots and remove per-pixel list iterators and scanline `addAll` copies. Allocation samples still include `ActiveSprite`, `SecondaryOamEntry`, list iterators and backing arrays. | Preserve shift-register updates, fetch order/dummy reads, A12 edges, sprite-zero hits, and mid-scanline saves. | [Issue #323](https://github.com/alondero/nestlin/issues/323). |
| 5 | Use primitive loops or a measured packed-pixel path for UI RGB conversion. `NestlinApplication.frameUpdated` uses nested `withIndex().forEach` on primitive pixel rows. | Preserve exact RGB output, screenshots and buffer ownership. Measure JavaFX allocations first: the headless benchmark excludes this path. | [Issue #321](https://github.com/alondero/nestlin/issues/321). |
| 6 | Remove redundant rewind serialization copies and reuse scratch streams. `Nestlin` produces a copied blob, then `RewindStateMachine` copies it through another stream. Rewind adds about 75 KB/frame in this fixture. | Retain an immutable snapshot every frame, save-file compatibility, failure handling, and the paired RetroAchievements progress trailer. | [Issue #322](https://github.com/alondero/nestlin/issues/322). |
| 7 | Drain audio into consumer-owned reusable arrays under one lock, with bulk ring copies. `getAudioSamples` allocates arrays and queries availability under a separate lock; buffer reads wrap one sample at a time. Resampler wrap arithmetic is another candidate in this path. | Preserve all PCM samples, configured capacities, drop-oldest overflow, resampler phase, concurrent access and endian conversion. | Implemented below in [the audio pipeline measurements](#audio-pipeline-issue-324); related to the audible-dropout investigation in [#31](https://github.com/alondero/nestlin/issues/31). |
| 8 | Upload/draw the display when a new frame or geometry change requires it, and reduce contention on the shared frame lock. `AnimationTimer` currently uploads/draws on every UI pulse. | Keep input/overlay polling responsive, preserve pause/resize/ROM-load redraws, and prevent buffer reuse from tearing frames. All PPU cycles still run. | [Issue #321](https://github.com/alondero/nestlin/issues/321). |
| 9 | Benchmark a 256-slot opcode lookup array. `OpcodesRefactor.get` currently uses an integer-keyed map for each instruction; real instruction mixes may incur boxed keys and hashing. | Keep the same opcode objects, missing entries, unofficial opcodes, KIL, bus microcode, interrupts and in-flight restore. The JMP fixture does not establish its benefit. | [Issue #325](https://github.com/alondero/nestlin/issues/325). |
| 10 | Precompute exact pulse and full triangle/noise/DMC mixer tables. `Apu.mixAndBuffer` repeats divisions at each output sample. | Build entries with the current expressions/evaluation order; preserve expansion mixing, filter history, clipping and PCM bytes. Avoid approximate TND-index formulas. | Implemented below in [the audio pipeline measurements](#audio-pipeline-issue-324). |

An initial candidate was caching `FrameCounter.Result` objects. Allocation
profiling showed that the JVM already eliminates most of these after warm-up in
this workload, so it was displaced by the measured PPU costs above.

## Measurements

JDK 21.0.10 on Windows, 256 MB maximum heap, two JVM-visible processors, 300 warm-up
frames and 600 measured frames per scenario. The fixture patches the bundled
`nestest.nes` entry point to a stable JMP loop, keeping its header/CHR, and sets up
background tiles, eight groups of sparse sprites, and four APU channels. The core
runs through the production `Nestlin.stepCpuCycle` and frame-completion paths.
Rewind capture runs normally when enabled. Audio drains and output hashing occur
outside the measured section.

Three sequential baseline/candidate pairs ran with identical JVM settings. Both
trees used the PR's benchmark harness and Gradle task. For the baseline, a
detached worktree at the measured PR tree had the five emulator and SAM-callsite
files below restored from the exact master base; this keeps the harness identical
while measuring the base implementation. The table gives the median of each
run's statistic; the baseline allocation range reflects JIT escape-analysis
variation between processes. No compilation or test suite ran concurrently with
the measured JavaExec tasks.

Recreate the baseline worktree from the repository root in PowerShell, then run
the candidate command from the current PR checkout:

```powershell
git worktree add --detach ..\nestlin-perf-baseline 6254aa70e6a0c382024a2a19f5c7643a0892c468
git -C ..\nestlin-perf-baseline restore --source=6b3fd58e035a77602042820e146e3fcd3da0c1c7 -- `
  src/main/kotlin/com/github/alondero/nestlin/Memory.kt `
  src/main/kotlin/com/github/alondero/nestlin/ppu/Ppu.kt `
  src/main/kotlin/com/github/alondero/nestlin/ppu/PpuInternalMemory.kt `
  src/test/kotlin/com/github/alondero/nestlin/gamepak/Mapper19Test.kt `
  src/test/kotlin/com/github/alondero/nestlin/ppu/A12EdgeRateTest.kt
Push-Location ..\nestlin-perf-baseline
./gradlew.bat coreBench -Pframes=600 -Pwarmup=300 --no-daemon
Pop-Location
./gradlew.bat coreBench -Pframes=600 -Pwarmup=300 --no-daemon
```

| Scenario | Median frame time, before / after | p95, before / after | Core bytes/frame, before / after |
| --- | --- | --- | --- |
| NTSC, rendering + rewind | 4.128 / 3.576 ms | 5.206 / 4.801 ms | 1,593,097–2,102,089 / 126,153 |
| NTSC, rendering | 3.418 / 3.080 ms | 4.795 / 4.379 ms | 1,518,366–2,027,358 / 51,422 |
| PAL, rendering + rewind | 4.076 / 3.908 ms | 5.485 / 5.107 ms | 1,593,801–2,102,793 / 126,857 |
| NTSC, forced blank | 1.980 / 1.774 ms | 2.714 / 2.471 ms | 4,264 / 4,264 |

Rendering allocation fell by about 92–97%; median rendering frame times fell by
about 4–13%, and p95 by about 7–9% in these runs. The second pair had substantial
host noise (including p95 and maximum spikes), so the small three-run timing
sample should be read as directional. The blank path is an unchanged control; its
timing variation also shows host/JIT noise. Allocation is the more stable result.
This is evidence for reduced garbage-collection pressure, not a guarantee of
hitch-free playback.
These measurements exclude JavaFX, device playback, real-game instruction mixes,
bank switching and native achievement evaluation. Two visible processors and a
small heap constrain the JVM; they do not reproduce a particular slower CPU.

## Accuracy checks and reproduction

All twelve measured scenario pairs produced identical SHA-256 fingerprints for
the complete serialized state, concatenated rendered RGB frames, and PCM samples.
**One-time manual real-game validation (not reproducible from the checked-in
commands):** 600-frame baseline/candidate runs of Tetris (mapper 0), Adventures
of Lolo (mapper 1), and Kirby (mapper 4), using available local ROMs with rewind
enabled, matched the same three fingerprint types for each game. These checks
exercise real instruction mixes and mapper-driven banking rather than the
synthetic JMP loop; their timings were not included in the performance table.
The same four synthetic scenarios and three real-game checks were repeated
against the clean PR's master baseline and matched state/frame/audio fingerprints
within each pair. Master uses save-state version 12, whereas the measurement
worktree uses version 11; the versions have different serialized-state hashes.
That format change belongs to master and is not introduced by these optimisations.
The following fingerprints are the version-12 values reproduced by the PR's
`coreBench` command. The NTSC rendering scenarios (with/without rewind) share:

```text
state 3949b24927ec62b91a8aadd4bc8cc0134b5f84e8a95110976afb6dbb3c1d6206
frame 9e5eade7c9318d422f01f6db744fcdd4c8935a8071a841d20a25c7ee5270287d
audio 1d38748ca38b03760387bbf27d9c1544a22a88fc940578ce293f3c8647cf8da3
```

PAL rendering fingerprints:

```text
state ab755e897c2da6295a41dd817e7f6df88a870b16cd85c358abf2cbbcb19eb4e4
frame 9e5eade7c9318d422f01f6db744fcdd4c8935a8071a841d20a25c7ee5270287d
audio f069af0a8cc728eb13b67d1751dff898d77b5e88167793cc24078ba1538816c6
```

NTSC forced-blank fingerprints:

```text
state 853eb2c369c226aa20233422e173bd3f7ea746ec92962ed6660f879b9cc60eea
frame bc9dba3196b364a40e65478b534d78b2add4113ce63abe6bb3692ea4803ebaac
audio 47b9b73167e68c39bb2d317d264cf3e1d19765d1abdb3492465341c6ca38c371
```

Run the repeatable benchmark, including fingerprints:

```powershell
./gradlew.bat coreBench
./gradlew.bat coreBench -Pframes=1200 -Pwarmup=600
./gradlew.bat testPerformance
```

`RenderingAllocationTest` measures the real rendering path after warm-up, with a
256 KiB/frame budget for NTSC and PAL. It failed on the baseline at approximately
2 MB/frame and passes with the changes. It asserts allocation rather than timing,
so wall-clock performance remains a manual measurement. The `performance` tag
runs in its own JVM through `testPerformance`, also required by `check`/`build`:
unrelated test call sites otherwise change JIT profiles and invalidate the
allocation budget. The task requires JVM thread-allocation measurement and
fails explicitly if `com.sun.management.ThreadMXBean` is unavailable or does
not support it; this lane must not pass with the allocation guard skipped.

The mirroring tests cover horizontal, vertical, four-screen, and both one-screen
table selections through the optimized `$2000-$2FFF` lookup. The `$3000-$3EFF`
case adds coverage for the existing recursive mirror path; that path does not
call the changed lookup, so this case is supplemental rather than a regression
test for the optimization. Mapper-19 override, A12, sprite selection/overflow
and OAM tests also cover the changed routing. CI runs `testPerformance --warning-mode=fail`,
validates all four issue #312 task-graph consumers, resolves the strict
`build shadowJar --dry-run` lane, and executes `uberJar` with strict warnings.
No save-state version bump is necessary:
the serialized fields and their order have not changed.

Review follow-up corrected stale helper/UI names, added explicit coverage for the
full nametable mirror range and both one-screen table selections, and included
the isolated `testPerformance` lane in the source/runtime task-graph guards and
PR CI. It also documents the fast lookup's address precondition, makes missing
allocation measurement fail loudly, and drains audio during measured frames to
match the benchmark's steady state.

The full clean PR checkout build against current master passed before the review
follow-up: 1,892 functional tests and both isolated allocation checks passed,
with two existing skips. Post-follow-up checks passed: all 15
`NametableMirroringTest` cases, all eight `TaskGraphLintTest` cases, both isolated
allocation checks, the four-consumer/eight-edge runtime validator, the strict
`build shadowJar --dry-run` lane, and documentation lint (36 Markdown files).
The full Mesen2 comparison lane was not run; equivalence here is against the
unchanged baseline implementation, alongside the existing functional suite.

Suggested next session: implement #322 first, remove redundant rewind copies with
explicit snapshot ownership, and compare `coreBench` state/frame/audio hashes and
allocation before/after. Then profile the production UI for #321.

## Audio pipeline (issue #324)

Measured 2026-10-03 against `4b426fa56213aeec178affb62a096904a5068056`,
using JDK 21 on Windows and a Ryzen 9 3900X. This is desktop evidence;
**slower-hardware and actual device-underrun acceptance remains pending**.
These measurements do not establish a fix for the audible dropouts in #31.

Playback now supplies one reusable 8192-sample input array. The ring drains a
bounded prefix under one lock, using at most two contiguous copies. Empty drains
return zero without allocation, and unused destination storage is untouched.
The allocating convenience API remains for existing callers and shares its empty
array. Overflow still drops the oldest sample, and capacities are unrestricted
by power-of-two assumptions. Producer writes retain their per-sample lock.

The resampler accepts a valid prefix length, so stale contents in reused storage
never reach interpolation. Only its known nonnegative head/tail increments use
conditional wrap; signed-offset lookup, discard, negative-position recovery and
rounding retain the original behavior. PCM conversion is shared by playback and
the device probe, retaining both 16-bit byte orders and the existing 8-bit fallback.

The exact mixer uses 31 pulse entries plus every 16 * 16 * 128 TND tuple. Both
arrays are initialized once and shared across APUs, with 262,392 bytes of Double
payload (about 256 KiB), plus array/object headers. Initialization retains the
original expression order and zero cases. Expansion mixing, analog filters,
clipping, mute behavior, sample accumulation, cycle timing and serialized fields
are unchanged.

### Measurements and reproduction

```text
./gradlew audioBench
./gradlew coreBench -Pframes=120 -Pwarmup=150
./gradlew audioDeviceBench -PaudioSeconds=30
./gradlew audioDeviceBench -PaudioSeconds=5 -PaudioStallMs=300
./gradlew test
./gradlew testPerformance
```

The local compiler required `-Dorg.gradle.jvmargs=-Xmx768m`,
`-Pkotlin.daemon.jvmargs=-Xmx1536m` and `--max-workers=1` after the initial clean
compilation exhausted its default heap. Benchmark JVMs use `-Xmx256m` and
`-XX:ActiveProcessorCount=2`; limiting reported processors does not emulate
slower hardware. Allocation/CPU measurement requires a JVM exposing
`com.sun.management.ThreadMXBean`; the allocation test fails if unsupported.

| Measurement | Original | Optimized | Interpretation |
| --- | --- | --- | --- |
| Nonempty APU drain allocation (roughly 735 samples/poll) | 1,488 bytes/poll | 0 bytes/poll | Repeated empty and nonempty drains also pass the isolated allocation guard. |
| Ring drain elapsed time, same-process frozen baseline, 735 samples | 4.19 ns/sample | 0.19 ns/sample | 10,000 measured batches after 2,000 warmup batches; excludes producer work. |
| Ring drain CPU time in that run | 2.13 ns/sample | 2.13 ns/sample | Windows thread CPU clock is too coarse to resolve this short operation reliably; use elapsed time for the drain comparison. |
| DAC-only mixer elapsed time, two warmed runs | 9.14-9.60 ns/sample | 2.22-6.15 ns/sample | Median of seven 5-million-sample rounds; formula and table sum checks match exactly. The absolute gain is small at 44.1 kHz. |
| NTSC producer tick + mix CPU time | 722.80 ns/output sample | 552.73-722.80 ns/output sample | Includes channel clocks and per-sample ring writes; scheduling/JIT variation prevents attributing the full difference to tables. |
| PAL producer tick + mix CPU time | 680.27 ns/output sample | 531.46-616.50 ns/output sample | Same limitation; 1,000 measured batches after 500 warmup batches. |

Ring writes, including the unchanged lock and wrap, measured 10.6-19.1 ns/sample
in the same-process run. This is an uncontended cost measurement, not a
contention profile; no batching or producer/consumer redesign is justified by
it. A future redesign requires a production contention profile first. The APU
poll CPU measurements printed zero on this host because they were below the
thread CPU clock's resolution; zero does not mean zero cost.

`coreBench` state, frame and audio hashes matched before/after for all four
workloads. Rendering allocation was unchanged, as expected: its measured section
excludes audio drains. Frame medians varied substantially between runs on this
shared host, so no full-core speedup is claimed from these short measurements.

### Device evidence and remaining gate

`audioDeviceBench` runs the rendering/rewind fixture at the selected region's
refresh rate on a producer thread. Its consumer uses the same reusable drain,
resampler and encoder as playback. It requests an 8192-byte mono 44.1 kHz line,
reports the actual opened buffer size, excludes a one-second startup interval and
shutdown, and prints empty/idle polls separately from device queue starvation
and Java Sound STOP events. It plays sound and needs a working audio output.

Java Sound documents STOP events when source-line output underflows; see the
[SourceDataLine contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/sound/sampled/SourceDataLine.html).
A forced consumer stall checks whether the selected backend actually reports
those events. Zero events from an uncalibrated backend cannot establish zero
actual device underruns. Device queue starvation counts transitions to an empty
host line buffer, sampled before writes, and is a lower-bound observation rather
than a hardware-driver underrun count.

A five-second unstalled desktop run observed no STOP events in either region.
PAL nevertheless recorded 2,901 empty/idle polls with at least 5,782 queued bytes;
NTSC's minimum queued bytes was 5,894. Empty producer polls did not imply device
starvation. The 300 ms calibration run observed one queue-starvation episode per region
(minimum queued bytes zero), but no STOP events. This backend therefore failed
STOP-event calibration, and actual device-underrun measurement is unavailable.

Before merging, run longer unstalled and calibrated device measurements on the
slower target hardware, record the JVM/device/region/buffer settings and counts,
and compare against the unchanged base using the same fixture. If forced stalls
produce no STOP events, use backend-specific device telemetry for actual
underruns. No actual hardware-driver count or dropout improvement is claimed here.

### Equivalence coverage

- Exhaustive raw Double-bit checks cover all pulse sums and all 32,768 TND tuples.
- Six PCM/state SHA-256 fixtures were captured before production edits. NTSC/PAL
  scripts exercise active DMC DMA, expansion contributions, filter history,
  clipping, mute/unmute, save/load and a backlog exceeding ring capacity.
- Random ring operations match a FIFO for capacities 1, 3, 7 and 100, including
  overflow, clear, wrap, empty/zero-length and destination-bounded reads.
- A simultaneous producer/consumer test checks ordered lossless wraps with
  reusable destination storage; the APU drain test compares small repeated
  drains against the allocating API and checks untouched tails.
- The frozen resampler implementation is a differential oracle across capacities
  1, 3, 7 and 17 and output rates 22.05, 44.1, 48 and 96 kHz, including partial
  pushes, overflow, negative positions, clear and chunked/empty output.
- PCM encoding matches all 65,536 signed values in both byte orders; the 8-bit
  fallback and unused destination tail are checked separately.

Verification passed: the full fast suite ran 1,924 tests with two existing skips;
all three isolated rendering/drain allocation checks passed, as did documentation
lint (37 Markdown files), `validateTaskGraph`, and the strict
`build shadowJar --dry-run --warning-mode=fail` task resolution. The final review
follow-up was checked with the audio suite plus `KonsistArchitectureTest` and
documentation lint. Mesen2/native contract lanes were not run; this change's
accuracy evidence is equivalence to the unchanged PCM/state baseline.
