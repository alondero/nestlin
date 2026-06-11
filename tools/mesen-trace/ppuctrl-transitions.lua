-- ppuctrl-transitions.lua — log every write to PPUCTRL ($2000) and PPUMASK ($2001),
-- including their $2008-$3FFF mirrors, tagged with frame number.
-- The "did the game ever enable rendering / NMI, and when?" instrument: a boot-stall
-- investigation starts by checking whether PPUMASK ever gets bits 3/4 set and whether
-- PPUCTRL bit 7 (NMI enable) toggles at the expected cadence.
--
-- Verified against installed Mesen2 v2.1.1 (emu.callbackType, own frame counter —
-- see write-watch.lua header for the API caveats).
--
-- Output: <MesenDir>/LuaScriptData/ppuctrl-transitions/ppuctrl.txt

-- ==== CONFIG ====
local TARGET_FRAME = 600
-- ================

local frame = 0

local base = emu.getScriptDataFolder()
local last = string.sub(base, -1)
if last ~= "\\" and last ~= "/" then base = base .. "/" end
local f = assert(io.open(base .. "ppuctrl.txt", "w"))

local lastVal = { [0] = -1, [1] = -1 }  -- de-dupe repeated identical writes per register

local function onWrite(address, value)
    local reg = address % 8
    if reg > 1 then return end  -- only $2000/$2001 (and mirrors)
    if lastVal[reg] == value then return end
    lastVal[reg] = value
    local name = (reg == 0) and "PPUCTRL" or "PPUMASK"
    f:write(string.format("frame=%d %s=$%02X\n", frame, name, value))
    f:flush()
end

local function onEndFrame()
    frame = frame + 1
    if frame >= TARGET_FRAME then
        f:write(string.format("-- done at frame %d. PPUCTRL=$%02X PPUMASK=$%02X\n",
            frame, lastVal[0], lastVal[1]))
        f:close()
        emu.stop(0)
    end
end

emu.addMemoryCallback(onWrite, emu.callbackType.write, 0x2000, 0x3FFF)
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
