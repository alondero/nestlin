---
name: mesen
description: Use Mesen as a reference emulator for headless screenshot comparison, Lua-based test automation, and emulator validation. Invoke whenever the user mentions Mesen, needs headless NES emulation, wants to compare emulator output pixel-by-pixel, run test scripts against a ROM, or capture screenshots at specific frames.
---

# Mesen Skill

Mesen is a cycle-accurate NES/Famicom emulator with Lua scripting for test automation.

## Installation

- **Mesen v0.9.9**: `tools/Mesen/Mesen.exe` (headless mode works)
- **Mesen2 v2.1.1**: `tools/Mesen2/Mesen.exe` (requires display for screenshots)

Override paths with `MESEN_PATH` env var or `mesen.path` system property.

## Headless Mode (Mesen v0.9.9 only)

Use `--testrunner` mode:

```bash
Mesen.exe --testrunner <script.lua> <rom>
```

### API Reference

**`emu.getState()`** - Returns full emulator state (USE THIS):
```lua
local state = emu.getState()
-- CPU: state.cpu.pc, .a, .x, .y, .sp, .status, .cycleCount, .nmiFlag, .irqFlag
-- PPU: state.ppu.scanline, .cycle, .frameCount, .control, .mask, .status
-- Cart: state.cart.selectedChrPages[0-7], .selectedPrgPages[0-3], .prgRomSize, etc.
-- APU: state.apu.frameCounter, .noise, .dmc, .square1, .square2, .triangle
```

**`emu.takeScreenshot()`** - Works headless, returns PNG data:
```lua
local data = emu.takeScreenshot()
local f = io.open("C:/tmp/screenshot.png", "wb")
if f then f:write(data); f:close() end
```

**`emu.stop()`** - Clean exit from callback (works headless).

### Working Lua Script Pattern
```lua
local targetFrame = 60
local frame = 0

function onEndFrame()
    frame = frame + 1
    if frame >= targetFrame then
        local state = emu.getState()
        -- Use state.cpu.*, state.ppu.*, state.cart.*
        local data = emu.takeScreenshot()
        -- Save to file...
        emu.stop()  -- OR os.exit()
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
emu.resume()
```

### Critical Rules
- Register callbacks BEFORE calling `emu.resume()`
- Use absolute paths for file output (e.g., `C:/tmp/output.png`)
- **`emu.read()` does NOT work headless** - causes deadlocks or callback re-entry. Use `emu.getState()` instead.

## GUI Mode (Mesen2 or when display is available)

```bash
Mesen.exe --doNotSaveSettings <script.lua> <rom>
```

Requires display attached. Screenshot path: `emu.getScriptDataFolder() .. "screenshot.png"`

### `emu.read(addr, memType)` in Mesen2 GUI mode

Works reliably. Useful for byte-level Nestlin↔Mesen2 comparison without
plumbing state diffs. Examples used in this codebase:

```lua
local y    = emu.read(i * 4,     emu.memType.nesSpriteRam)  -- OAM
local tile = emu.read(addr,      emu.memType.nesPpuMemory)  -- pattern/nametable
```

Useful NES memTypes (full list emitted by `for k,_ in pairs(emu.memType)`):
`nesMemory`, `nesInternalRam`, `nesWorkRam`, `nesSaveRam`, `nesSpriteRam`,
`nesSecondarySpriteRam`, `nesPaletteRam`, `nesNametableRam`,
`nesPpuMemory`, `nesChrRom`, `nesChrRam`, `nesPrgRom`, `nesMapperRam`,
`nesDebug`, `nesPpuDebug`.

### Embedding Lua in Kotlin triple-quoted strings — escape `$`

Lua uses `$` only inside `string.format` format strings (e.g. `"$%02X"`).
In a Kotlin `"""…"""` literal those `$` characters start interpolation.
Use `${'$'}` for a literal dollar sign — **never** `\$`, which Lua rejects
with `invalid escape sequence near '\$'` and which Kotlin doesn't unescape
either:

```kotlin
val lua = """
local s = string.format("tile=${'$'}%02X", t)
"""
```

## Key Differences: Mesen v1 vs v2

| Feature | Mesen v1 (headless) | Mesen2 |
|---------|---------------------|--------|
| `emu.stop()` | ✅ Works | ❌ Hangs |
| `os.exit()` | ✅ Works | ✅ Works |
| `emu.getState()` | ✅ Works | ✅ Works |
| `emu.read()` | ❌ Deadlocks | ✅ Works in GUI mode (verified 2026-05-18) |
| `emu.takeScreenshot()` | ✅ Works | ✅ Works |
