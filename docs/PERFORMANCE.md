# Accuracy-preserving performance audit

Audit date: 2026-10-02. Baseline: `1bd6ef3ebd02567a194e9afb81c95a5da7ce0056`.

The first three opportunities below were implemented in this session. They remove
temporary allocations while retaining the same emulated cycles, bus accesses,
mapper callbacks, sprite selection and save-state format. The remaining entries
are candidates requiring measurement in their own production paths.

| Rank | Opportunity and evidence | Accuracy constraints | Status |
| --- | --- | --- | --- |
| 1 | Give PPU CHR/nametable callbacks primitive method signatures. Generic Kotlin function callbacks boxed addresses on each read; allocation stacks point to `PpuInternalMemory.get` during background and sprite fetches. | Preserve callback ordering, CHR read updates to the CPU data bus, nullable nametable fall-through, and A12 detection. | Implemented: primitive functional interfaces in `PpuInternalMemory`, wired by `Memory`. |
| 2 | Remove temporary nametable address pairs and boxed offsets. `mapNametableAddress` boxed offsets even where the JVM eliminated the pair itself. | Resolve current mirroring on every access, retain all five modes, `$3000` mirrors, and mapper overrides. | Implemented: select the backing array directly and use the low ten address bits as its offset. |
| 3 | Check sprite Y before constructing a complete sprite record. `getSprite` ran for up to 64 candidates on every rendering scanline. | Retain rotated OAMADDR order, the eight-sprite limit, ninth-sprite overflow, height/flips, and immutable evaluation-time snapshots. | Implemented: construct `SpriteData` only after selecting a sprite. |
| 4 | Reuse selected-sprite scratch slots and remove per-pixel list iterators and scanline `addAll` copies. Allocation samples still include `ActiveSprite`, `SecondaryOamEntry`, list iterators and backing arrays. | Preserve shift-register updates, fetch order/dummy reads, A12 edges, sprite-zero hits, and mid-scanline saves. | [Issue #323](https://github.com/alondero/nestlin/issues/323). |
| 5 | Use primitive loops or a measured packed-pixel path for UI RGB conversion. `Application.frameUpdated` uses nested `withIndex().forEach` on primitive pixel rows. | Preserve exact RGB output, screenshots and buffer ownership. Measure JavaFX allocations first: the headless benchmark excludes this path. | [Issue #321](https://github.com/alondero/nestlin/issues/321). |
| 6 | Remove redundant rewind serialization copies and reuse scratch streams. `Nestlin` produces a copied blob, then `RewindStateMachine` copies it through another stream. Rewind adds about 75 KB/frame in this fixture. | Retain an immutable snapshot every frame, save-file compatibility, failure handling, and the paired RetroAchievements progress trailer. | [Issue #322](https://github.com/alondero/nestlin/issues/322). |
| 7 | Drain audio into consumer-owned reusable arrays under one lock, with bulk ring copies. `getAudioSamples` allocates arrays and queries availability under a separate lock; buffer reads wrap one sample at a time. Resampler wrap arithmetic is another candidate in this path. | Preserve all PCM samples, configured capacities, drop-oldest overflow, resampler phase, concurrent access and endian conversion. | [Issue #324](https://github.com/alondero/nestlin/issues/324), related to the audible-dropout investigation in [#31](https://github.com/alondero/nestlin/issues/31). |
| 8 | Upload/draw the display when a new frame or geometry change requires it, and reduce contention on the shared frame lock. `AnimationTimer` currently uploads/draws on every UI pulse. | Keep input/overlay polling responsive, preserve pause/resize/ROM-load redraws, and prevent buffer reuse from tearing frames. All PPU cycles still run. | [Issue #321](https://github.com/alondero/nestlin/issues/321). |
| 9 | Benchmark a 256-slot opcode lookup array. `OpcodesRefactor.get` currently uses an integer-keyed map for each instruction; real instruction mixes may incur boxed keys and hashing. | Keep the same opcode objects, missing entries, unofficial opcodes, KIL, bus microcode, interrupts and in-flight restore. The JMP fixture does not establish its benefit. | [Issue #325](https://github.com/alondero/nestlin/issues/325). |
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

Three sequential baseline/candidate pairs ran from the same compiled baseline
and candidate with identical JVM settings. The table gives the median of each
run's statistic; the baseline allocation range reflects JIT escape-analysis
variation between processes. No compilation or test suite ran concurrently with
these paired measurements.

| Scenario | Median frame time, before / after | p95, before / after | Core bytes/frame, before / after |
| --- | --- | --- | --- |
| NTSC, rendering + rewind | 3.089 / 2.520 ms | 4.526 / 3.821 ms | 1,593,097–2,078,953 / 126,153 |
| NTSC, rendering | 3.031 / 2.665 ms | 4.291 / 3.912 ms | 1,518,366–2,004,222 / 51,422 |
| PAL, rendering + rewind | 3.350 / 2.982 ms | 4.786 / 4.337 ms | 1,593,801–2,079,657 / 126,857 |
| NTSC, forced blank | 1.707 / 1.586 ms | 2.278 / 2.227 ms | 4,264 / 4,264 |

Rendering allocation fell by about 92–97%; median rendering frame times fell by
about 11–18%, and p95 by about 9–16% in these runs. The blank path is an unchanged
control: its timing variation illustrates host/JIT noise. This is evidence for
reduced garbage-collection pressure, not a guarantee of hitch-free playback.
These measurements exclude JavaFX, device playback, real-game instruction mixes,
bank switching and native achievement evaluation. Two visible processors and a
small heap constrain the JVM; they do not reproduce a particular slower CPU.

## Accuracy checks and reproduction

All twelve measured scenario pairs produced identical SHA-256 fingerprints for
the complete serialized state, concatenated rendered RGB frames, and PCM samples.
Additional 600-frame baseline/candidate runs of Tetris (mapper 0), Adventures of
Lolo (mapper 1), and Kirby (mapper 4), using the available local ROMs with rewind
enabled, matched the same three fingerprint types for each game. These checks
exercise real instruction mixes and mapper-driven banking rather than the
synthetic JMP loop; their timings were not included in the performance table.
The NTSC rendering scenarios (with/without rewind) shared these fingerprints:

```text
state 958be09ab0dd007a5fad023930421b4074b53bc32ef6324ff0db1c394703ce06
frame 9e5eade7c9318d422f01f6db744fcdd4c8935a8071a841d20a25c7ee5270287d
audio 1d38748ca38b03760387bbf27d9c1544a22a88fc940578ce293f3c8647cf8da3
```

PAL rendering fingerprints:

```text
state 11956b0c4116021110e8be7f564185bcd2271a07523e3f15f020d226c889c18d
frame 9e5eade7c9318d422f01f6db744fcdd4c8935a8071a841d20a25c7ee5270287d
audio f069af0a8cc728eb13b67d1751dff898d77b5e88167793cc24078ba1538816c6
```

NTSC forced-blank fingerprints:

```text
state 1fa276d2831dc471427afb736e0cddfca5b974393bb56a0b41861da1c82e19e0
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
allocation budget. JVMs without thread allocation measurement explicitly skip
this check.

The existing nametable, mapper-19 override, A12, sprite selection/overflow and OAM
tests also cover the changed routing. No save-state version bump is necessary:
the serialized fields and their order have not changed.

Independent review of this session's diff found no actionable Standards findings
against `CLAUDE.md`/the test strategy, and no Spec findings against the request for
ten opportunities, three implemented changes, preserved accuracy and deferred
GitHub issues.

Final `gradlew.bat build` passed: 1,871 functional tests passed with two existing
skips, both isolated allocation checks passed, and repository lint checks passed.
The full Mesen2 comparison lane was not run; equivalence here is against the
unchanged baseline implementation, alongside the existing functional suite.

Suggested next session: implement #322 first, remove redundant rewind copies with
explicit snapshot ownership, and compare `coreBench` state/frame/audio hashes and
allocation before/after. Then profile the production UI for #321.
