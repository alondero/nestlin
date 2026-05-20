package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Captures CPU, PPU, and memory state from Mesen2 at a specific frame boundary.
 * Uses Lua scripting with emu.getState() and emu.read(). Setup details in
 * [Mesen2Process] and `.claude/skills/mesen/SKILL.md`.
 */
object Mesen2StateCapturer {

    private const val SCRIPT_NAME = "state_capture"

    fun getMesen2Path(): Path = Mesen2Process.mesen2Path()
    fun isMesen2Available(): Boolean = Mesen2Process.isAvailable()

    fun captureState(romPath: Path, frameNumber: Int): EmulatorStateSnapshot {
        val scriptDataDir = Mesen2Process.runScript(
            generateCaptureScript(frameNumber),
            romPath,
            SCRIPT_NAME
        )
        val stateJsonPath = scriptDataDir.resolve("state.json")
        if (!Files.exists(stateJsonPath)) {
            throw Mesen2ScreenshotException(
                "Mesen2 script ran but state.json not found at $stateJsonPath. " +
                "I/O access may not be enabled."
            )
        }
        val json = Files.readString(stateJsonPath)
        return parseMesen2State(json, romPath.fileName.toString(), frameNumber)
    }

    private fun generateCaptureScript(targetFrame: Int): String {
        // os.exit() instead of emu.stop() since emu.stop() hangs in Mesen2.
        // basePath separator fixup: emu.getScriptDataFolder() returns no
        // trailing separator on Windows, so naive concatenation lands the
        // file outside the script's data folder.
        return """
local targetFrame = $targetFrame
local frame = 0

local function writeState()
    local state = emu.getState()
    local cpu = state.cpu
    local ppu = state.ppu

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

    local basePath = emu.getScriptDataFolder()
    local last = string.sub(basePath, -1)
    if last ~= "\\" and last ~= "/" then
        basePath = basePath .. "\\"
    end
    local f = io.open(basePath .. "state.json", "w")
    if f then
        f:write(json)
        f:close()
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
