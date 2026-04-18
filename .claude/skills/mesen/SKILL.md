---
name: mesen
description: Use when working with Mesen emulator - scripting API, Lua automation, headless testing, debugger operations, or when the user mentions Mesen scripts or the Mesen API.
---

# Mesen Skill

Mesen is a NES emulator with a powerful Lua scripting API for automation, debugging, and testing. This skill covers the Mesen scripting API, test runner mode, and debugger integration.

## Quick Start

### Running a Lua Script
1. Open Mesen and load a ROM
2. Open the Script Window (Tools → Script Window or Ctrl+G)
3. Load your `.lua` script or create one in the editor
4. Press F5 to start, Escape to stop

### Headless Test Runner Mode
```bash
Mesen.exe --testrunner MyGame.nes MyTest.lua
```
- Runs at maximum speed until `emu.stop()` is called
- Exit code from `emu.stop(exitCode)` validates test pass/fail

### Key API Objects
- `emu.*` - Core emulation functions (getState, read, write, log, etc.)
- `emu.eventType.*` - Event types for callbacks
- `emu.memType.*` - Memory types for read/write operations
- `emu.memCallbackType.*` - Memory callback types

## Emulation Control

### State Access
```lua
-- Get full emulation state
local state = emu.getState()
print(state.cpu.pc)        -- Program counter
print(state.cpu.a)         -- Accumulator
print(state.ppu.scanline)  -- Current scanline
print(state.ppu.cycle)     -- Current cycle

-- Set state (partial update supported)
emu.setState({ cpu = { pc = 0x8000 } })
```

### Execution Control
```lua
emu.reset()                 -- Soft reset
emu.stop(exitCode)          -- Stop emulation (test runner mode)
emu.resume()                -- Resume after break
emu.breakExecution()        -- Break into debugger
emu.execute(1000, emu.executeCountType.cpuCycles)  -- Run for N cycles
```

### Frame Events
```lua
emu.addEventCallback(function()
    -- Runs at start of every frame (cycle 0, scanline -1)
    emu.log("Frame started")
end, emu.eventType.startFrame)

emu.addEventCallback(function()
    -- Runs at end of every frame (cycle 0, scanline 240)
end, emu.eventType.endFrame)
```

## Memory Access

### Reading Memory
```lua
-- Read from various memory types
local value = emu.read(0x2002, emu.memType.ppu)     -- PPU register
local cpuVal = emu.read(0x4016, emu.memType.cpu)     -- CPU memory
local palette = emu.read(0x3F00, emu.memType.palette) -- Palette RAM
local prgByte = emu.read(0x8000, emu.memType.prgRom) -- PRG ROM

-- Signed reads
local signed = emu.read(0x2002, emu.memType.ppu, true)

-- Read 16-bit word
local word = emu.readWord(0xFFFC, emu.memType.cpu)
```

### Writing Memory
```lua
emu.write(0x2006, 0x3F, emu.memType.ppu)   -- PPU register
emu.write(0x4014, 0xFF, emu.memType.cpu)    -- Sprite DMA
emu.write(0x8000, 0x42, emu.memType.prgRom)  -- Write to PRG ROM

-- Write 16-bit word
emu.writeWord(0xFFFC, 0x1234, emu.memType.cpu)

-- Revert ROM changes
emu.revertPrgChrChanges()
```

### Memory Addresses
```lua
-- Get ROM offsets for current mapper config
local offset = emu.getPrgRomOffset(0x8000)  -- Returns byte offset in PRG ROM
local chrOffset = emu.getChrRomOffset(0x1000)  -- Returns byte offset in CHR ROM

-- Get label address (from debugger symbols)
local addr = emu.getLabelAddress("nmi_handler")
```

### Memory Callbacks
```lua
-- Watch memory reads/writes
emu.addMemoryCallback(function(address, value)
    if address == 0x4016 then
        emu.log(string.format("Joypad read: $%02X", value))
    end
end, emu.memCallbackType.cpuRead, 0x4016, 0x4017)

emu.addMemoryCallback(function(address, value)
    emu.log(string.format("PPU write to $%04X: $%02X", address, value))
end, emu.memCallbackType.ppuWrite, 0, 0x1FFF)

-- Remove callbacks
emu.removeMemoryCallback(ref, type, startAddress, endAddress)
```

## Input Handling

### Reading Input
```lua
local input = emu.getInput(0)  -- Port 0-3
-- Returns: { a, b, select, start, up, down, left, right }

if input.start then
    emu.log("Start pressed")
end
```

### Setting Input (Automated Testing)
```lua
emu.addEventCallback(function()
    -- Set input every frame (call in inputPolled event for best results)
    emu.setInput(0, { start = true })  -- Hold start for 1 frame
end, emu.eventType.inputPolled)

-- Partial input - only affect specific buttons
emu.setInput(0, { a = true, b = true })  -- Press A and B, keep other inputs
```

### Key Checking
```lua
if emu.isKeyPressed("F5") then
    emu.log("F5 is pressed")
end
```

## Drawing & Display

### Drawing Primitives
```lua
-- Draw pixel
emu.drawPixel(10, 10, 0xFF0000)  -- Red pixel at (10, 10)

-- Draw line
emu.drawLine(0, 0, 100, 100, 0x00FF00)  -- Green diagonal

-- Draw rectangle
emu.drawRectangle(10, 10, 50, 30, 0x0000FF, false)  -- Outline only
emu.drawRectangle(10, 10, 50, 30, 0x0000FF, true)   -- Filled

-- Draw text
emu.drawString(10, 10, "Hello NES!", 0xFFFFFF, 0x000000)

-- Clear all drawings
emu.clearScreen()
```

