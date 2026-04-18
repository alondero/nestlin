package com.github.alondero.nestlin.compare

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Mesen2 screenshot capture via GUI mode.
 *
 * Uses: `Mesen.exe script.lua rom.nes` (NOT --loadlua or --testRunner)
 *
 * Requires:
 * 1. A display attached (Mesen is a GUI application)
 * 2. I/O access enabled: Script → Settings → Script Window → Restrictions → Allow access to I/O and OS functions
 *
 * The PPU renders in GUI mode, enabling valid screenshot capture via emu.takeScreenshot().
 * This will NOT work in headless environments - use assumeTrue checks or skip tests.
 */
object Mesen2ReferenceRunner {

    private const val ENV_VAR = "MESEN2_PATH"
    private val MESEN_ARGS = listOf("--doNotSaveSettings")

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
        // GUI mode: use emu.eventType.endFrame callback, NOT frameadvance loop
        return """
local targetFrame = $targetFrame
local outputFile = "$outputFile"
local frame = 0

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        local data = emu.takeScreenshot()
        local basePath = emu.getScriptDataFolder()
        local fullPath = basePath .. outputFile
        local f = io.open(fullPath, "wb")
        if f then
            f:write(data)
            f:close()
        end
        os.exit()
    end
end

emu.addEventCallback(onEndFrame, emu.eventType.endFrame)
""".trimIndent()
    }

    private fun guessScriptDataFolder(): Path {
        // On Windows: %APPDATA%\Sourcen\Mesen\LuaScriptData\<scriptname>\
        val appData = System.getenv("APPDATA") ?: return Paths.get(".")
        return Paths.get(appData, "Sourcen", "Mesen", "LuaScriptData")
    }
}

class Mesen2NotFoundException(message: String) : RuntimeException(message)
class Mesen2ExecutionException(message: String) : RuntimeException(message)
class Mesen2ScreenshotException(message: String) : RuntimeException(message)
