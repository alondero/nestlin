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
| 7 | Drain audio into consumer-owned reusable arrays under one lock, with bulk ring copies. `getAudioSamples` allocates arrays and queries availability under a separate lock; buffer reads wrap one sample at a time. Resampler wrap arithmetic is another candidate in this path. | Preserve all PCM samples, configured capacities, drop-oldest overflow, resampler phase, concurrent access and endian conversion. | [Issue #324](https://github.com/alondero/nestlin/issues/324), related to the audible-dropout investigation in [#31](https://github.com/alondero/nestlin/issues/31). |
| 8 | Upload/draw the display when a new frame or geometry change requires it, and reduce contention on the shared frame lock. `AnimationTimer` currently uploads/draws on every UI pulse. | Keep input/overlay polling responsive, preserve pause/resize/ROM-load redraws, and prevent buffer reuse from tearing frames. All PPU cycles still run. | [Issue #321](https://github.com/alondero/nestlin/issues/321). |
| 9 | Index opcode lookup with 256 slots. The mixed-trace benchmark measures map hashing/boxing and production CPU execution. | Keep the same opcode objects, missing entries, unofficial opcodes, KIL, bus microcode, interrupts and in-flight restore. | Implemented in [issue #325](https://github.com/alondero/nestlin/issues/325); measurements and constraints below. |
| 10 | Precompute exact pulse and full triangle/noise/DMC mixer tables. `Apu.mixAndBuffer` repeats divisions at each output sample. | Build entries with the current expressions/evaluation order; preserve expansion mixing, filter history, clipping and PCM bytes. Avoid approximate TND-index formulas. | [Issue #324](https://github.com/alondero/nestlin/issues/324). |

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

## Indexed opcode dispatch (issue #325)

For CPU contributors, run the manual benchmark with the bundled ROM and golden
trace (no external assets):

```powershell
./gradlew.bat opcodeBench -Psamples=600 -Pwarmup=300 --console=plain
```

`opcodeBench` is owned by the CPU/performance test harness. Positive `samples`
and non-negative `warmup` control measurement batches, not emulated frames.
The task follows Gradle's normal success/failure exit status; failed capture or
lookup-equivalence checks fail the task. It has no timing threshold and does not run as a
JUnit test or CI allocation guard.

The 8,991-instruction nestest trace includes 225 distinct opcode bytes, 197
unofficial operations, and 5,629 bytes above the JVM's usual small-Integer cache.
Each lookup sample replays this exact byte order 64 times, adding opcode cycle
counts to an observed checksum. Map and array runs alternate their order in each
batch and use the same opcode instances. The array uses the same bounds check as
the production candidate. Lookup timing isolates resolution; it does not execute
opcode semantics. Allocation and thread CPU time use JVM management counters;
unsupported counters print `unavailable`. Wall-clock median/p95 are reported
separately. CPU time is averaged across all measured batches because Windows CPU
counters can quantize a short batch to zero.

The CPU measurement resets the same serialized starting state outside each timed
section and executes the nestest ROM via `Cpu.tick`, one call per bus cycle.
Capture stops when the CPU becomes idle or completes as many instructions as the
reference trace contains; it finishes the last instruction's bus cycles and
does not assert any particular halt opcode. In the measured revision, an
incorrect `$C3` KIL registration truncates execution to 5,823 actual instructions.
The bundled oracle identifies `$C3` as DCP `(indirect,X)`; the separate mapping fix
is tracked in [#333](https://github.com/alondero/nestlin/issues/333). The benchmark
will capture a longer run when that bug is fixed, without enforcing the erroneous
mapping. PPU/APU clocks, tracing, save/load, and hashing are excluded from isolated
CPU timing. These CPU-only timings exclude the production loop's PPU/APU work.
Allocations are measured rather than inferred from boxing.

**Existing oracle coverage gap:** `GoldenLogTest` validates only 5,003 of the
8,991 bundled reference rows (55.6%). `Logger.opcodeLog` lacks unofficial-opcode
formatters and throws at row 5,004 (`$04`, NOP zero-page). The test catches this
exception and bounds comparison by `currLog.size`, so a truncated log passes.
The remaining 3,988 rows (44.4%), including every unofficial-opcode case, have no
end-to-end assertion against this oracle. `opcodeBench` executes part of that
segment, but its hashes compare with the original implementation and can preserve
existing wrong behavior; lookup-only replay of all 8,991 bytes does not assert
their semantics. [#334](https://github.com/alondero/nestlin/issues/334) tracks full
oracle coverage and truncation failures. See the
[testing strategy](TESTING_STRATEGY.md#cpu-oracle-coverage) for the current limits.

Opcode fixes currently need coordinated updates to the canonical `Opcodes`
definitions, the independent `Logger.opcodeLog` formatters, and
`OpcodeCycleTableTest`. The dispatcher and logger can silently disagree;
an implemented opcode can still terminate golden logging. The indexed lookup
introduces no additional definition site and does not fix that existing drift.

Separately, the harness prints SHA-256 fingerprints for nestest through
`Nestlin.stepCpuCycle`, and 120 rendering frames in both NTSC and PAL with a
synthetic mixed kernel (loads/stores, ALU, indexed page crossings, stack,
subroutines, branches, unofficial loads/stores and read-modify-writes). The mixed
kernel reuses the rendering fixture's background, sprites and four APU channels.
Bus fingerprints encode the CPU cycle number, operation, address and value in
access order, including dummy reads and RMW writes. Frame/audio fingerprints hash
each published RGB frame and drained PCM sample. Tracing and hashing run outside
all performance measurements. Compare these hashes and cycle/access counts
between trees; no emulated cycles are batched or skipped.

### Dispatch measurements and equivalence

Measured revision: `c4eb675`. Measured 2026-10-03 on Windows, JDK 21.0.10, AMD Ryzen 9 3900X, 256 MB maximum
heap and two JVM-visible processors. Three baseline processes ran before three
candidate processes, each with 300 warm-up and 600 measured samples. The baseline
used the opcode implementation from `4b426fa56213aeec178affb62a096904a5068056`;
the identical harness then measured the indexed candidate in the same worktree.
No test suite or compilation ran concurrently with a benchmark JVM.
`-XX:ActiveProcessorCount=2` matches `coreBench`'s constrained JVM configuration
for comparable local runs, limiting GC/JIT parallelism along with the small heap.
It also limits compiler threads during warm-up and can increase timing variance;
it does not simulate a particular two-core CPU. These settings are symmetric
between baseline and candidate. The retained `getOrNull` bounds check also runs
on each indexed lookup despite dispatch inputs already being bytes; its cost is
included in the measurement and preserves the accessor's invalid-input behavior.

The table reports the median of each process's reported statistic. Lookup rows
use all six processes (both lookups run in each JVM); CPU rows use the three
baseline or candidate processes respectively. Times are nanoseconds per
instruction; allocations are bytes per instruction.

| Measurement | Map | Indexed array |
| --- | --- | --- |
| Lookup wall median | 6.927 | 0.660 |
| Lookup wall p95 | 13.358 | 1.210 |
| Lookup mean thread CPU time | 6.019 | 0.588 |
| Lookup allocation | 10.017 | 0.000 |
| Executed CPU wall median | 140.323 | 108.192 |
| Executed CPU wall p95 | 357.050 | 141.044 |
| Executed CPU mean thread CPU time | 143.111 | 102.861 |
| Executed CPU allocation | 10.315 | 0.250 |

The actual ROM prefix dispatches 5,823 instructions (184 distinct bytes) in
16,648 cycles before the incorrect `$C3` halt in that revision. Indexed dispatch removes about
10 bytes of allocation per instruction in this mix, reducing measured CPU
allocation by 97.6%. Lookup costs consistently favour the array within the same
JVM. CPU timing is directional: host noise was substantial, including a baseline
p95 of 939 ns/instruction. The unchanged map lookup control's median also fell
from 7.239 to 6.340 ns between the baseline and candidate groups. These sequential
runs cannot attribute the entire CPU timing improvement to indexed dispatch.

All six runs matched all four fingerprints and instruction/cycle/access counts
for each scenario. NTSC mixed rendering covered 3,573,653 cycles and 966,989
instructions; PAL covered 3,989,693 cycles and 1,079,565 instructions. Each cycle
produced the same ordered bus access in these workloads. Fingerprints:

```text
nestest
state 6be4e6b008c7a452ee256f6e484e33d8dc75e778c35260b9886e57ddc1640a81
bus   3998919b7de2f0bedae80a8bb4df9c7ba4cfc72d178c8ae33245f893a2492fb8
frame dd493585bde88d5307e760b29cdd3a721ad9fa8fa5cb16618c77889c0d6be401
audio 0d14e0beea945b6197f9da01278f8fe5ab9db98f50dda9761b68555e332837a1

NTSC-mixed-render
state ecb7666fe7e36509e276f35d9a53c0b3e0c79434a4013a3afc84feda73de93ea
bus   c36a04efe2d8393f2384ad881690ee9092897724eee7f0acd0279d7413c2aa30
frame 3c9dd78906a9a0b88910ee6c3702c40f34c8609ab7cda14a286168dd338e7a13
audio ad6009c8f4248aedb46ef57686430da7237abb448ad13b210977fa266f6a8a50

PAL-mixed-render
state 5a8ddaa4c1a92fa81a93e939481929faad75951b9e933276557aae1bcb13b16d
bus   c25cd31876adaa6b01dc62bb2c9047d0831543e33b767005119e2a7e5571edc0
frame e81b05a87f927377dce2e20013a710cab8f1c77a7c47893d58fb7d83f3b00270
audio 2e3458beb80fefb49312fc95d5a7cd33d6319cf4a5695cedc68293a7ca3ebc59
```

The production table is private and populated once from the canonical map.
The map stays available for diagnostics, completeness/cycle tests, and the
original lookup benchmark. Exhaustive identity assertions cover all 256 slots;
the four unmapped bytes stay null, and out-of-range integers remain null rather
than wrapping. `Cpu.tick` and mid-instruction restore already share this lookup;
their microcode and save-state format are unchanged.

To reproduce the baseline using the candidate's harness in a separate checkout:

```powershell
git worktree add --detach ..\nestlin-opcode-baseline HEAD
git -C ..\nestlin-opcode-baseline restore --source=4b426fa56213aeec178affb62a096904a5068056 -- `
  src/main/kotlin/com/github/alondero/nestlin/cpu/opcode/Opcodes.kt
# Adapt the original type name to the completed rename; retain its map lookup.
$opcodeBaseline = (Resolve-Path ..\nestlin-opcode-baseline\src\main\kotlin\com\github\alondero\nestlin\cpu\opcode\Opcodes.kt).Path
$opcodeOriginal = [System.IO.File]::ReadAllText($opcodeBaseline)
$opcodeUtf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($opcodeBaseline, $opcodeOriginal.Replace('OpcodesRefactor', 'Opcodes'), $opcodeUtf8)
Push-Location ..\nestlin-opcode-baseline
./gradlew.bat opcodeBench -Psamples=600 -Pwarmup=300 --console=plain
Pop-Location
# Run the same command in the candidate checkout, then compare all hashes/counts.
```

Verification passed: all 456 focused CPU/APU/DMA/interrupt/save-state checks;
the full fast suite (1,916 passed, two existing external-fixture skips for Akira
and Star Soldier); both isolated rendering allocation checks; documentation lint;
and `git diff --check`. The full command was
`./gradlew.bat test testPerformance docsLint --warning-mode=fail`.
Mesen2 and commercial-ROM comparisons were not run; equivalence here is against
the original implementation using bundled fixtures. This passing suite does not
establish correctness of the unofficial portion omitted by `GoldenLogTest`.
PR review exposed the mapping and oracle-coverage bugs tracked above.

Suggested next session: implement #322 first, remove redundant rewind copies with
explicit snapshot ownership, and compare `coreBench` state/frame/audio hashes and
allocation before/after. Then profile the production UI for #321.