### Color Format
Colors are ARGB integers:
- `0xFFFFFF` = White
- `0x000000` = Black
- `0xFF0000` = Red
- `0x00FF00` = Green
- `0x0000FF` = Blue
- Alpha: `0x7F000000` = semi-transparent

### Duration Parameter
```lua
-- Default: 1 frame
emu.drawPixel(10, 10, 0xFF0000)  -- Shows for 1 frame

-- Permanent (until clearScreen)
emu.drawPixel(10, 10, 0xFF0000, 0)

-- Delayed drawing
emu.drawPixel(10, 10, 0xFF0000, 1, 60)  -- After 60 frames
```

### Screen Buffer
```lua
-- Get entire screen
local buffer = emu.getScreenBuffer()  -- Array of 256x240 ARGB values

-- Replace current frame
emu.setScreenBuffer(buffer)

-- Get single pixel
local color = emu.getPixel(128, 120)
```

## Save States

### Synchronous (from startFrame/cpuExec callbacks)
```lua
emu.addEventCallback(function()
    -- Save state (must be in startFrame or cpuExec callback)
    local state = emu.saveSavestate()

    -- Later, restore it
    emu.loadSavestate(state)
end, emu.eventType.startFrame)
```

### Asynchronous (anywhere)
```lua
-- Save to slot
emu.saveSavestateAsync(0)  -- Save to slot 0

-- Later, load from slot
emu.loadSavestateAsync(0)

-- Get saved data
local data = emu.getSavestateData(0)

-- Clear slot
emu.clearSavestateData(0)
```

## Logging & Messages
```lua
emu.log("Debug message")           -- Output to script log window
emu.displayMessage("Test", "Passed!")  -- Show on screen

-- Get log window contents
local log = emu.getLogWindowLog()
```

## Event Types Reference
```lua
emu.eventType.reset         -- Soft reset triggered
emu.eventType.nmi           -- NMI occurred
emu.eventType.irq           -- IRQ occurred
emu.eventType.startFrame    -- Frame starting
emu.eventType.endFrame      -- Frame ending
emu.eventType.codeBreak     -- Breakpoint hit
emu.eventType.stateLoaded   -- Savestate loaded
emu.eventType.stateSaved     -- Savestate saved
emu.eventType.inputPolled   -- Input polled for next frame
emu.eventType.spriteZeroHit -- Sprite 0 hit detected
emu.eventType.scriptEnded   -- Script stopped
```

## Memory Types Reference
```lua
emu.memType.cpu        -- $0000-$FFFF, may cause side effects!
emu.memType.ppu        -- $0000-$3FFF, may cause side effects!
emu.memType.palette    -- $00-$3F
emu.memType.oam        -- $00-$FF (sprite RAM)
emu.memType.secondaryOam  -- $00-$1F (sprite evaluation)
emu.memType.prgRom     -- PRG ROM (writable)
emu.memType.chrRom     -- CHR ROM (writable)
emu.memType.chrRam     -- CHR RAM
emu.memType.workRam    -- Work RAM
emu.memType.saveRam    -- Save RAM
emu.memType.cpuDebug   -- CPU memory, no side effects
emu.memType.ppuDebug   -- PPU memory, no side effects
```

## ROM Info
```lua
local info = emu.getRomInfo()
-- info.name, info.path, info.fileCrc32Hash, info.format, info.isChrRam, etc.
```

## Script Data Folder
```lua
-- Get path for saving script-specific data
local folder = emu.getScriptDataFolder()
-- Returns: LuaScriptData/<scriptname>/
```

## Screenshot
```lua
-- Take screenshot, returns PNG as binary string
local png = emu.takeScreenshot()
```

## Cheats
```lua
emu.addCheat("SXEENPA")  -- Game Genie code
emu.clearCheats()
```

## Access Counters
```lua
-- Track memory access patterns
local counters = emu.getAccessCounters(emu.counterMemType.nesRam, emu.counterOpType.read)
-- Returns array of access counts per address

emu.resetAccessCounters()
```

## Common Patterns

### Waiting for a Condition
```lua
emu.addEventCallback(function()
    local state = emu.getState()
    if state.cpu.pc == 0x8000 then
        emu.log("Reached target address!")
        emu.stop(0)  -- Exit with success code
    end
end, emu.eventType.startFrame)
```

### Frame-Perfect Input
```lua
emu.addEventCallback(function()
    -- Set input at start of each frame
    emu.setInput(0, { a = true })
end, emu.eventType.inputPolled)
```

### Memory Watch with Side-Effect Bypass
```lua
-- Use cpuDebug/ppuDebug to avoid side effects
emu.addMemoryCallback(function(address, value)
    emu.log(string.format("Read $%04X = $%02X", address, value))
end, emu.memCallbackType.cpuRead, 0, 0xFFFF, emu.memType.cpuDebug)
```

## File Paths in Nestlin Project

Mesen is located at: `tools/Mesen` (configured in CLAUDE.local.md)

Test runner usage example:
```bash
tools/Mesen/Mesen.exe --testrunner testroms/nestest.nes TestScript.lua
```

## Additional Resources

- For complete API details, see [docs-apireference.md](docs-apireference.md)
- For debugger usage, see [docs-debugging-debugger.md](docs-debugging-debugger.md)
- For configuration options, see [docs-configuration.md](docs-configuration.md)