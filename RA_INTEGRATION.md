# RetroAchievements Integration

Nestlin can talk to retroachievements.org to surface achievement progress
and rich presence for the games you load. The integration is **opt-in and
optional**: every existing emulator flow works with no native library and
no network access, and a graceful NoOp fallback kicks in whenever the
native façade is absent, corrupt, or incompatible.

Issue #267 ships the native capability and the menu's availability
indicator. Login, token restoration, profile UI, and per-game achievement
sets land in issue #268. The HTTP transport and any per-rom set
caching land in issue #269.

## What this integration looks like from the user's perspective

  - **Emulation menu → RetroAchievements → Status**: shows
    `native library available (rcheevos 12.4.0)` when the façade is on
    the classpath, or `native library unavailable` (with a tooltip
    explaining how to enable it) when it isn't.
  - **The first frame after `prepareGame` returns false**: the
    coordinator falls back to the NoOp service. Gameplay proceeds
    normally — there is no UI pop-up, no error toast, no console
    spam. The status menu reads "native library unavailable" until
    issue #268 adds the "Sign in…" entry that turns the service live.

## What this integration looks like from the developer's perspective

The integration has three layers, each isolated from the next:

```
┌──────────────────────────────────────────────────────────────┐
│ Application.kt (JavaFX UI)                                  │
│   ├─ uses RetroAchievementsServiceFactory.create()          │
│   └─ RetroAchievements menu shows the façade's availability  │
└──────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────┐
│ session/RetroAchievementsServiceFactory (Kotlin)             │
│   ├─ try NativeRetroAchievementsService.load()              │
│   └─ fall back to NoOpRetroAchievementsService on failure   │
└──────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────┐
│ session/NativeRetroAchievementsService (Kotlin/JNA)         │
│   ├─ owns one rc_client_t* (via ra_facade_create)           │
│   ├─ delegates to the ra_facade_* ABI                       │
│   └─ returns false on any native failure (never throws)     │
└──────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────┐
│ native/ra_facade/{ra_facade.h,ra_facade.c} (C)               │
│   ├─ owns every rc_client_t* and rc_api_request_t*          │
│   ├─ forces softcore mode after rc_client_create            │
│   ├─ copies every native string/buffer into flat ABI arrays │
│   └─ never calls back into JVM code (no GVL / GC races)      │
└──────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼────────────────────────────────┐
│ native/rcheevos/ (vendored v12.4.0)                          │
│   └─ 32 .c files, MIT-licensed upstream                       │
└──────────────────────────────────────────────────────────────┘
```

