# RetroAchievements — manual acceptance script (issue #273)

This document is the operator-facing checklist for validating the
RetroAchievements integration on **Windows**, **Linux**, and **macOS**
end-to-end. Each scenario has an expected outcome and the way to
verify it. The script is intentionally light on tooling — every
check uses the running application, the saved state directory, or
the build's `nra-smoke` CLI; nothing requires the developer tools.

## Scope

The integration is **softcore-only**. It is **not** a hardcore-mode
client; it does **not** submit leaderboard scores; it does **not**
display unofficial achievement sets; and it does **not** expose
achievement-set authoring tools. This is the contract from
RA_INTEGRATION.md and the C-side forced-softcore behaviour
(`rc_client_set_hardcore_enabled(client, 0)` in
`native/ra_facade/ra_facade.c`).

Any behaviour that touches the hardcore flag, leaderboards, or
unofficial sets is a regression to be filed against this integration.

## Pre-flight (every OS)

1. **JDK 21 on PATH.** `java -version` reports 21.x.
2. **Built JAR.** `./gradlew uberJar` produces
   `build/libs/nestlin-all.jar`. The JAR must include
   `native-ra/MANIFEST.json` and the per-platform shared library
   (`rcheevos_facade.dll` / `librcheevos_facade.so` /
   `librcheevos_facade.dylib`). Confirm:
   ```
   unzip -l build/libs/nestlin-all.jar | grep MANIFEST
   unzip -l build/libs/nestlin-all.jar | grep rcheevos_facade
   ```
