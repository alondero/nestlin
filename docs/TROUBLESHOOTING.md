# Troubleshooting

This page is for players and bug reporters who need to diagnose a Nestlin failure and provide safe, reproducible evidence.

Start with the smallest reproducible command. Include the command, Nestlin commit or release, operating system, JDK version, ROM filename and checksum, and the relevant output in a report. Do not attach a commercial ROM unless you have the right to redistribute it.

## The application does not start

1. Check Java with `java -version`; Nestlin requires JDK 21.
2. Run `./gradlew build` to distinguish a build problem from a JavaFX/runtime problem.
3. Run `./gradlew run --args="path/to/game.nes"` from the repository root so Gradle resolves resources and dependencies correctly.
4. If JavaFX cannot create a window, verify that the host has a working desktop display server. Headless tools such as `bootcheck` and `replay` do not require JavaFX.

## The ROM is rejected or the mapper is unsupported

Use the header decoder before reporting a mapper problem:

```bash
python tools/rom_info.py info path/to/game.nes
```

Check the mapper, submapper, PRG/CHR sizes, mirroring, battery flag, and region. Header labels can be wrong, especially for Namco 108/DxROM-family dumps. `rom_info.py` can scan a library and create a patched copy for the documented Namco 108 case; it never modifies the source ROM.

If the reported mapper number is not in [`MAPPER_SUPPORT.md`](../MAPPER_SUPPORT.md), the emulator currently throws `UnsupportedOperationException`. A mapper request needs a legally shareable reproduction recipe and hardware/reference sources; it does not need the ROM itself.

## A game boots to a blank or frozen screen

Run the oracle-free smoke check:

```bash
./gradlew bootcheck -Prom=path/to/game.nes -Pframes=120
```

Interpret `BOOTCHECK VERDICT: PASS|WARN|FAIL` together with the loaded/rendered/non-blank/NMI/IRQ lines. A `FAIL` is actionable even when the regular unit suite is green.

For a Mesen2 comparison, configure `MESEN2_PATH`, verify the environment, and run:

```bash
./gradlew verifyTestEnv
./gradlew testMesenComparison
```

When the first divergent frame matters, use:

```bash
./gradlew diverge -Prom=path/to/game.nes -Pframe=120
```

The divergence classifier is intended to localise the subsystem before changing CPU, PPU, APU, or mapper code. Read [`TESTING_STRATEGY.md`](TESTING_STRATEGY.md) before turning the failure into a regression test.

## A replay bug cannot be reproduced

The headless replay command requires the exact ROM dump that the FM2 names and hashes:

```bash
java -jar build/libs/nestlin-all.jar replay path/to/game.nes path/to/bug.fm2
```

Exit code `2` normally means usage error or a ROM/movie checksum mismatch. Exit code `3` means the emulator threw during replay. A hang can still exit `0`: compare `state=` and `frame=` hashes at multiple `--frame` values. If the frame hash freezes while the state hash advances, investigate rendering; if both freeze, investigate CPU/interrupt progress.

## No audio or bad audio

- Confirm that `--no-audio` was not supplied.
- Check the host output device and mixer before changing emulator code.
- Re-run with a clean launch and note whether the issue is silence, underrun, pitch, channel mixing, or region-specific timing.
- For an implementation change, add a focused APU test and run `./gradlew test`.

## Input or gamepad problems

Delete or edit `~/.config/nestlin/input.json` to regenerate the defaults. Confirm keyboard input first, then connect one gamepad and inspect its JInput mapping. A stuck key after focus changes should be reported with the host OS, controller model, and exact focus/release sequence.

## Tests are unexpectedly skipped

The default `test` task intentionally excludes Mesen2, external-ROM, and native-RA lanes. Run:

```bash
./gradlew verifyTestEnv
./gradlew testMesenComparison
./gradlew testNativeRa
```

If Gradle appears to use old environment variables, run `./gradlew --stop` and retry. Set `NESTLIN_REQUIRE_MESEN2` when a comparison runner must fail instead of skip.

## Where to report a new problem

Search existing issues first. Use the compatibility template for a game-specific failure, the bug template for a general regression, and include logs or hashes rather than ROM files. Security or credential exposure must follow [SECURITY.md](../.github/SECURITY.md), not a public issue.
