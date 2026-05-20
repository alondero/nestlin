package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Drives Mesen2 in GUI mode to dump OAM at a list of frames so we can
 * compare against Nestlin OAM byte-for-byte. Setup and gotchas live in
 * [Mesen2Process] and `.claude/skills/mesen/SKILL.md`.
 */
object Mesen2OamDumpRunner {

    private const val SCRIPT_NAME = "dump_oam"

    fun mesen2Path(): Path = Mesen2Process.mesen2Path()
    fun isAvailable(): Boolean = Mesen2Process.isAvailable()

    /**
     * Run [romPath] for max([targetFrames]) frames; on each target frame
     * write a `frameNNNN.txt` file with 16 sprites of OAM contents plus a
     * `frameNNNN.png` screenshot to [outDir].
     */
    fun dumpOam(romPath: Path, targetFrames: List<Int>, outDir: Path) {
        val scriptDataDir = Mesen2Process.runScript(
            generateScript(targetFrames),
            romPath,
            SCRIPT_NAME
        )
        Files.createDirectories(outDir)
        if (Files.exists(scriptDataDir)) {
            Files.list(scriptDataDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith("frame") }
                      .forEach { Files.copy(it, outDir.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private fun generateScript(targetFrames: List<Int>): String {
        val targetsLua = targetFrames.joinToString(",")
        return """
local targets = { $targetsLua }
local maxFrame = ${targetFrames.max()}
local targetSet = {}
for _, f in ipairs(targets) do targetSet[f] = true end
local frame = 0
local base = emu.getScriptDataFolder()

-- Find a working memType for OAM (varies by Mesen version)
local oamType = nil
local function pickOamType()
    local candidates = { "nesSpriteRam", "spriteRam", "nesOam", "oam" }
    for _, name in ipairs(candidates) do
        if emu.memType[name] then return name, emu.memType[name] end
    end
    return nil, nil
end

local typeName, typeVal = pickOamType()

function onEndFrame()
    frame = frame + 1
    if targetSet[frame] then
        local path = base .. "/frame" .. string.format("%04d", frame) .. ".txt"
        local f = io.open(path, "w")
        if f then
            f:write("# memType=" .. tostring(typeName) .. "\n")
            if typeVal then
                for i = 0, 15 do
                    local base_addr = i * 4
                    local y    = emu.read(base_addr,     typeVal)
                    local tile = emu.read(base_addr + 1, typeVal)
                    local attr = emu.read(base_addr + 2, typeVal)
                    local x    = emu.read(base_addr + 3, typeVal)
                    f:write(string.format("S%02d Y=%3d X=%3d tile=${'$'}%02X attr=${'$'}%02X\n", i, y, x, tile, attr))
                end
            else
                f:write("# No OAM memType available\n")
                for k, v in pairs(emu.memType) do
                    f:write("# memType." .. tostring(k) .. " = " .. tostring(v) .. "\n")
                end
            end
            local pmem = emu.memType.nesPpuMemory or emu.memType.nesPpu or
                         emu.memType.ppuMemory or emu.memType.ppu or
                         emu.memType.nesChrRom
            f:write("# available memTypes:")
            for k, v in pairs(emu.memType) do f:write(" " .. tostring(k)) end
            f:write("\n# chosen pmem = " .. tostring(pmem) .. "\n")
            if pmem then
                f:write("# pattern-table dump ${'$'}1800-${'$'}1BFF (1KB)\n")
                for addr = 0x1800, 0x1BFF, 16 do
                    local line = string.format("%04X:", addr)
                    for i = 0, 15 do
                        line = line .. string.format(" %02X", emu.read(addr + i, pmem))
                    end
                    f:write(line .. "\n")
                end
            end
            f:close()
        end
    end
    if targetSet[frame] then
        local data = emu.takeScreenshot()
        local png = base .. "/frame" .. string.format("%04d", frame) .. ".png"
        local pf = io.open(png, "wb")
        if pf then pf:write(data); pf:close() end
    end
    if frame >= maxFrame then
        os.exit()
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
    }
}
