---
name: mesen
description: Use Mesen as a reference emulator for headless screenshot comparison, Lua-based test automation, mapper-register tracing, A12 IRQ debugging, and any byte-level comparison with Nestlin. Invoke whenever the user mentions Mesen, needs headless NES emulation, wants to compare emulator output pixel-by-pixel, dump mapper state, trace PPU/CPU memory accesses, or capture state at specific frames.
---

# Mesen Skill

Mesen is a cycle-accurate NES/Famicom emulator. Two distinct binaries ship on this machine, with very different capabilities. **Choose the right one or scripts will silently misbehave.**

## Which binary, when

| Capability | Mesen v1 (`tools/Mesen/Mesen.exe`) | Mesen v2 (`tools/Mesen2/Mesen.exe`) |
|---|---|---|
| Headless (`--testRunner`) | yes — true headless | **yes** (verified 2026-06-08 on v2.1.1, incl. `emu.read` and memory callbacks) |
| `emu.getState()` | yes | yes — returns a **FLAT dotted-key table** (`s["cpu.pc"]`), not nested |
| `emu.takeScreenshot()` | yes | yes |
| `emu.read()` | no — deadlocks in headless | yes — GUI **and** `--testRunner` |
| `emu.addMemoryCallback()` | limited | yes — via **`emu.callbackType`** (see below) |
| `emu.execute(count, type)` | yes | yes |
| `emu.stop(code)` | yes | **yes** — exits cleanly with the code (earlier "use os.exit()" advice obsolete) |
| Mapper introspection via `nesMapperRam` memType | partial | yes |

**Rule of thumb:** use **v2 `--testRunner --doNotSaveSettings`** for everything scripted — it is headless-capable on this machine. Before writing fresh Lua, check `tools/mesen-trace/` — write-watch, interrupt-counter, ppuctrl-transitions, and chr-dump are already there, verified against the installed binary.

## Absolute paths (no relative path discipline survives worktrees)

All runner plumbing is consolidated in `Mesen2Process.kt`. Defaults: `MESEN2_PATH` env → `mesen2.path` system property → `X:\src\nestlin\tools\Mesen2\Mesen.exe`.

ROM paths passed to runners must be absolute. `ProcessBuilder.directory(mesenDir)` makes the cwd the Mesen install folder, so `testroms/foo.nes` would resolve to `tools/Mesen2/testroms/foo.nes` and silently fail to load. Symptom: capture wall-time >10s (v2 GUI normally captures Kirby at ~75 fps, so 150 frames = ~2s). `Mesen2Process.runScript` already calls `.toAbsolutePath()` on script + rom — callers passing paths in must still hand absolute paths to `runScript`.

In generated Lua, `emu.getScriptDataFolder()` returns no trailing separator on Windows. Always append `\\` if missing before concatenating filenames, or files land outside the script's data folder with names like `capturescreenshot.png`.

Worktree caveat: `tools/` and `testroms/` are gitignored. A fresh worktree has neither. The runners' absolute defaults let any worktree reach the parent's copies; ROM paths in tests must also use absolute paths (e.g. `Paths.get("X:/src/nestlin/testroms/kirby.nes").toAbsolutePath()`).

## GUI-mode invocation

```
Mesen.exe --doNotSaveSettings <absolute-script.lua> <absolute-rom.nes>
```

I/O access must be enabled once interactively: Script → Settings → Script Window → Restrictions → Allow access to I/O and OS functions. Persisted in `settings.json`; `--doNotSaveSettings` keeps test runs from polluting it.

## Headless v1 invocation

```
Mesen.exe --testrunner <script.lua> <rom>
```

`emu.stop(exitCode)` exits with the given code; `emu.read()` will deadlock.

## API surface (full, organized by purpose)

### Frame and state inspection

| Call | Use |
|---|---|
| `emu.getState()` | **v2.1.1 reality:** a FLAT table with dotted string keys — `s["cpu.pc"]`, `s["ppu.scanline"]`, `s["ppu.frameCount"]`, `s["cpu.cycleCount"]`, etc. (`state.cpu.pc` nested access throws nil-index.) **Inside event/memory callbacks** the table is PARTIAL: no `ppu.*`, no `cart.*` keys — an unguarded access silently aborts the rest of the callback. Count frames yourself via an `endFrame` callback instead of reading `ppu.frameCount` there. |
| `emu.setState(state)` | Writes CPU/PPU back (NOT APU or cart). Useful for forcing a known state to repro. |
| `emu.takeScreenshot()` | Returns raw PNG bytes — `io.open(path, "wb"); f:write(data); f:close()`. |
| `emu.getRomInfo()` | Mapper number, PRG/CHR sizes, mirroring — surface what the ROM declares. |

