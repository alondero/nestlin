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