The narrowest contract is `RetroAchievementsService` (the interface
introduced in #266). Everything below that interface is implementation
detail — a future issue could swap the C façade for a pure-Kotlin
client without touching the rest of Nestlin.

## Building the native library

### Prerequisites

A C compiler with C99 support. The build script discovers the compiler
on PATH; on Windows it also probes `C:\ProgramData\mingw64\mingw64\bin\gcc.exe`.

| OS       | Tested compiler | Other options                |
|----------|-----------------|------------------------------|
| Windows  | MinGW-w64 GCC 15 | clang-cl (with MSVC SDK)   |
| Linux    | GCC 10+         | clang 11+                    |
| macOS    | Apple Clang 14+ | Homebrew GCC 11+             |

No network access is required at build time. The HTTP layer is stubbed
off in the façade (see "Softcore / no-network" below).

### Build commands

```
./gradlew buildNative        # compile rcheevos + façade → native/build/<host>/
./gradlew testNativeRa       # compile + run the @Tag("nativeRa") contract tests
./gradlew build               # standard build (does NOT trigger buildNative)
```

The default `./gradlew build` does NOT compile the native library —
running it on a machine without GCC never fails. The native lib is
opt-in: developers working on the JNA layer run `:buildNative` explicitly,
and the runnable JAR they produce carries the library via the
`copyNativeRa` task.

### Output location

After `:buildNative`, the shared library is at:

  - Windows:  `build/native-ra/windows/rcheevos_facade.dll`
  - macOS:    `build/native-ra/macos/librcheevos_facade.dylib`
  - Linux:    `build/native-ra/linux/librcheevos_facade.so`

The `:copyNativeRa` task then moves it into `build/resources/main/native-ra/<host>/`
so the shadow JAR picks it up automatically.

### Distribution to end users (no compiler required)

End users on a fresh checkout do **not** need a C compiler. The
Gradle task `:fetchNativeRa` runs automatically before
`:copyNativeRa` on `./gradlew uberJar` and downloads the
matching pre-built binary from the most recent GitHub Release.

The CI workflow `.github/workflows/release-native-libs.yml` builds
the library on each of the three supported platforms
(ubuntu-latest, windows-latest, macos-latest), zips each result
alongside its `MANIFEST.fragment.json`, and attaches the three
zips to a GitHub Release tagged `vX.Y.Z`. End users get the
artifact whose name matches their platform:

  - Windows:  `rcheevos_facade-windows-x86_64.zip`
  - macOS:    `rcheevos_facade-macos-universal.zip`
  - Linux:    `rcheevos_facade-linux-x86_64.zip`

Override the release pin with `NESTLIN_RA_RELEASE_TAG=v1.2.3`.
Override the repo (for forks / private mirrors) with
`NESTLIN_RA_REPO=owner/repo`. Both default to the standard
`alondero/nestlin` release.

The pre-built Linux `.so` targets glibc 2.35 (Ubuntu 22.04 LTS);
the pre-built Windows `.dll` is built with UCRT (Windows 10 1709+);
the pre-built macOS `.dylib` is a universal binary (x86_64 + arm64).
Anyone on an older glibc or Windows version gets a graceful NoOp
fallback — the JAR still runs, the Status menu just reads "native
library unavailable".

See `native/README.md` for the full compatibility matrix and the
escape hatches (run `:buildNative` locally with a cross-compiler;
override `NESTLIN_RA_RELEASE_TAG` to pin a specific release).

### Customizing the build

Set `NESTLIN_RA_COMPILER=gcc|clang|auto` to override compiler selection.
The build script exits with code 2 when no compiler is found; Gradle
treats this as "build skipped" (a warning, not a failure) and the JNA
service falls back to NoOp.

## Softcore / no-network mode

Issue #267 requires the client to **force softcore mode immediately after
client creation**. The façade does this by calling
`rc_client_set_hardcore_enabled(client, 0)` in `ra_facade_create`. The
`ra_facade_get_game_info` result carries the effective hardcore flag
(the Kotlin-side exposes it indirectly via `isSignedIn()`, which always
returns `false` until #268 lands login).

Login is also intentionally not implemented in this slice. The façade's
`server_call` callback is a no-network shim that immediately responds
with `RC_API_SERVER_RESPONSE_CLIENT_ERROR`. As a consequence:

  - `prepareGame` returns `false` for every ROM (the request never
    completes).
  - `isSignedIn()` always returns `false`.
  - `serializeProgress()` returns `null` (no progress to serialize).
  - `evaluateFrame()` is safe to call (the runtime advances against the
    memory reader, but no achievement can ever trigger because no set
    is loaded).

When #268 ships login, the server-call shim gets replaced with a real
HTTP transport. The Kotlin-side contract doesn't change.

## Memory discipline

The issue requires that no borrowed native memory or sensitive contents
appear in logs. The design that satisfies this:

  - Every string rcheevos returns (`rc_client_user_t::display_name`,
    `rc_client_achievement_t::title`, etc.) is copied by the façade
    into a flat C array in `ra_event_t`. JNA reads the array contents
    with `bytesToString(...)` and the resulting `String` is owned by
    the JVM heap. Once the `String` is in JVM memory, no reference
    to native memory exists.
  - The `handleEvent` log paths redacted sensitive fields explicitly:
    server error messages and API paths are not logged; only the
    numeric result code and related ID.
  - The read-memory callback's userdata pointer is `Pointer.NULL` —
    there's nothing in the C side that would let a malicious ROM
    read past the façade's bounds, and there's nothing in the JVM
    side that would let a stale native pointer leak into user-facing
    strings.

## Tests

The contract tests live in two files:

  - `src/test/kotlin/com/github/alondero/nestlin/session/RetroAchievementsServiceFactoryTest.kt`
    — always runs (default `./gradlew test`). Covers the
    fallback-to-NoOp path and the JNA-binding-shape contract.
  - `src/test/kotlin/com/github/alondero/nestlin/session/RaFacadeBindingsTest.kt`
    — tagged `@Tag("nativeRa")`, skipped from the default suite, runs
    only via `./gradlew testNativeRa` (which depends on `buildNative`).

The native contract tests cover:

  - Forced softcore mode (via the documented `isSignedIn() == false` side-effect).
  - `prepareGame` returns false when not signed in.
  - `evaluateFrame` is safe before any game is loaded.
  - `serializeProgress` returns null before any game is loaded.
  - `shutdown` is idempotent (no crash on the second call's freed pointer).
  - `unloadGame` is idempotent.
  - Version strings are non-empty.
  - JNA `Structure` subclasses can be instantiated (proves field layout).

These tests deliberately **do not** make network calls. The no-network
shim in the façade means the tests are fully hermetic — they construct
the client, exercise every public method, and verify the documented
"softcore/no-network" behaviour.

## Why a custom C façade instead of JNA-direct

JNA can bind `rc_client.h` directly, but doing so would:

  - Force every Kotlin caller to handle `rc_client_t*` and
    `rc_client_event_t*` — leaking the native API across the
    `RetroAchievementsService` seam, which #266 explicitly forbids.
  - Couple the JVM-side ABI to rcheevos's struct layouts. A future
    rcheevos release that adds a field to `rc_client_achievement_t`
    would silently break the JVM struct mapping.
  - Make the "no borrowed native memory" requirement harder to
    enforce — rcheevos's API exposes `const char*` for every string,
    so callers would have to know to copy each one.

The custom façade trades a small amount of up-front glue code for a
contract that the rest of Nestlin can rely on indefinitely.

## Future work (not in this slice)

  - **Issue #268**: login with username + token, token restoration,
    profile display, "sign out" menu action.
  - **Issue #269**: real HTTP transport (replace the no-network shim
    with libcurl or a JVM-side URLConnection). Server response caching
    so the same ROM on the same machine doesn't re-download.
  - **Issue #270**: achievement set fetch + caching, achievement unlock
    submission, leaderboard submission, rich presence strings.
  - **Issue #271**: UI surfaces — toast on unlock, leaderboard tracker
    overlay, challenge indicator, progress indicator.
