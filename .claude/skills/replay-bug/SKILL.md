---
name: replay-bug
description: Reproduce a reported NES game bug (hang, freeze, crash, graphical glitch, desync) deterministically from a ROM + an FM2 input log, using Nestlin's headless `replay` command. Invoke whenever a user reports or attaches a bug repro for a specific game, says the emulator hangs/freezes/crashes on a ROM, hands you an `.fm2` file, asks to reproduce or bisect a game-specific failure, or wants to turn a repro into a regression fingerprint/bundle. This is the first step before debugging any game-specific issue.
---

# Replay-as-test: reproduce a game bug from a ROM + FM2

Nestlin has a headless `replay` subcommand (issue #62). Given a ROM and an `.fm2` movie it
**deterministically** boots cold, replays the inputs, and lands the machine in the exact state the
bug occurs in — no GUI, no wall clock, no threads. The same (ROM, FM2) pair produces byte-identical
state on every machine. This is how you reproduce a user-reported bug before you debug it.

## When to use this

- A user reports "game X hangs / freezes / crashes / glitches when I do Y" — get or record an FM2,
  then replay it to land the failure state on demand.
- A user attaches an `.fm2` (often inside a `.zip`) to an issue. That file + the ROM **is** the repro.
- You need a stable, repeatable starting point to bisect, instrument, or compare against Mesen2.
- You want to capture a deterministic fingerprint so the fix gets a regression gate.

## The command

```bash
# Build once (shadow JAR):
./gradlew shadowJar      # → build/libs/nestlin-all.jar

# Record mode — print fingerprints + write a PNG of the frame reached:
java -jar build/libs/nestlin-all.jar replay <rom.nes> <movie.fm2>

# Capture a specific (mid-movie) frame instead of the final one:
java -jar build/libs/nestlin-all.jar replay <rom.nes> <movie.fm2> --frame N --png out.png

# Verify mode — assert the run reproduces a previously-recorded fingerprint:
java -jar build/libs/nestlin-all.jar replay <rom.nes> <movie.fm2> \
    --expect-state <sha> --expect-frame <sha>
```

Flags: `--frame N` (replay only the first N input frames), `--png PATH` (default `<movie>.png`),
`--expect-state`/`--expect-frame` (switch to verify mode), `--no-verify-checksum` (skip the
ROM-vs-movie guard).

**Output** (record mode) is machine-readable lines: `rom=`, `movie=`, `frames=`, `state=<sha256>`,
`frame=<sha256>`, `png=<path>`.

**Exit codes** — branch on these in CI/agents:

| Code | Meaning |
|---|---|
| `0` | ran clean (record), or verify matched |
| `1` | verify mismatch (a diff line names which of state/frame differs) |
| `2` | usage error, or the ROM's checksum doesn't match the movie's `romChecksum` |
| `3` | the emulator **threw** mid-replay (crash, unimplemented mapper) — a clean failure, not a stack trace |

## The two fingerprints, and the key diagnostic

- `state=` — SHA-256 over the full `SaveState` serialisation (CPU + RAM + PPU + APU + mapper). The
  authoritative, pixel-independent fingerprint. Prefer this for regression gates (state diff beats
  pixel diff — project policy).
- `frame=` — SHA-256 over the RGB framebuffer. Pairs with the PNG; this is the *human* artefact.

**A hang does not throw.** It replays to the end and lands in a frozen frame → `exit 0`, and the
PNG/hash reveal the freeze. So a clean exit with a frozen-looking PNG is the expected shape of a
hang repro, not a failure of the tool.

**Diagnostic trick — run `--frame N` at several points and compare the two hashes:**

- `frame=` frozen (identical across frames) **but** `state=` still advancing ⇒ the CPU is executing
  while the display is stuck → a **render/blank bug**, not a CPU lockup.
- both frozen ⇒ the CPU has genuinely stalled (spin loop / deadlock) → look at the PC / NMI/IRQ path.

This is exactly how issue #141 (Akira hangs on game start, Mapper 33) was characterised: title screen
(frame 100) renders, but from ~frame 333 `frame=` froze while `state=` kept advancing — a render bug.

## Reproducing a reported bug — workflow

1. **Get the FM2.** If attached to an issue inside a zip, download + extract it. FM2 is text; its
   header carries `romChecksum` (base64 MD5 of PRG+CHR), so the movie self-identifies its ROM.
2. **Find the ROM.** Local working ROMs are under `X:\src\nestlin\testroms\`; the full NO-INTRO
   library is at `S:\Media\Nintendo NES\Games\` (see `CLAUDE.local.md` for all paths). Use the exact
   filename from the FM2's `romFilename` header.
3. **Replay it.** Run record mode. The checksum guard (`exit 2`) tells you immediately if you grabbed
   the wrong ROM dump.
4. **Confirm the bug.** Open the PNG (`Read` the `png=` path). Use `--frame N` to capture before/after
   the failing transition and apply the state-vs-frame diagnostic above.
5. **Hand off to the right tool.** `replay` reproduces and fingerprints; for *why* it diverges,
   compare against the Mesen2 oracle (see the **mesen** skill) at the first diverging frame.

## Turning a repro into a regression gate

Record the `state=` hash once, then add a verify-mode invocation (or a test that calls
`com.github.alondero.nestlin.cli.ReplayCommand.run` with `expectState`) so the fix can't silently
regress. The bundle is just **ROM + FM2** — no savestate needed (replay always cold-boots; the FM2's
`romChecksum` pins the ROM). Caveat: most ROMs are not in git (only `nestest.nes` is), so a committed
bundle against a NO-INTRO ROM must skip cleanly when the ROM is absent (e.g. on CI without the
library); a bundle against `nestest.nes` always runs.

## Code touchpoints

| File | Role |
|---|---|
| `src/main/kotlin/com/github/alondero/nestlin/cli/ReplayCli.kt` | arg parsing → `Parsed.Ok/Error` |
| `src/main/kotlin/com/github/alondero/nestlin/cli/ReplayCommand.kt` | boot + replay + hash + PNG; `Options`/`Outcome`; exit-code constants |
| `src/main/kotlin/com/github/alondero/nestlin/ui/Application.kt` | `main` dispatches `replay` **before** `Application.launch` (so JavaFX never starts) |
| `src/main/kotlin/com/github/alondero/nestlin/movie/` | the FM2 format + deterministic replay engine `replay` builds on (see the FM2 memory entries) |
| `src/test/kotlin/com/github/alondero/nestlin/cli/` | `ReplayCommandTest` / `ReplayCliTest` — the worked examples |

## Worktree caveat

Determinism needs the game's **mapper** to be implemented on the branch you build. A mapper landed on
`master` may be absent on an older worktree branch — symptom is `exit 3` "Mapper N not implemented".
Fix: `git checkout master -- <mapper files>` into the worktree (or merge master) before replaying.

## See also

- **mesen** skill — the reference-emulator oracle for *why* a frame diverges, once `replay` has
  reproduced *that* a frame diverges.
- **nes-emulation-expert** skill — hardware/timing reasoning for the underlying bug.
- FM2 engine background: memory entries `movie-replay-fm2-engine-2026-06-04`,
  `movie-live-record-play-2026-06-06`, `replay-as-test-cli-issue-62-2026-06-07`.
