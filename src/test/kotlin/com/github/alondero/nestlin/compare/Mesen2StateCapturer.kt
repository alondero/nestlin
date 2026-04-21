package com.github.alondero.nestlin.compare

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Captures CPU, PPU, and memory state from Mesen2 at a specific frame boundary.
 * Uses Lua scripting with emu.getState() and emu.read().
 */
object Mesen2StateCapturer {

    private const val ENV_VAR = "MESEN2_PATH"
    private val MESEN_ARGS = listOf("--doNotSaveSettings")

    fun getMesen2Path(): Path {
        val path = System.getenv(ENV_VAR) ?: System.getProperty("mesen2.path")
        return path?.let { Paths.get(it) } ?: Paths.get("tools/Mesen/Mesen.exe")
    }

    fun isMesen2Available(): Boolean = getMesen2Path().toFile().exists()

    fun captureState(romPath: Path, frameNumber: Int): EmulatorStateSnapshot {
        val mesenPath = getMesen2Path()
        val mesenDir = mesenPath.parent.toFile()

        // Create a unique temp directory for this run's script
        val runDir = Files.createTempDirectory("mesen_state_")
        val scriptPath = runDir.resolve("state_capture.lua")

        // Generate the Lua script for state capture
        val luaScript = generateCaptureScript(frameNumber)
        Files.writeString(scriptPath, luaScript)

        try {
            // Run Mesen2 with the state capture script
            val process = ProcessBuilder().apply {
                command(mesenPath.toString(), *MESEN_ARGS.toTypedArray(),
                        scriptPath.toString(), romPath.toString())
                directory(mesenDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
                redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }.start()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw Mesen2ExecutionException(
                    "Mesen2 exited with code $exitCode. " +
                    "Make sure I/O access is enabled in Script → Settings → " +
                    "Script Window → Restrictions → Allow access to I/O and OS functions."
                )
            }

            // Read the state JSON from script data folder
            val stateJsonPath = guessScriptDataFolder().resolve("state.json")
            if (Files.exists(stateJsonPath)) {
                val json = Files.readString(stateJsonPath)
                return parseMesen2State(json, romPath.fileName.toString(), frameNumber)
            } else {
                throw Mesen2ScreenshotException(
                    "Mesen2 script ran but state not found at $stateJsonPath. " +
                    "I/O access may not be enabled."
                )
            }
        } finally {
            // Cleanup temp script directory
            scriptPath.toFile().delete()
            runDir.toFile().deleteRecursively()
        }
    }

