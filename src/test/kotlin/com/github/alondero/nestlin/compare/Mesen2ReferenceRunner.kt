package com.github.alondero.nestlin.compare

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Mesen2 screenshot capture via --testRunner (headless).
 *
 * Uses: `Mesen.exe --testRunner --doNotSaveSettings rom.nes script.lua`
 *
 * No display required; emu.takeScreenshot() reads from the in-memory PPU
 * framebuffer. Phase 0 spike (2026-05-20) confirmed 30 frames + screenshot
 * complete in ~0.5s vs ~10s in the prior GUI invocation.
 */
object Mesen2ReferenceRunner {

    private const val ENV_VAR = "MESEN2_PATH"
    private val MESEN_ARGS = listOf("--testRunner", "--doNotSaveSettings")

    fun getMesen2Path(): Path {
        val path = System.getenv(ENV_VAR) ?: System.getProperty("mesen2.path")
        return path?.let { Paths.get(it) } ?: Paths.get("tools/Mesen/Mesen.exe")
    }

    fun isMesen2Available(): Boolean = getMesen2Path().toFile().exists()

    fun captureFrame(romPath: Path, frameNumber: Int, outputPath: Path) {
        val mesenPath = getMesen2Path()
        val mesenDir = mesenPath.parent.toFile()

        // Create a unique temp directory for this run's script
        val runDir = Files.createTempDirectory("mesen_capture_")
        val scriptPath = runDir.resolve("capture.lua")

        // Generate the Lua script using the correct GUI-mode API
        val luaScript = generateCaptureScript(frameNumber, "screenshot.png")
        Files.writeString(scriptPath, luaScript)

        try {
            // GUI mode: Mesen.exe script.lua rom.nes
            // The script auto-exits via os.exit() after capturing
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

            // Script wrote to getScriptDataFolder() + "screenshot.png"
            // Copy to the requested outputPath
            val scriptDataPath = guessScriptDataFolder().resolve("screenshot.png")
            if (Files.exists(scriptDataPath)) {
                Files.createDirectories(outputPath.parent)
                Files.copy(scriptDataPath, outputPath)
            } else {
                throw Mesen2ScreenshotException(
                    "Mesen2 script ran but screenshot not found at $scriptDataPath. " +
                    "I/O access may not be enabled."
                )
            }
        } finally {
            // Cleanup temp script directory
            scriptPath.toFile().delete()
            runDir.toFile().deleteRecursively()
        }
    }

    private fun generateCaptureScript(targetFrame: Int, outputFile: String): String {
        // --testRunner mode: emu.stop(code) exits cleanly with that exit code.
        // getScriptDataFolder() returns NO trailing separator — must insert "/".
        return """
local targetFrame = $targetFrame
local outputFile = "$outputFile"
local frame = 0

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        local ok, err = pcall(function()
            local data = emu.takeScreenshot()
            local fullPath = emu.getScriptDataFolder() .. "/" .. outputFile
            local f = io.open(fullPath, "wb")
            if f then f:write(data); f:close() end
        end)
        if not ok then
            print("capture error: " .. tostring(err))
            emu.stop(2)
        else
            emu.stop(0)
        end
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
    }

    private fun guessScriptDataFolder(): Path {
        // Mesen2 writes to: <mesen_dir>/LuaScriptData/<script_name>/
        // NOT %APPDATA%/Sourcen/Mesen/ (that's Mesen v1, not v2)
        val mesenPath = getMesen2Path()
        return mesenPath.parent.resolve("LuaScriptData").resolve("capture")
    }
}

class Mesen2NotFoundException(message: String) : RuntimeException(message)
class Mesen2ExecutionException(message: String) : RuntimeException(message)
class Mesen2ScreenshotException(message: String) : RuntimeException(message)