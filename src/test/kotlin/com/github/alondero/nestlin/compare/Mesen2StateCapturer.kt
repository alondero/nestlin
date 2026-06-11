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
    private val MESEN_ARGS = listOf("--testRunner", "--doNotSaveSettings")

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
            val absoluteRom = romPath.toAbsolutePath()
            val process = ProcessBuilder().apply {
                command(mesenPath.toString(), *MESEN_ARGS.toTypedArray(),
                        scriptPath.toString(), absoluteRom.toString())
                directory(mesenDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
                redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }.start()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw Mesen2ExecutionException("Mesen2 --testRunner exited with code $exitCode.")
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
        // --testRunner mode. Mesen2 v2 returns getState() as a FLAT table with
        // dotted string keys: state["cpu.pc"], state["ppu.frameCount"], etc.
        // NOT state.cpu.pc (that throws nil-index). Confirmed by Phase 0 spike
        // 2026-05-20 in docs/TESTING_STRATEGY.md.
        // getScriptDataFolder() has no trailing separator — insert "/".
        val luaScript = """
local targetFrame = $targetFrame
local frame = 0

-- Interrupt counters, reset at every endFrame, so at capture time they hold the
-- number of NMI/IRQ dispatches during the FINAL FULL FRAME. emu.eventType.irq
-- fires for mapper IRQs too (verified on v2.1.1) and is reliable for counting.
local nmiThisFrame = 0
local irqThisFrame = 0

local function onNmi()
    nmiThisFrame = nmiThisFrame + 1
end

local function onIrq()
    irqThisFrame = irqThisFrame + 1
end

local function writeState()
    local s = emu.getState()

    local function n(k) return s[k] or 0 end
    -- Boolean flag -> 0/1. (Lua treats 0 as truthy, so this is only safe for genuine booleans.)
    local function b(k) if s[k] then return 1 else return 0 end end
    -- Numeric "address" flag (e.g. patternAddr is 0 or 0x1000) -> 0/1.
    local function nz(k) local v = s[k]; if type(v) == "number" and v ~= 0 then return 1 else return 0 end end

    -- Mesen2 stores PPUCTRL/MASK/STATUS decomposed; rebuild the raw register bytes
    -- so they line up with Nestlin's raw $2000/$2001/$2002 snapshot. Base-nametable
    -- bits live in the t register, not in the decomposed control struct.
    local control = b("ppu.control.nmiOnVerticalBlank") * 128
        + b("ppu.control.largeSprites") * 32
        + nz("ppu.control.backgroundPatternAddr") * 16
        + nz("ppu.control.spritePatternAddr") * 8
        + b("ppu.control.verticalWrite") * 4
        + (math.floor(n("ppu.tmpVideoRamAddr") / 1024) % 4)
    local mask = b("ppu.mask.grayscale")
        + b("ppu.mask.backgroundMask") * 2
        + b("ppu.mask.spriteMask") * 4
        + b("ppu.mask.backgroundEnabled") * 8
        + b("ppu.mask.spritesEnabled") * 16
        + b("ppu.mask.intensifyRed") * 32
        + b("ppu.mask.intensifyGreen") * 64
        + b("ppu.mask.intensifyBlue") * 128
    local ppuStatus = b("ppu.statusFlags.spriteOverflow") * 32
        + b("ppu.statusFlags.sprite0Hit") * 64
        + b("ppu.statusFlags.verticalBlank") * 128

    -- Arrays in the {idx:val,...} shape the Kotlin parseIntArray() expects.
    local function dumpMem(memType, size)
        local parts = {}
        for i = 0, size - 1 do parts[#parts + 1] = i .. ":" .. emu.read(i, memType) end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    local function dumpPalette()
        local parts = {}
        for i = 0, 31 do parts[#parts + 1] = i .. ":" .. n("ppu.paletteRam" .. i) end
        return "{" .. table.concat(parts, ",") .. "}"
    end

    local json = "{" ..
        "\"pc\":" .. n("cpu.pc") .. "," ..
        "\"a\":" .. n("cpu.a") .. "," ..
        "\"x\":" .. n("cpu.x") .. "," ..
        "\"y\":" .. n("cpu.y") .. "," ..
        "\"sp\":" .. n("cpu.sp") .. "," ..
        "\"status\":" .. n("cpu.ps") .. "," ..
        "\"cycleCount\":" .. n("cpu.cycleCount") .. "," ..
        "\"scanline\":" .. n("ppu.scanline") .. "," ..
        "\"ppuCycle\":" .. n("ppu.cycle") .. "," ..
        "\"ppuFrameCount\":" .. n("ppu.frameCount") .. "," ..
        "\"control\":" .. control .. "," ..
        "\"mask\":" .. mask .. "," ..
        "\"ppuStatus\":" .. ppuStatus .. "," ..
        "\"nmiCountLastFrame\":" .. nmiThisFrame .. "," ..
        "\"irqCountLastFrame\":" .. irqThisFrame .. "," ..
        "\"cpuRam\":" .. dumpMem(emu.memType.nesInternalRam, 2048) .. "," ..
        "\"oam\":" .. dumpMem(emu.memType.nesSpriteRam, 256) .. "," ..
        "\"chr\":" .. dumpMem(emu.memType.nesPpuMemory, 8192) .. "," ..
        "\"paletteRam\":" .. dumpPalette() ..
    "}"

    local fullPath = emu.getScriptDataFolder() .. "/state.json"
    local f = io.open(fullPath, "w")
    if f then f:write(json); f:close() end
end

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        -- nmiThisFrame/irqThisFrame still hold the counts since the PREVIOUS
        -- endFrame, i.e. the dispatches during the last full frame.
        local ok, err = pcall(writeState)
        if not ok then
            print("state capture error: " .. tostring(err))
            emu.stop(2)
        else
            emu.stop(0)
        end
    end
    -- Reset per-frame interrupt counters for the next frame window.
    nmiThisFrame = 0
    irqThisFrame = 0
end

emu.addEventCallback(onNmi, emu.eventType.nmi)
emu.addEventCallback(onIrq, emu.eventType.irq)
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
            timestamp = System.currentTimeMillis(),
            chr = parseIntArray(json, "chr", 8192),
            nmiCountLastFrame = parseIntOrDefault(json, "nmiCountLastFrame", -1),
            irqCountLastFrame = parseIntOrDefault(json, "irqCountLastFrame", -1)
        )
    }

    private fun parseIntOrDefault(json: String, key: String, default: Int): Int {
        val match = Regex("\"$key\":(-?\\d+)").find(json) ?: return default
        return match.groupValues[1].toIntOrNull() ?: default
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