    private fun generateCaptureScript(targetFrame: Int): String {
        // Use emu.addEventCallback for endFrame, then dump state via emu.getState() and emu.read()
        val luaScript = """
local targetFrame = $targetFrame
local frame = 0

local function writeState()
    local state = emu.getState()
    local cpu = state.cpu
    local ppu = state.ppu

    -- Build state table as JSON-compatible structure
    local snapshot = {
        cpu = {
            pc = cpu.pc,
            a = cpu.a,
            x = cpu.x,
            y = cpu.y,
            sp = cpu.sp,
            status = cpu.status,
            cycleCount = cpu.cycleCount
        },
        ppu = {
            cycle = ppu.cycle,
            scanline = ppu.scanline,
            frameCount = ppu.frameCount,
            control = emu.read(0x2000, emu.memType.cpuDebug),
            mask = emu.read(0x2001, emu.memType.cpuDebug),
            status = emu.read(0x2002, emu.memType.cpuDebug)
        },
        cpuRam = {},
        ppuRegisters = {
            controller = emu.read(0x2000, emu.memType.cpuDebug),
            mask = emu.read(0x2001, emu.memType.cpuDebug),
            status = emu.read(0x2002, emu.memType.cpuDebug),
            oamAddress = emu.read(0x2003, emu.memType.cpuDebug),
            oamData = emu.read(0x2004, emu.memType.cpuDebug),
            scroll = emu.read(0x2005, emu.memType.cpuDebug),
            address = emu.read(0x2006, emu.memType.cpuDebug),
            data = emu.read(0x2007, emu.memType.cpuDebug)
        },
        oam = {},
        paletteRam = {}
    }

    -- Read 2KB CPU RAM
    for i = 0, 0x7FF do
        snapshot.cpuRam[i] = emu.read(i, emu.memType.cpuDebug)
    end

    -- Read 256B OAM
    for i = 0, 0xFF do
        snapshot.oam[i] = emu.read(i, emu.memType.oam)
    end

    -- Read 32B Palette RAM
    for i = 0, 0x1F do
        snapshot.paletteRam[i] = emu.read(0x3F00 + i, emu.memType.palette)
    end

    -- Write JSON manually (simple serialization)
    local json = encodeJSON(snapshot)
    local f = io.open(emu.getScriptDataFolder() .. "state.json", "w")
    if f then
        f:write(json)
        f:close()
    end
end

-- Simple JSON encoder for tables with only simple types
function encodeJSON(obj)
    if type(obj) == "table" then
        local keys = {}
        for k, v in pairs(obj) do
            table.insert(keys, k)
        end
        table.sort(keys, function(a, b)
            if type(a) == "number" and type(b) == "number" then
                return a < b
            elseif type(a) == "string" and type(b) == "string" then
                return a < b
            end
            return tostring(a) < tostring(b)
        end)

        local parts = {}
        for _, k in ipairs(keys) do
            local v = obj[k]
            if type(k) == "number" then
                table.insert(parts, string.format("%d:%s", k, encodeJSON(v)))
            else
                table.insert(parts, string.format("\"%s\":%s", k, encodeJSON(v)))
            end
        end
        return "{" .. table.concat(parts, ",") .. "}"
    elseif type(obj) == "string" then
        return "\"" .. obj .. "\""
    elseif type(obj) == "number" then
        if obj == math.floor(obj) then
            return string.format("%d", obj)
        else
            return string.format("%.10g", obj)
        end
    elseif type(obj) == "boolean" then
        return obj and "true" or "false"
    else
        return "null"
    end
end

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        writeState()
        emu.stop()
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
        return luaScript
    }

    private fun guessScriptDataFolder(): Path {
        val mesenPath = getMesen2Path()
        return mesenPath.parent.resolve("LuaScriptData").resolve("state_capture")
    }

    private fun parseMesen2State(json: String, romName: String, frameNumber: Int): EmulatorStateSnapshot {
        // Parse JSON manually since we don't want to add another dependency
        // Use simple regex-based parsing for the specific structure we expect
        return EmulatorStateSnapshot(
            emulator = "Mesen2",
            romName = romName,
            frameNumber = frameNumber,
            cpu = parseCpuState(json),
            ppu = parsePpuState(json),
            cpuRam = parseIntArray(json, "cpuRam", 0x800),
            ppuRegisters = parsePpuRegisters(json),
            oam = parseIntArray(json, "oam", 256),
            paletteRam = parseIntArray(json, "paletteRam", 32),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseCpuState(json: String): CpuState {
        return CpuState(
            pc = parseInt(json, "pc"),
            a = parseInt(json, "a"),
            x = parseInt(json, "x"),
            y = parseInt(json, "y"),
            sp = parseInt(json, "sp"),
            status = parseInt(json, "status"),
            cycleCount = parseLong(json, "cycleCount")
        )
    }

    private fun parsePpuState(json: String): PpuState {
        return PpuState(
            cycle = parseInt(json, "cycle"),
            scanline = parseInt(json, "scanline"),
            frameCount = parseInt(json, "frameCount"),
            control = parseInt(json, "control"),
            mask = parseInt(json, "mask"),
            status = parseInt(json, "status"),
            vRamAddress = 0  // Will be computed from scroll if needed
        )
    }

    private fun parsePpuRegisters(json: String): PpuRegisters {
        return PpuRegisters(
            controller = parseInt(json, "controller"),
            mask = parseInt(json, "mask"),
            status = parseInt(json, "status"),
            oamAddress = parseInt(json, "oamAddress"),
            oamData = parseInt(json, "oamData"),
            scroll = parseInt(json, "scroll"),
            address = parseInt(json, "address"),
            data = parseInt(json, "data")
        )
    }

    private fun parseIntArray(json: String, key: String, size: Int): IntArray {
        val arr = IntArray(size)
        val pattern = "\"$key\":\\{([0-9:,]*)\\}"
        val regex = Regex(pattern)
        val match = regex.find(json)
        if (match != null) {
            val values = match.groupValues[1].split(",")
            for (i in values.indices) {
                if (i < size && values[i].contains(":")) {
                    val parts = values[i].split(":")
                    val idx = parts[0].toInt()
                    val value = parts[1].toInt()
                    if (idx in 0 until size) {
                        arr[idx] = value
                    }
                }
            }
        }
        return arr
    }

    private fun parseInt(json: String, key: String): Int {
        // Simple regex to find "key":value within the cpu or ppu section
        val pattern = "\"$key\":(-?\\d+)"
        val regex = Regex(pattern)
        val match = regex.find(json)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun parseLong(json: String, key: String): Long {
        val pattern = "\"$key\":(-?\\d+)"
        val regex = Regex(pattern)
        val match = regex.find(json)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
