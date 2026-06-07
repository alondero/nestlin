package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Drives Mesen2 in --testRunner (headless) mode against the Akira ROM to
 * capture a structured PPU/CPU state snapshot at frame 175 — the moment
 * Nestlin's spin loop on $2002 bit 6 (sprite-0 hit) begins. Diffing the
 * Nestlin snapshot at the same frame against this oracle should reveal the
 * first divergence that explains the freeze (issue #141).
 *
 * The ROM is NO-INTRO and not in git; skipped when absent. Mesen2 is
 * resolved via MESEN2_PATH / `tools/Mesen2/Mesen.exe` — also skipped when
 * neither is present, since CI runners won't have either.
 *
 * NOTE: this test does NOT drive the FM2 inputs. It is intentionally a
 * "title-screen baseline" — if Mesen2 with no input diverges from Nestlin
 * at frame 60 or earlier, that points to a mapper/CHR/PRG-timimg bug.
 * Driving the FM2 inputs is a follow-up that requires Mesen2's movie API.
 */
class AkiraMesen2OracleTest {

    private val rom: Path = Paths.get("S:/Media/Nintendo NES/Games/Akira (Japan) (Translated En).nes")
    private val targetFrame = 175

    @Test
    fun `capture Mesen2 PPU state at Akira frame 175`() {
        assumeTrue(Files.isRegularFile(rom), "Akira ROM not found at $rom")
        assumeTrue(Mesen2OamDumpRunner.isAvailable(), "Mesen2 not installed")

        val mesen = Mesen2OamDumpRunner.mesen2Path()
        val mesenDir = mesen.parent.toFile()
        val runDir = Files.createTempDirectory("mesen_akira_")
        val scriptPath = runDir.resolve("akira_oracle.lua")
        val fm2Path = "X:/src/nestlin/.claude/worktrees/dark-tough-owl/build/repro-141/Akira (Japan) (Translated En) - hang.fm2"
        val lua = """
local target = $targetFrame
local frame = 0
local base = emu.getScriptDataFolder()
if string.sub(base, -1) ~= "\\" and string.sub(base, -1) ~= "/" then base = base .. "\\" end

-- Read FM2 input rows; we feed controller state via $4016 strobe each frame.
-- NES standard controller: write 1 to \$4016 to strobe, 0 to latch buttons, then game reads 8 bits.
-- A, B, Sel, Start, Up, Down, Left, Right in Mesen2's bit order.
-- FM2 row format: |cmd|RLDUTSBA|RLDUTSBA||
-- Bit order in the controller hardware: A=bit0, B=bit1, Sel=bit2, Start=bit3, Up=4, Down=5, Left=6, Right=7.
local fm2Path = "${fm2Path.replace("\\", "\\\\")}"
local fm2Lines = {}
local f = io.open(fm2Path, "r")
if f then
    for line in f:lines() do
        -- Skip header lines (don't start with |)
        if string.sub(line, 1, 1) == "|" then
            -- Extract controller 1: characters 4..11
            local p1 = string.sub(line, 4, 11)
            local bits = 0
            if string.find(p1, "A", 1, true) then bits = bits + 0x01 end
            if string.find(p1, "B", 1, true) then bits = bits + 0x02 end
            if string.find(p1, "S", 1, true) then bits = bits + 0x04 end
            if string.find(p1, "T", 1, true) then bits = bits + 0x08 end  -- Start (T in FM2)
            if string.find(p1, "U", 1, true) then bits = bits + 0x10 end
            if string.find(p1, "D", 1, true) then bits = bits + 0x20 end
            if string.find(p1, "L", 1, true) then bits = bits + 0x40 end
            if string.find(p1, "R", 1, true) then bits = bits + 0x80 end
            table.insert(fm2Lines, bits)
        end
    end
    f:close()
end
print("# FM2 rows loaded: " .. #fm2Lines)
io.stdout:flush()

-- Memory types
local memTypes = {}
for k, v in pairs(emu.memType) do memTypes[k] = v end
local function pick(...) for _, n in ipairs({...}) do if memTypes[n] then return memTypes[n] end end end

local oamType      = pick("nesSpriteRam", "spriteRam", "nesOam", "oam")
local ctrlType     = pick("nesMemory", "cpuMemory", "memory")
local ctrlPortType = ctrlType

-- Controller latch. On each inputPolled we drive the controller.
-- We can't directly set button state in Mesen2 from Lua, so we use the hardware-shifted
-- register trick: poll each frame and write to \$4016 strobe to "request" the game to
-- re-read the current (movie-driven) state. In Mesen2, emu.write to \$4016 won't change
-- the shift register's contents — the buttons are set by the movie system. Without FM2
-- movie support, we instead use the inputPolled event to poke \$4016 and hope Mesen2
-- samples the (default zero) buttons for each frame.
function onInputPolled()
    -- No-op: cannot drive inputs in v2 Lua. Frame counter for diagnostics.
end
emu.addEventCallback(onInputPolled, emu.eventType.inputPolled)

function onEndFrame()
    frame = frame + 1
    if frame == target then
        local f = io.open(base .. "frame0175.txt", "w")
        f:write("# Frame $targetFrame state from Mesen2 (oracle) - no-input baseline (FM2 not driven)\n")
        f:write("# FM2 rows in file: " .. #fm2Lines .. "\n")
        f:write("# Controller bits at frame " .. target .. ": " .. string.format("0x%02X", fm2Lines[target] or 0) .. "\n")
        local s = emu.getState()
        f:write(string.format("CPU.PC=%04X  A=%02X  X=%02X  Y=%02X  SP=%02X  P=%02X  cyc=%d\n",
            s["cpu.pc"], s["cpu.a"], s["cpu.x"], s["cpu.y"], s["cpu.sp"], s["cpu.ps"], s["cpu.cycleCount"]))
        local ctrl = 0
        if s["ppu.control.nmiOnVerticalBlank"] then ctrl = ctrl + 0x80 end
        if s["ppu.control.spriteSize"] then ctrl = ctrl + 0x20 end
        if s["ppu.control.backgroundPatternAddr"] then ctrl = ctrl + 0x10 end
        if s["ppu.control.spritePatternAddr"] then ctrl = ctrl + 0x08 end
        if s["ppu.control.vramIncrement32"] then ctrl = ctrl + 0x04 end
        ctrl = ctrl + (s["ppu.control.nametableAddr"] or 0)
        local mask = 0
        if s["ppu.mask.intensifyBlue"] then mask = mask + 0x80 end
        if s["ppu.mask.intensifyRed"] then mask = mask + 0x20 end
        if s["ppu.mask.intensifyGreen"] then mask = mask + 0x40 end
        if s["ppu.mask.spritesEnabled"] then mask = mask + 0x10 end
        if s["ppu.mask.backgroundEnabled"] then mask = mask + 0x08 end
        if s["ppu.mask.spriteMask"] then mask = mask + 0x04 end
        if s["ppu.mask.backgroundMask"] then mask = mask + 0x02 end
        if s["ppu.mask.grayscale"] then mask = mask + 0x01 end
        local status = 0
        if s["ppu.statusFlags.verticalBlank"] then status = status + 0x80 end
        if s["ppu.statusFlags.sprite0Hit"] then status = status + 0x40 end
        if s["ppu.statusFlags.spriteOverflow"] then status = status + 0x20 end
        f:write(string.format("PPUCTRL=%02X  PPUMASK=%02X  PPUSTATUS=%02X  scanline=%d  cycle=%d\n",
            ctrl, mask, status, s["ppu.scanline"], s["ppu.cycle"]))
        f:write("PAL=")
        for i = 0, 31 do f:write(string.format("%02X", s["ppu.paletteRam" .. i] or 0)) end
        f:write("\n")
        if oamType then
            f:write("OAM=")
            for i = 0, 63 do f:write(string.format("%02X", emu.read(i, oamType))) end
            f:write("\n")
        end
        if oamType and ctrlType then
            f:write("ZP00-3F=")
            for i = 0, 63 do f:write(string.format("%02X", emu.read(i, ctrlType))) end
            f:write("\n")
        end
        -- Mapper CHR bank offsets
        f:write("CHR_bank_offsets=")
        for i = 0, 7 do f:write(string.format("%04X ", s["mapper.chrMemoryOffset" .. i] or 0)) end
        f:write("\n")
        f:close()
        local ok, png = pcall(emu.takeScreenshot)
        if ok and png then
            local pf = io.open(base .. "frame0175.png", "wb")
            if pf then pf:write(png); pf:close() end
        end
        emu.stop(0)
    end
    if frame > target + 10 then emu.stop(0) end
end
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
        Files.writeString(scriptPath, lua)
        try {
            val absoluteRom = rom.toAbsolutePath()
            val process = ProcessBuilder().apply {
                command(mesen.toString(), "--testRunner", "--doNotSaveSettings",
                        scriptPath.toString(), absoluteRom.toString())
                directory(mesenDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
                redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }.start()
            val exitCode = process.waitFor()
            println("[AkiraOracle] Mesen2 exit=$exitCode")
            val outFile = mesen.parent.resolve("LuaScriptData").resolve("akira_oracle").resolve("frame0175.txt")
            if (Files.exists(outFile)) {
                println("[AkiraOracle] Captured state:")
                Files.readAllLines(outFile).forEach { println("  $it") }
            } else {
                println("[AkiraOracle] NO CAPTURE FILE produced at $outFile")
            }
            val outPng = mesen.parent.resolve("LuaScriptData").resolve("akira_oracle").resolve("frame0175.png")
            if (Files.exists(outPng)) {
                val out = Paths.get("build/akira-mesen2-frame0175.png").also { Files.createDirectories(it.parent) }
                Files.copy(outPng, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                println("[AkiraOracle] PNG copied to $out")
            }
        } finally {
            scriptPath.toFile().delete()
            runDir.toFile().deleteRecursively()
        }
    }
}
