-- interrupt-counter.lua — count NMI and IRQ fires per frame.
-- The instrument that cracked the Gimmick boot hang (NMI fired once total vs once
-- per frame) and the Klax RAMBO-1 double-IRQ (2/frame vs Mesen's 1/frame).
-- Compare its output against Nestlin's Cpu.nmiCount/irqCount diagnostics or the
-- DivergenceLocalizer's per-frame counts.
--
-- Verified against installed Mesen2 v2.1.1: emu.eventType.irq fires for mapper IRQs
-- and is reliable for COUNTING. Do not call emu.getState() inside the nmi/irq
-- callbacks expecting .ppu/.cart — those fields are nil there; frame attribution
-- uses our own endFrame counter.
--
-- Output: <MesenDir>/LuaScriptData/interrupt-counter/interrupts.txt
-- one line per frame: "frame=N nmi=X irq=Y" (only frames with activity, plus a summary).

-- ==== CONFIG ====
local TARGET_FRAME = 600
-- ================

local frame = 0
local nmiThisFrame, irqThisFrame = 0, 0
local totalNmi, totalIrq = 0, 0

local base = emu.getScriptDataFolder()
local last = string.sub(base, -1)
if last ~= "\\" and last ~= "/" then base = base .. "/" end
local f = assert(io.open(base .. "interrupts.txt", "w"))

local function onNmi() nmiThisFrame = nmiThisFrame + 1 end
local function onIrq() irqThisFrame = irqThisFrame + 1 end

local function onEndFrame()
    frame = frame + 1
    if nmiThisFrame > 0 or irqThisFrame > 0 then
        f:write(string.format("frame=%d nmi=%d irq=%d\n", frame, nmiThisFrame, irqThisFrame))
        f:flush()
    end
    totalNmi = totalNmi + nmiThisFrame
    totalIrq = totalIrq + irqThisFrame
    nmiThisFrame, irqThisFrame = 0, 0
    if frame >= TARGET_FRAME then
        f:write(string.format("-- summary: %d frames, %d NMIs, %d IRQs\n", frame, totalNmi, totalIrq))
        f:close()
        emu.stop(0)
    end
end

emu.addEventCallback(onNmi, emu.eventType.nmi)
emu.addEventCallback(onIrq, emu.eventType.irq)
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
