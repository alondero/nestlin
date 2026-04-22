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
        return path?.let { Paths.get(it) } ?: Paths.get("tools/Mesen2/Mesen.exe")
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

            // Read the state JSON - Lua script tries session folder and mesen_dir
            val possiblePaths = listOf(
                guessScriptDataFolder().resolve("state.json"),
                mesenPath.parent.resolve("mesen_state.json"),
                Paths.get("test_state.json")
            )
            val stateJsonPath = possiblePaths.firstOrNull { Files.exists(it) }
                ?: throw Mesen2ScreenshotException(
                    "Mesen2 script ran but state not found. Tried: ${possiblePaths.joinToString { it.toString() }}"
                )
            val json = Files.readString(stateJsonPath)
            return parseMesen2State(json, romPath.fileName.toString(), frameNumber)
        } finally {
            // Cleanup temp script directory
            scriptPath.toFile().delete()
            runDir.toFile().deleteRecursively()
        }
    }

    private fun generateCaptureScript(targetFrame: Int): String {
        // Use emu.getState() and write what we can get to a file
        // os.exit() instead of emu.stop() since emu.stop() hangs in Mesen2
        val luaScript = """
local targetFrame = $targetFrame
local frame = 0

local function writeState()
    local state = emu.getState()
    local cpu = state.cpu
    local ppu = state.ppu

    -- Simple flat structure - no nesting to avoid nil issues
    local snapshot = {
        pc = cpu.pc,
        a = cpu.a,
        x = cpu.x,
        y = cpu.y,
        sp = cpu.sp,
        status = cpu.status,
        cycleCount = cpu.cycleCount,
        scanline = ppu.scanline,
        ppuCycle = ppu.cycle,
        ppuFrameCount = ppu.frameCount,
        control = ppu.control,
        mask = ppu.mask,
        ppuStatus = ppu.status
    }

    -- Encode as simple JSON
    local json = "{" ..
        "\"pc\":" .. cpu.pc .. "," ..
        "\"a\":" .. cpu.a .. "," ..
        "\"x\":" .. cpu.x .. "," ..
        "\"y\":" .. cpu.y .. "," ..
        "\"sp\":" .. cpu.sp .. "," ..
        "\"status\":" .. cpu.status .. "," ..
        "\"cycleCount\":" .. cpu.cycleCount .. "," ..
        "\"scanline\":" .. ppu.scanline .. "," ..
        "\"ppuCycle\":" .. ppu.cycle .. "," ..
        "\"ppuFrameCount\":" .. ppu.frameCount .. "," ..
        "\"control\":" .. (ppu.control or 0) .. "," ..
        "\"mask\":" .. (ppu.mask or 0) .. "," ..
        "\"ppuStatus\":" .. (ppu.status or 0) ..
    "}"

    -- Try to write to session folder (most likely to work)
    local scriptDir = emu.getScriptDataFolder()
    -- Also try relative to Mesen2's directory (mesenDir)
    local testPaths = {
        scriptDir .. "state.json",
        "mesen_state.json"
    }
    for i, p in ipairs(testPaths) do
        local f = io.open(p, "w")
        if f then
            f:write(json)
            f:close()
            break
        end
    end
end

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        writeState()
        os.exit()
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
        return luaScript
    }

    private fun guessScriptDataFolder(): Path {
        val mesenPath = getMesen2Path()
        // Mesen2 creates a session folder: <mesen_dir>/LuaScriptData/<script_name>/
        // where <script_name> is derived from the script filename
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

    private data class Mesen2CartState(
        val selectedChrPages: IntArray?,
        val selectedPrgPages: IntArray?,
        val prgRomSize: Int,
        val chrRomSize: Int
    )

    private fun parseCartState(json: String): Mesen2CartState {
        val chrPages = parseIntArrayFromTable(json, "selectedChrPages", 8)
        val prgPages = parseIntArrayFromTable(json, "selectedPrgPages", 4)
        val prgSize = parseInt(json, "prgRomSize")
        val chrSize = parseInt(json, "chrRomSize")
        return Mesen2CartState(chrPages, prgPages, prgSize, chrSize)
    }

    private fun parseIntArrayFromTable(json: String, key: String, size: Int): IntArray? {
        // Look for "key":{n0:v0,n1:v1,...}
        val pattern = "\"$key\":\\{([0-9:,]*)\\}"
        val regex = Regex(pattern)
        val match = regex.find(json) ?: return null
        val arr = IntArray(size)
        val content = match.groupValues[1]
        val pairs = content.split(",")
        for (pair in pairs) {
            if (pair.contains(":")) {
                val parts = pair.split(":")
                val idx = parts[0].toIntOrNull() ?: continue
                val value = parts[1].toIntOrNull() ?: continue
                if (idx in 0 until size) arr[idx] = value
            }
        }
        return arr
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
            cycle = parseInt(json, "ppuCycle"),
            scanline = parseInt(json, "scanline"),
            frameCount = parseInt(json, "ppuFrameCount"),
            control = parseInt(json, "control"),
            mask = parseInt(json, "mask"),
            status = parseInt(json, "ppuStatus"),
            vRamAddress = 0
        )
    }

    private fun parsePpuRegisters(json: String): PpuRegisters {
        return PpuRegisters(
            controller = parseInt(json, "control"),
            mask = parseInt(json, "mask"),
            status = parseInt(json, "ppuStatus"),
            oamAddress = 0,
            oamData = 0,
            scroll = 0,
            address = 0,
            data = 0
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
