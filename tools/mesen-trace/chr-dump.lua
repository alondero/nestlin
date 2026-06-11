-- chr-dump.lua — dump the active 8KB PPU pattern data ($0000-$1FFF) at a target frame.
-- Byte-compare this against Nestlin's CHR window (NestlinStateCapturer captures the
-- same region through the mapper) to prove or disprove a CHR-banking bug: if OAM
-- matches but CHR differs, the mapper has the wrong bank mapped — see the
-- DivergenceLocalizer classification table.
--
-- emu.read works in --testRunner mode on the installed Mesen2 v2.1.1. Reads happen
-- in the endFrame callback (NOT inside a memory callback, where getState/reads are
-- restricted).
--
-- Output: <MesenDir>/LuaScriptData/chr-dump/chr.txt — one "offset:value" pair per
-- line in hex, trivially diffable.

-- ==== CONFIG ====
local TARGET_FRAME = 120
-- ================

local frame = 0

local function dump()
    local base = emu.getScriptDataFolder()
    local last = string.sub(base, -1)
    if last ~= "\\" and last ~= "/" then base = base .. "/" end
    local f = assert(io.open(base .. "chr.txt", "w"))
    for i = 0, 0x1FFF do
        f:write(string.format("%04X:%02X\n", i, emu.read(i, emu.memType.nesPpuMemory)))
    end
    f:close()
end

local function onEndFrame()
    frame = frame + 1
    if frame >= TARGET_FRAME then
        local ok, err = pcall(dump)
        if not ok then
            print("chr-dump failed: " .. tostring(err))
            emu.stop(2)
        else
            emu.stop(0)
        end
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
