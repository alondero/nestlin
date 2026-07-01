package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives Mesen2 in GUI mode to dump OAM at a list of frames so we can
 * compare against Nestlin OAM byte-for-byte.
 *
 * Path resolution delegates to [Mesen2Session.mesen2Path] (see [mesen2Path]),
 * so this runner uses the same canonical chain as the rest of the compare lane.
 */
object Mesen2OamDumpRunner {
    private val MESEN_ARGS = listOf("--testRunner", "--doNotSaveSettings")

    /**
     * Delegates to [Mesen2Session.mesen2Path] so this runner honours the same
     * canonical resolution chain (`MESEN2_PATH` env → `mesen2.path` property →
     * absolute parent path → relative fallback) as the rest of the lane. It
     * previously had its own env-only lookup, which made [isAvailable]'s
     * skip-not-fail semantics subtly inconsistent (issue #214 fix 4).
     */
    fun mesen2Path(): Path = Mesen2Session.mesen2Path()

    fun isAvailable(): Boolean = mesen2Path().toFile().exists()

    /**
     * Run Kirby for `maxFrame` frames; on each frame in `targetFrames`
     * write a `frameNNNN.txt` file with 16 sprites of OAM contents.
     */
    fun dumpOam(romPath: Path, targetFrames: List<Int>, outDir: Path) {
        val mesen = mesen2Path()
        val mesenDir = mesen.parent.toFile()
        val runDir = Files.createTempDirectory("mesen_oam_")
        val scriptPath = runDir.resolve("dump_oam.lua")
        val targetsLua = targetFrames.joinToString(",")

        val lua = """
local targets = { $targetsLua }
local maxFrame = ${targetFrames.max()}
local targetSet = {}
for _, f in ipairs(targets) do targetSet[f] = true end
local frame = 0
local base = emu.getScriptDataFolder() .. "/"

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
        local path = base .. "frame" .. string.format("%04d", frame) .. ".txt"
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
            -- Dump pattern table bytes for the sprite-fetch addresses Kirby would use
            -- on the title screen (Kirby sprite tiles, both planes, every row)
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
        local png = base .. "frame" .. string.format("%04d", frame) .. ".png"
        local pf = io.open(png, "wb")
        if pf then pf:write(data); pf:close() end
    end
    if frame >= maxFrame then
        emu.stop(0)
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
        Files.writeString(scriptPath, lua)

        try {
            val absoluteRom = romPath.toAbsolutePath()
            val process = ProcessBuilder().apply {
                command(mesen.toString(), *MESEN_ARGS.toTypedArray(),
                        scriptPath.toString(), absoluteRom.toString())
                directory(mesenDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
                redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                System.err.println("Mesen2 exited with code $exitCode " +
                    "(I/O access must be enabled in Script -> Settings -> Restrictions)")
            }
            val scriptDataDir = mesen.parent.resolve("LuaScriptData").resolve("dump_oam")
            Files.createDirectories(outDir)
            if (Files.exists(scriptDataDir)) {
                Files.list(scriptDataDir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("frame") }
                          .forEach { Files.copy(it, outDir.resolve(it.fileName),
                              java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        } finally {
            scriptPath.toFile().delete()
            runDir.toFile().deleteRecursively()
        }
    }
}