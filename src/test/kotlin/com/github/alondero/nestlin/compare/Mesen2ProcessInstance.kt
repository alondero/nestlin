package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One long-running Mesen2 subprocess per ROM, used by [Mesen2Session].
 *
 * Wraps a `Mesen.exe --testRunner --doNotSaveSettings <server.lua> <rom>`
 * process whose Lua script polls a command file on every `endFrame`,
 * writes the requested capture (state JSON or PNG) to disk, and signals
 * completion via a `done` marker. The Kotlin side writes the command via
 * an atomic-rename mailbox and polls the marker.
 *
 * ## Why always RESET between captures
 *
 * The Lua frame counter keeps ticking across captures. A naive
 * "runToFrame(N)" semantics breaks if a previous capture already passed
 * frame N — the capture would never fire. The Plan agent (issue #61)
 * caught this: Klax's three sequential tests ask for frames 30, 60, 240
 * in test-method order, and the process is still running between them.
 * Sending `RESET` before each `RUNTO` gives identical semantics to
 * today's per-test-process model (a fresh boot). `emu.reset()` is
 * sub-millisecond — the cost is negligible compared to the avoided
 * process spawn.
 *
 * ## Sequence-numbered done markers
 *
 * `done.txt` is written by Lua and read by Kotlin. Without a sequence
 * number, a slow capture followed by a fast one could hand back stale
 * results (Kotlin reads the OLD done.txt before the new one lands). Every
 * command embeds a monotonic sequence number; Lua echoes it in the done
 * marker; Kotlin only accepts matches. The [nextSeq] counter is per
 * instance — different `Mesen2ProcessInstance`s have independent streams.
 *
 * ## Concurrency
 *
 * JUnit 5's default is sequential test execution, so the [lock] is mostly
 * defensive. But the API is `@ThreadSafe`-shaped — a parallel test runner
 * that calls [runToAndCaptureState] on the same instance must serialize
 * via [lock]. [ConcurrentHashMap.computeIfAbsent] in [Mesen2Session.forRom]
 * already handles the lookup race.
 */
class Mesen2ProcessInstance internal constructor(
    private val rom: Path
) {
    private val sessionDir: Path = Files.createTempDirectory("mesen_session_")
    private val cmdFile: Path = sessionDir.resolve("cmd.txt")
    private val cmdTmpFile: Path = sessionDir.resolve("cmd.txt.tmp")
    private val doneFile: Path = sessionDir.resolve("done.txt")

    // Mesen2 script data folder is <mesen_dir>/LuaScriptData/<script_basename>/
    // The script is named `session_server.lua`, so basename is `session_server`.
    private val scriptDataDir: Path = Mesen2Session.mesen2Path().parent
        .resolve("LuaScriptData").resolve("session_server")
    private val stateJsonPath: Path = scriptDataDir.resolve("state.json")

    private val process: Process
    private val lock = ReentrantLock()
    private val nextSeq = AtomicLong(0)
    private var closed = false

    init {
        val mesen = Mesen2Session.mesen2Path()
        Files.writeString(sessionDir.resolve("session_server.lua"), serverScript())

        val absoluteRom = rom.toAbsolutePath()
        process = ProcessBuilder(
            mesen.toString(),
            "--testRunner",
            "--doNotSaveSettings",
            sessionDir.resolve("session_server.lua").toAbsolutePath().toString(),
            absoluteRom.toString()
        ).apply {
            directory(mesen.parent.toFile())
            redirectError(ProcessBuilder.Redirect.INHERIT)
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
        }.start()

        // Block until Lua signals READY (or 10s timeout).
        waitForMarker(expectedSeq = -1L, expectedPrefix = "READY", timeoutMs = 10_000)
    }

    /**
     * Run the ROM to [frame], capture CPU+PPU+memory state as JSON, parse
     * to an [EmulatorStateSnapshot], and return it. Each call resets the
     * NES state first (see class KDoc).
     */
    fun runToAndCaptureState(frame: Int): EmulatorStateSnapshot = lock.withLock {
        ensureOpen()
        val seq = nextSeq.getAndIncrement()
        sendCommand("RUNTO $seq $frame STATE")
        waitForMarker(seq, expectedPrefix = "DONE", timeoutMs = 30_000)
        val json = Files.readString(stateJsonPath)
        Mesen2StateJsonParser.parseMesen2State(json, rom.fileName.toString(), frame)
    }

    /**
     * Run the ROM to [frame], capture a PNG screenshot via
     * `emu.takeScreenshot()`, copy it to [outPath]. Creates the parent
     * directory tree as needed.
     */
    fun runToAndCaptureScreenshot(frame: Int, outPath: Path) = lock.withLock {
        ensureOpen()
        val seq = nextSeq.getAndIncrement()
        val absoluteOut = outPath.toAbsolutePath()
        sendCommand("RUNTO $seq $frame SHOT $absoluteOut")
        waitForMarker(seq, expectedPrefix = "DONE", timeoutMs = 30_000)
        // The Lua server writes the PNG directly to outPath (passed via the
        // SHOT command), so the screenshot lives at outPath, not in the
        // session's script-data folder.
        if (!Files.exists(absoluteOut)) {
            throw Mesen2ScreenshotException(
                "Mesen2 screenshot not found at $absoluteOut. " +
                "I/O access may not be enabled in Script → Settings."
            )
        }
    }

    /**
     * Send `QUIT` to Lua, wait up to 5s for the process to exit, then
     * `destroyForcibly()` if it didn't. Idempotent.
     */
    fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            runCatching {
                sendCommand("QUIT -1")
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
            runCatching { sessionDir.toFile().deleteRecursively() }
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Mesen2ProcessInstance for $rom is closed" }
        if (!process.isAlive) {
            throw Mesen2ExecutionException(
                "Mesen2 process for $rom died (exit code ${process.exitValue()}). " +
                "Check test output for the Lua error."
            )
        }
    }

    private fun sendCommand(cmd: String) {
        // Atomic-rename mailbox: write tmp then move into place. On NTFS this
        // is a single MoveFileEx with REPLACE_EXISTING + WRITE_THROUGH.
        // Lua reads cmd.txt once per endFrame, removes it after consume.
        //
        // Fall back to non-atomic move if the tmp and cmd files are on
        // different volumes (e.g. TMPDIR=/tmp tmpfs vs project on local disk
        // in some CI runners). AtomicMoveNotSupportedException would otherwise
        // leak cmdTmpFile for the rest of the session.
        Files.writeString(cmdTmpFile, cmd)
        try {
            Files.move(cmdTmpFile, cmdFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(cmdTmpFile, cmdFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun waitForMarker(
        expectedSeq: Long,
        expectedPrefix: String,
        timeoutMs: Long
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastSeen = ""
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw Mesen2ExecutionException(
                    "Mesen2 process for $rom died while waiting for $expectedPrefix " +
                    "(exit ${process.exitValue()}). Last done.txt contents: $lastSeen"
                )
            }
            if (Files.exists(doneFile)) {
                lastSeen = runCatching { Files.readString(doneFile).trim() }.getOrDefault("")
                if (matchesMarker(lastSeen, expectedSeq, expectedPrefix)) {
                    if (lastSeen.startsWith("DONE $expectedSeq ERR ")) {
                        val msg = lastSeen.removePrefix("DONE $expectedSeq ERR ")
                        throw Mesen2ExecutionException("Mesen2 Lua script error: $msg")
                    }
                    return
                }
            }
            Thread.sleep(5)
        }
        throw Mesen2ExecutionException(
            "Mesen2 timed out waiting for $expectedPrefix on $rom after ${timeoutMs}ms. " +
            "Last done.txt contents: ${if (lastSeen.isEmpty()) "<none>" else lastSeen}"
        )
    }

    private fun matchesMarker(seen: String, expectedSeq: Long, expectedPrefix: String): Boolean {
        if (expectedSeq < 0) return seen == expectedPrefix
        if (!seen.startsWith("$expectedPrefix $expectedSeq ")) return false
        return seen == "$expectedPrefix $expectedSeq OK" || seen.startsWith("$expectedPrefix $expectedSeq ERR ")
    }

    /**
     * The Lua server script. Long-running: polls cmd.txt every endFrame,
     * writes results to emu.getScriptDataFolder() (= scriptDataDir).
     *
     * Mesen2 v2.1.1's Lua quirks (verified 2026-06-30 via bisect):
     *  - Single-backslash escape sequences in strings (`\U`, `\A`, `\L`,
     *    `\T`, `\s`) cause fatal parse errors. Use forward slashes.
     *  - Single-line `local function name(params) body end` with `if/then/else`
     *    on the same line is rejected. Use multi-line forms.
     *  - The end-of-frame logic is inlined directly into the onEndFrame
     *    callback — forward-declared globals that an earlier local function
     *    tries to call appear to confuse the parser.
     */
    private fun serverScript(): String {
        val absCmd = cmdFile.toAbsolutePath().toString().replace("\\", "/")
        val absDone = doneFile.toAbsolutePath().toString().replace("\\", "/")
        return """
-- Session server for issue #61: long-running Mesen2 process polled via cmd.txt.
-- Generated by Mesen2ProcessInstance.

local frame = 0
local pending = nil
local nmiThisFrame = 0
local irqThisFrame = 0

local cmdFile = "@@ABS_CMD@@"
local doneFile = "@@ABS_DONE@@"

local function writeMarker(text)
    local f = io.open(doneFile, "w")
    if f then f:write(text); f:close() end
end

local function readCommand()
    local f = io.open(cmdFile, "r")
    if not f then return nil end
    local content = f:read("*l")
    f:close()
    if content then os.remove(cmdFile) end
    return content
end

local function writeState()
    local s = emu.getState()

    local function n(k)
        if s[k] == nil then return 0 else return s[k] end
    end
    local function b(k)
        if s[k] then return 1 else return 0 end
    end
    local function nz(k)
        local v = s[k]
        if type(v) == "number" and v ~= 0 then return 1 else return 0 end
    end

    -- Rebuild PPUCTRL/MASK/STATUS from decomposed getState booleans so they
    -- line up with Nestlin's raw $2000/$2001/$2002.
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

    local function dumpMem(memType, size)
        local parts = {}
        for i = 0, size - 1 do
            parts[#parts + 1] = i .. ":" .. emu.read(i, memType)
        end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    local function dumpPalette()
        local parts = {}
        for i = 0, 31 do
            parts[#parts + 1] = i .. ":" .. n("ppu.paletteRam" .. i)
        end
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

local function writeScreenshot(outpath)
    local ok, data = pcall(emu.takeScreenshot)
    if not ok or not data then error("emu.takeScreenshot failed: " .. tostring(data)) end
    local f = io.open(outpath, "wb")
    if not f then error("could not open " .. outpath .. " for write") end
    f:write(data)
    f:close()
end

local function onNmi()
    nmiThisFrame = nmiThisFrame + 1
end
local function onIrq()
    irqThisFrame = irqThisFrame + 1
end
-- onReset intentionally does NOT clear `pending`: when emu.reset() is called
-- from the RUNTO handler, the reset event fires synchronously and onReset
-- would wipe the just-set pending before the next endFrame can match it.
-- The RUNTO handler resets frame and counters itself after emu.reset().
local function onReset()
    nmiThisFrame = 0
    irqThisFrame = 0
end

-- onEndFrame body is inlined below — Mesen2's Lua parser appears to have
-- trouble with single-line local functions containing if/then/else, and
-- we avoid forward references to globals that aren't yet defined.
local function onEndFrame()
    frame = frame + 1

    if pending and frame == pending.frame then
        local ok, err = pcall(function()
            if pending.type == "STATE" then
                writeState()
            elseif pending.type == "SHOT" then
                writeScreenshot(pending.outpath)
            end
        end)
        if ok then
            writeMarker("DONE " .. pending.seq .. " OK")
        else
            writeMarker("DONE " .. pending.seq .. " ERR " .. tostring(err))
        end
        pending = nil
    end

    -- Reset per-frame interrupt counters AFTER capture so captured counts
    -- reflect only frame N (not frame N+1's early NMIs).
    nmiThisFrame = 0
    irqThisFrame = 0

    local cmd = readCommand()
    if not cmd then return end

    local verb = string.match(cmd, "^%S+")
    if verb == "RESET" then
        emu.reset()
        frame = 0
        pending = nil
        nmiThisFrame = 0
        irqThisFrame = 0
    elseif verb == "QUIT" then
        local seqStr = string.match(cmd, "^QUIT%s+(%S+)")
        local seq = tonumber(seqStr) or -1
        writeMarker("QUIT " .. seq .. " OK")
        emu.stop(0)
    elseif verb == "RUNTO" then
        local seq, target, ctype, outpath = string.match(cmd, "^RUNTO%s+(%S+)%s+(%S+)%s+(%S+)%s*(.*)$")
        seq = tonumber(seq)
        target = tonumber(target)
        if not seq or not target or not ctype then
            writeMarker("RUNTO ERR malformed: " .. cmd)
            return
        end
        -- Always reset before a new capture so the frame counter starts at 0
        -- (matches the per-test-process semantics of the old Mesen2StateCapturer).
        emu.reset()
        frame = 0
        nmiThisFrame = 0
        irqThisFrame = 0
        pending = { seq = seq, frame = target, type = ctype, outpath = outpath }
    else
        writeMarker("ERR unknown verb: " .. tostring(verb))
    end
end

emu.addEventCallback(onNmi, emu.eventType.nmi)
emu.addEventCallback(onIrq, emu.eventType.irq)
emu.addEventCallback(onReset, emu.eventType.reset)
emu.addEventCallback(onEndFrame, emu.eventType.endFrame)

writeMarker("READY")
""".trimIndent()
            .replace("@@ABS_CMD@@", absCmd)
            .replace("@@ABS_DONE@@", absDone)
    }
}
