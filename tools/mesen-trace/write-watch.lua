-- write-watch.lua — log every CPU write in an address range, tagged with frame number.
-- The canonical "which mapper registers does this game actually touch?" instrument.
--
-- Verified against the installed Mesen2 v2.1.1 (NOT the upstream docs):
--   * memory callbacks use emu.callbackType (exec/read/write); emu.memCallbackType is nil
--   * emu.getState() inside a callback has NO .ppu and NO .cart — do not touch them here;
--     frame number comes from our own endFrame counter instead
--   * runs headless: Mesen.exe --testRunner --doNotSaveSettings write-watch.lua rom.nes
--
-- Output: <MesenDir>/LuaScriptData/write-watch/writes.txt (flushed per line, so a
-- crash mid-run still leaves usable data).

-- ==== CONFIG ====
local WATCH_START  = 0x8000   -- watch range start (mapper register space by default)
local WATCH_END    = 0xFFFF   -- watch range end
local TARGET_FRAME = 600      -- stop after this many frames
local MAX_LINES    = 20000    -- safety valve so a write-happy game can't fill the disk
-- ================

local frame = 0
local lines = 0

local base = emu.getScriptDataFolder()
local last = string.sub(base, -1)
if last ~= "\\" and last ~= "/" then base = base .. "/" end
local f = assert(io.open(base .. "writes.txt", "w"))

local function onWrite(address, value)
    if lines >= MAX_LINES then return end
    lines = lines + 1
    f:write(string.format("frame=%d addr=$%04X val=$%02X\n", frame, address, value))
    f:flush()
end

local function onEndFrame()
    frame = frame + 1
    if frame >= TARGET_FRAME then
        f:write(string.format("-- done: %d frames, %d writes logged\n", frame, lines))
        f:close()
        emu.stop(0)
    end
end

emu.addMemoryCallback(onWrite, emu.callbackType.write, WATCH_START, WATCH_END)
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