### Memory tracing — KEY for mapper work

| Call | Use |
|---|---|
| `emu.read(addr, memType, signed=false)` | One byte from any memory space. **v2 GUI only.** |
| `emu.readWord(addr, memType, signed=false)` | Two bytes. |
| `emu.write(addr, value, memType)` | Inject a value. Writes to `prgRom`/`chrRom` are reversible via `emu.revertPrgChrChanges()`. |
| `emu.addMemoryCallback(fn, type, startAddr [, endAddr])` | Fire on every access in a range. **v2.1.1: `type` comes from `emu.callbackType`, whose ONLY keys are `exec`, `read`, `write`** — `emu.memCallbackType` is nil and there is no `cpuWrite`/`ppuRead` variant. Watch mapper-register writes with `emu.addMemoryCallback(fn, emu.callbackType.write, 0x8000, 0xFFFF)`; the callback receives `(address, value)`. Do NOT call `emu.getState()` inside it for ppu/cart fields (they're absent there). Returning a number from the callback rewrites the operation's value. |
| `emu.removeMemoryCallback(ref, type, ...)` | Unregister. |
| `emu.getPrgRomOffset(cpuAddr)` | Maps a CPU address to a byte offset in the PRG ROM file — invaluable for reasoning about bank switching. |
| `emu.getChrRomOffset(ppuAddr)` | Maps a PPU address to CHR ROM file offset. |
| `emu.getAccessCounters(...)` | Read/write/exec counts for instrumentation. |

### Timing and execution control

| Call | Use |
|---|---|
| `emu.execute(count, type)` | Step exactly N `cpuCycles` / `ppuCycles` / `cpuInstructions`. Pairs with `emu.breakExecution()` for ratchet-style tests. |
| `emu.breakExecution()` | Stop, open debugger window (GUI). |
| `emu.resume()` | Continue after `breakExecution()`. |
| `emu.reset()` | Soft reset. |
| `emu.rewind(seconds)` | Only valid from `startFrame` callback. |
| `emu.stop(exitCode)` | Works on BOTH v1 and v2.1.1 — exits `--testRunner` with the given code. (Old "v1 only, use os.exit()" advice was wrong for the installed v2.) |

### Event hooks

`emu.addEventCallback(fn, eventType)` — v2.1.1 keys (dumped at runtime): `codeBreak, endFrame, inputPolled, irq, nmi, reset, scriptEnded, startFrame, stateLoaded, stateSaved`.

`emu.eventType.irq` **fires for mapper IRQs** and is reliable for counting fires per frame — increment a global, attribute to a frame via your own `endFrame` counter. This is the instrument that proved Mesen fires the RAMBO-1 IRQ once/frame vs Nestlin's twice. The callback receives no args; do NOT rely on `emu.getState()` ppu/cart fields inside it (absent — see above).

### Drawing overlays (debugging visualisation)

`emu.drawPixel(x, y, color, duration=1, delay=0)`, `emu.drawLine`, `emu.drawRectangle(..., fill, ...)`, `emu.drawString(x, y, text, fg, bg, duration, delay)`, `emu.clearScreen()`. Colors are 32-bit ARGB. Useful for highlighting which sprite the test thinks is offending.

### Logging

`emu.log("text")` → script log window. `emu.displayMessage("category", "text")` → main window OSD.

### Filesystem

`emu.getScriptDataFolder()` → `<MesenDir>/LuaScriptData/<scriptBaseName>/` (Mesen v2; v1 differs). **No trailing separator** — append one before concatenating filenames.

### memType enum — **discover at runtime**

The published docs at mesen.ca list bare names (`cpu, ppu, palette, oam, prgRom, chrRom, chrRam, workRam, saveRam, cpuDebug, ppuDebug`), but the installed Mesen v2.1.1 uses `nes`-prefixed names (`nesMemory, nesInternalRam, nesWorkRam, nesSaveRam, nesSpriteRam, nesSecondarySpriteRam, nesPaletteRam, nesNametableRam, nesPpuMemory, nesChrRom, nesChrRam, nesPrgRom, nesMapperRam, nesDebug, nesPpuDebug`). **Always discover at script start:**

```lua
local memTypes = {}
for k, v in pairs(emu.memType) do memTypes[k] = v end
-- now pick: memTypes.nesSpriteRam or memTypes.spriteRam or memTypes.oam
local function pick(...) for _,n in ipairs({...}) do if memTypes[n] then return memTypes[n] end end end
local oam = pick("nesSpriteRam", "spriteRam", "oam")
```

## Mapper-inspection recipe

For obscure mappers, the question is usually "which $8000-region writes is the ROM making, and when?" — **do not write this from scratch: use `tools/mesen-trace/write-watch.lua`** (edit its CONFIG block). The companions cover the other recurring questions: `interrupt-counter.lua` (NMI/IRQ fires per frame — the instrument that cracked the Gimmick hang and the Klax double-IRQ), `ppuctrl-transitions.lua` (did rendering/NMI ever get enabled?), `chr-dump.lua` (which CHR bank is mapped at frame N?). All verified against the installed v2.1.1; see `tools/mesen-trace/README.md`.

Key shape they all share (this is what a correct v2.1.1 trace looks like):

```lua
-- frame attribution: own counter, NOT getState() inside the callback
local frame = 0
local f = assert(io.open(emu.getScriptDataFolder() .. "/out.txt", "w"))

local function onWrite(address, value)
    f:write(string.format("frame=%d addr=%04X val=%02X\n", frame, address, value))
    f:flush()  -- crash mid-run still leaves usable data
end

local function onEndFrame()
    frame = frame + 1
    if frame >= 600 then f:close(); emu.stop(0) end
end

emu.addMemoryCallback(onWrite, emu.callbackType.write, 0x8000, 0xFFFF)
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
```

For PPU-side mapper triggers (MMC3 A12 IRQ), use `emu.callbackType.read` over `$0000..$1FFF` and post-process for rising A12 edges (low→high in address bit 12).

For PRG ROM offset tracking (which physical bank is mapped at the moment of the write), include `emu.getPrgRomOffset(address)` in the log line.

## Embedding Lua in Kotlin triple-quoted strings — `$` escape

In Kotlin `"""…"""`, `$` starts interpolation. Use `${'$'}` for a literal `$`; **never** `\$` (Lua rejects it, Kotlin doesn't unescape it).

```kotlin
val lua = """
local s = string.format("tile=${'$'}%02X", t)
"""
```

## Existing touchpoints in this codebase

| File | What it does |
|---|---|
| `src/test/kotlin/.../compare/Mesen2Process.kt` | Shared driver: path resolution, ProcessBuilder invocation, temp script dir lifecycle. New Mesen2 runners should call `Mesen2Process.runScript(lua, romPath, scriptName)`. |
| `src/test/kotlin/.../compare/Mesen2ReferenceRunner.kt` | Screenshot capture via v2 GUI |
| `src/test/kotlin/.../compare/Mesen2OamDumpRunner.kt` | Multi-frame OAM dump via Lua + `emu.read(spriteRam)` |
| `src/test/kotlin/.../compare/Mesen2StateCapturer.kt` | Single-frame CPU/PPU state via `getState()` |
| `src/test/kotlin/.../compare/ScreenshotComparisonTest.kt` | Parameterised pixel-diff harness |
| `src/test/kotlin/.../compare/KirbyMesenVsNestlinOamTest.kt` | Driver for OAM dump comparison |
| `src/test/kotlin/.../compare/DebugMesen2CaptureTest.kt` | Manual state-capture smoke test |

## Known gotchas

- **Gradle daemon env var inheritance**: `MESEN2_PATH` set in PowerShell may not reach the test JVM. Restart the daemon (`./gradlew --stop`) or forward explicitly via `build.gradle.kts`.
- **`assumeTrue` swallows real errors**: `ScreenshotComparisonTest` catches `Mesen2ExecutionException`/`Mesen2ScreenshotException` and calls `assumeTrue(false)`, hiding the real cause as SKIPPED. When debugging, temporarily convert to `fail()` to see the message.

## See also

- [NESdev Wiki](https://www.nesdev.org/wiki/) — NES hardware reference
- Mesen Lua API at https://www.mesen.ca/docs/apireference.html (current upstream; may differ slightly from installed v2.1.1)
- Memory entries: `mesen2-capture-harness-gotchas-2026-05-19`, `mesen2-state-capture-investigation`, `nestlin-screenshot-harness`
