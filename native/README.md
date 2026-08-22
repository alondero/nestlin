# native/ — C source for the RetroAchievements façade

Vendored rcheevos v12.4.0 plus the small C façade that bridges it to
Kotlin/JNA. See `RA_INTEGRATION.md` for the full design.

## Layout

```
native/
├── CMakeLists.txt          Top-level CMake (cross-platform fallback)
├── README.md               this file
├── ra_facade/
│   ├── ra_facade.h         Flat C ABI exposed to JNA
│   └── ra_facade.c         Façade implementation
└── rcheevos/               Vendored rcheevos v12.4.0 (MIT)
    ├── LICENSE             MIT license text
    ├── NOTICE              Upstream SHA + local-modifications list
    ├── include/            Public headers
    └── src/                rcheevos implementation (32 .c files)
```

## Build

The Gradle task `:buildNative` does this automatically:

```powershell
tools/build-native-ra.ps1 -OutputDir <out> -RchDir <this>/rcheevos -FacadeDir <this>/ra_facade
```

The script exits with:

  - `0` — build succeeded, library at `$OutputDir/$libName`.
  - `2` — no supported C compiler found (treat as "build skipped").
  - `1` — compile or link failure.

The CMake file (`native/CMakeLists.txt`) is the cross-platform
fallback used by developers who prefer CMake over the PowerShell
script. The script and CMake produce bit-identical libraries.

## Distribution to end users (no compiler required)

End users on a fresh checkout do **not** need a C compiler. The
Gradle task `:fetchNativeRa` runs automatically before
`:copyNativeRa` on `./gradlew uberJar` and downloads the
matching pre-built binary from the most recent GitHub Release.

Distribution flow:

1. The CI workflow `.github/workflows/release-native-libs.yml`
   builds the library on each of the three supported platforms
   (ubuntu-latest, windows-latest, macos-latest), zips each result
   alongside its `MANIFEST.fragment.json`, and attaches the three
   zips to a GitHub Release tagged `vX.Y.Z`.
2. End users building from a checkout run `./gradlew uberJar`.
   `:fetchNativeRa` hits
   `https://api.github.com/repos/alondero/nestlin/releases/latest`,
   finds the asset whose name is `rcheevos_facade-<platformId>.zip`
   (where `<platformId>` is one of `windows-x86_64`,
   `linux-x86_64`, `macos-universal`), and extracts it into
   `build/native-ra/<host>/`.
3. `:writeNativeRaManifest` then merges the fragment into the
   single `MANIFEST.json` that ships in the JAR. The runtime
   `RaManifest.loadForCurrentPlatform()` validates the SHA-256
   against the manifest's pinned value (issue #273 AC #2).

Override the release pin with `NESTLIN_RA_RELEASE_TAG=v1.2.3`.
Override the repo (for forks / private mirrors) with
`NESTLIN_RA_REPO=owner/repo`. Both default to the standard
`alondero/nestlin` release.

### Compatibility windows

The pre-built Linux `.so` is built on `ubuntu-latest` (Ubuntu 22.04
at the time of writing, glibc 2.35). Anyone on a Linux distribution
with **glibc < 2.35** (CentOS 7, Debian 10, RHEL 7, etc.) will see a
`GLIBC_2.XX not found` error at load time and the JAR will fall
back to `NoOpRetroAchievementsService` (the Status menu reads
"native library unavailable").

The pre-built Windows `.dll` is built with **UCRT** (Universal C
Runtime, the default for MinGW-w64 7.0+). It works on Windows 10
1709 (Fall Creators Update) and later, where UCRT ships with the
OS. Earlier Windows versions would need the user to install the
Visual C++ Redistributable manually.

The pre-built macOS `.dylib` is a **universal binary** (x86_64 +
arm64), built with the Apple Clang that ships with the GitHub
Actions `macos-latest` runner (currently Xcode 15 CLT). It works on
macOS 12 (Monterey) and later.

ARM64-only Linux (Raspberry Pi OS, Asahi Fedora) is **not**
currently distributed as a pre-built artifact. ARM64 users on Linux
must either run `:buildNative` locally with a cross-compiler or
accept the NoOp fallback.

### When the pre-built path fails

The Gradle build does **not** fail when `:fetchNativeRa` can't reach
GitHub or the release doesn't have the matching asset — it logs a
warning and `:copyNativeRa` skips with the existing "JNA will fall
back to NoOp" message. The resulting JAR is still runnable; only the
RA integration is degraded.

## Vendored upstream

`rcheevos/` is an unmodified snapshot of rcheevos v12.4.0 (commit
`2ad0b8672f68a48148620164510b963039e49eb1`), MIT-licensed. See
`rcheevos/NOTICE` for the upstream SHA, the four files removed before
vendoring (libretro / RAIntegration / .natvis / module.modulemap), and
the rationale for each removal.

To upgrade to a newer rcheevos release: drop a fresh checkout in
`native/rcheevos-<version>/`, re-apply the same four removals, update
`native/CMakeLists.txt` + the `RC_FACADE_VERSION_STRING` in
`ra_facade.c`, and update the SHA recorded in `rcheevos/NOTICE`. Do
**not** edit the upstream sources in place — every diff against
upstream should be visible in `NOTICE`.

## License

The façade code (`ra_facade.h`, `ra_facade.c`, this README) is part of
Nestlin (see top-level LICENSE). rcheevos is MIT-licensed by
RetroAchievements.org — see `rcheevos/LICENSE` for the full text and
`rcheevos/NOTICE` for the attribution block.