3. **Native smoke.** `java -jar build/libs/nestlin-all.jar nra-smoke`
   prints 9 STEP lines + an OVERALL line. Every STEP must be PASS.
   (See [Native smoke](#native-smoke) below.)

## Scenarios

The scenarios below are the literal list from the issue's acceptance
criteria. Each scenario has a one-line summary and the steps to
verify it.

### S01 — Fresh signed-out launch

1. Launch with no `~/.config/nestlin` credentials:
   `java -jar build/libs/nestlin-all.jar path/to/any.nes`.
2. Open the **RetroAchievements** menu.
3. Verify the **Status** item reads
   `native library available (rcheevos 12.4.0)` or
   `native library unavailable` — depending on whether the JAR
   bundled the native lib for the host.
4. Verify **Sign In...** is enabled and **View Profile...** +
   **Sign Out** are disabled.

### S02 — Login

1. Click **RetroAchievements → Sign In...**.
2. Enter a valid RetroAchievements username + password.
3. Verify the **Status** indicator transitions through
   `Authenticating` → `Signed In`.
4. Verify **Sign In...** is disabled and **View Profile...** +
   **Sign Out** are now enabled.

### S03 — Token restoration

1. Quit Nestlin.
2. Re-launch. Verify the **Status** indicator returns to
   `Signed In` without prompting for credentials.
3. Inspect `~/.config/nestlin/ra/prefs.xml` (Linux) /
   `~/Library/Preferences/com.github.alondero.nestlin.plist`
   (macOS) /
   `HKCU\Software\JavaSoft\Prefs\com\github\alondero\nestlin\ra`
   (Windows). The stored credentials are username + a 32-char hex
   token; the password is **never** persisted.

### S04 — Recognized plain ROM

1. Load a plain `.nes` file whose hash is in the RA database
   (use `python tools/rom_info.py info path/to/rom.nes` to confirm
   the hash matches a known game).
2. Verify a **boot placard** appears for ~3 s showing the game
   badge, title, and unlocked/total counts.
3. Open **RetroAchievements → Current Game Achievements...**.
4. Verify the achievements window lists the game's core set in
   buckets (Locked / Unlocked / Almost There / Active Challenge /
   Recently Unlocked).

### S05 — Recognized archived ROM

1. Load a `.7z` archive containing a recognised NES ROM.
2. Repeat S04. The placard + achievements window must behave
   identically to the plain-ROM path.

### S06 — Unrecognised ROM

1. Load a ROM whose hash is NOT in the RA database (e.g. a
   translation patch or homebrew).
2. Verify a **subtle** boot placard appears reading
   `<displayName> not recognized on RetroAchievements (possible ROM hack / translation / alternate dump)`.
3. Open the achievements window — it must show an
   `Unrecognized` view-model with the ROM's display name.

### S07 — Boot placard

Covered by S04–S06. Verify the placard disappears after ~3 s and
the canvas returns to the normal frame.

### S08 — Unlock notification

1. In a game with an RA achievement set, trigger one achievement
   (the exact trigger depends on the game; e.g. in Super Mario Bros,
   jump over the first Goomba to earn "Going Up").
2. Verify a **gold-bordered unlock pill** appears in the top-right
   corner for ~5 s with the achievement title and points.

### S09 — Simultaneous unlocks

1. Load a save state just before several achievements are about
   to trigger (Kirby's Adventure with several level-completion
   flags works well), then resume.
2. Verify the unlock pills appear **one at a time**, each for the
   full 5 s, in trigger order. The queue is FIFO — a 3-unlock burst
   shows pill A (0–5 s), pill B (5–10 s), pill C (10–15 s).
3. The system banner (offline / server-error) does **not** displace
   an active unlock.

### S10 — Achievements window

1. Open **RetroAchievements → Current Game Achievements...**.
2. Verify the window is **non-modal** — gameplay continues in the
   background, no input is blocked.
3. Verify the bucket headers (Locked / Unlocked / Almost There /
   Active Challenge / Recently Unlocked / Unsupported / Unsynced)
   are visible and clickable. Switching buckets is instant (no
   network round-trip).

### S11 — Save / load with progress

1. Trigger one achievement (S08) so the runtime has progress to
   serialise.
2. Save state (`F5`).
3. Verify the `.nstl` file is at least a few hundred bytes larger
   than a vanilla save state (the RA progress trailer is
   length-prefixed; see SaveState v7).
4. Power-reset (`Ctrl+R`) and load the state (`F8`).
5. Verify the unlock pill **does not** replay — the achievement is
   still unlocked after the load.

### S12 — Rewind

1. Trigger an achievement.
2. Hold `Backspace` for ~2 s to rewind.
3. Verify the achievement is still unlocked after rewind (the
   rewind buffer captures RA progress per frame; rewinding restores
   the prior progress state without a fresh unlock).

### S13 — Offline startup

1. Disconnect from the network (or block
   `retroachievements.org` at the firewall).
2. Launch Nestlin.
3. Verify the Status reads `native library available` (the lib is
   bundled; the offline check is only for the network round-trips).
4. Sign-in: the Status transitions `Authenticating` → `Offline`.
5. Re-enable the network. Sign in again.

### S14 — Reconnect after a transient drop

1. Sign in (S02).
2. Disconnect the network for ~5 s.
3. Reconnect.
4. Verify the next sign-in attempt succeeds and the Status moves
   back to `Signed In` (no app restart required).

### S15 — Logout

1. Sign in (S02).
2. Click **RetroAchievements → Sign Out**.
3. Verify the credentials file is empty / removed (inspect the
   platform's storage location from S03 — both keys must be gone).
4. Verify the Status reads `Signed Out` and the Profile /
   Achievements menu items disable.

### S16 — Shutdown

1. Sign in (S02) and load a recognised ROM (S04).
2. Quit the application normally.
3. Verify the process exits within ~1 s (no JNI crash, no
   UnsatisfiedLinkError in stderr).
4. On macOS / Linux, verify the JVM does not print a
   "library was unloaded" warning — `ra_facade_destroy` ran cleanly
   in the shutdown path.

### S17 — Native fallback

1. Rename or delete the bundled native lib in the JAR (this
   simulates a corrupt / missing binary on disk):
   ```
   unzip -p build/libs/nestlin-all.jar native-ra/MANIFEST.json | head
   ```
   For a true test, run the JAR on a host without a C compiler —
   `buildNative` exits 2, the JAR ships without the lib, and the
   loader's `RaManifest.loadForCurrentPlatform()` reports
   `LIBRARY_MISSING` (or `MANIFEST_MISSING` if the manifest isn't
   bundled either).
2. Launch. Verify the Status reads `native library unavailable`.
3. Verify every other emulator feature still works — boot the
   ROM, save state, rewind, screenshot. The fallback must be
   **silent** (no crash, no error toast) except for the Status
   indicator.

## Native smoke

Run the bundled smoke runner to confirm the native path is healthy
on the current host:

```
java -jar build/libs/nestlin-all.jar nra-smoke [--rom path/to/rom.nes]
```

The runner prints nine `STEP N name: PASS|FAIL — reason` lines plus
an `OVERALL:` summary. Exit codes:

| Code | Meaning |
|------|---------|
| 0    | Every step PASS. |
| 1    | One or more steps FAIL — the printed reason names which. |
| 3    | Native library missing — `RaManifest.loadForCurrentPlatform()` reported a pre-flight failure. The runner cannot proceed; the Status menu will read "native library unavailable". |

The nine steps are:

1. **manifest** — `MANIFEST.json` is bundled; entry for the host
   OS+arch exists; bundled library size matches; SHA-256 matches.
2. **load** — JNA can `Native.load("rcheevos_facade")`.
3. **version** — the loaded library's `ra_facade_rcheevos_version()`
   and `ra_facade_version()` match the manifest's pinned strings.
4. **client-lifetime** — `ra_facade_create` returns non-null;
   `ra_facade_destroy` is idempotent.
5. **nes-hashing** — `ra_facade_hash_nes_rom` returns a 32-char
   lowercase hex digest; hashing the same ROM twice returns the
   same digest.
6. **mock-login** — `isSignedIn()` returns false after create
   (the forced-softcore contract).
7. **memory-events** — the read-memory callback is not invoked
   on the no-game path (no spurious reads).
8. **progress-serialization** — `progress_size` and
   `serialize_progress` return 0 on the no-game path.
9. **callback-teardown** — destroying a handle drains the event
   queue; the new handle's queue starts empty.

The runner never reaches the network. It is safe to run on an
air-gapped CI machine.

## Per-OS notes

### Windows

- Native lib: `rcheevos_facade.dll` at
  `native-ra/windows/rcheevos_facade.dll` inside the JAR.
- Credential storage:
  `HKCU\Software\JavaSoft\Prefs\com\github\alondero\nestlin\ra`.
- Audio device name: by default the first available output line
  (`javax.sound.sampled`).

### Linux

- Native lib: `librcheevos_facade.so` at
  `native-ra/linux/librcheevos_facade.so` inside the JAR.
- Credential storage:
  `~/.java/.userPrefs/com/github/alondero/nestlin/ra/prefs.xml`.
- Audio device: PulseAudio / ALSA via `javax.sound.sampled`.

### macOS

- Native lib: `librcheevos_facade.dylib` at
  `native-ra/macos/librcheevos_facade.dylib` inside the JAR.
  The build pipeline emits a **universal** (x86_64 + arm64) binary;
  JNA's dyld resolves the right slice for the host CPU.
- Credential storage:
  `~/Library/Preferences/com.github.alondero.nestlin.plist`.
- Audio device: CoreAudio via `javax.sound.sampled`.

## Safe diagnostics

Every failure path in the integration funnels through the
`Redactor` (`src/main/kotlin/com/github/alondero/nestlin/util/Redactor.kt`):

- URLs in stderr have their `t=`, `token=`, `password=`, `username=`,
  and other credential-bearing query parameters replaced with
  `***`.
- Free-form messages have any contiguous run of 16+ alphanumeric
  characters replaced with `***` (this catches the 32-char hex
  tokens rcheevos issues).
- No log line carries a password, token, or session cookie.
  The credential store persists only the username and the 32-char
  hex token; the password is **never** persisted.

A failed smoke step, a failed checksum, a network timeout, or a
server error message all reach stderr through this redaction path.
If you find a log line that looks like it could be a credential,
file a regression — that's a release-blocking issue.
