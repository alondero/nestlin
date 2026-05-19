package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Mesen2 screenshot capture via GUI mode. Setup details live in
 * [Mesen2Process] and `.claude/skills/mesen/SKILL.md`.
 *
 * Will NOT work in headless environments — callers should `assumeTrue` on
 * `isMesen2Available()` and treat thrown [Mesen2ScreenshotException] as a
 * skip rather than a failure.
 */
object Mesen2ReferenceRunner {

    private const val SCRIPT_NAME = "capture"

    fun getMesen2Path(): Path = Mesen2Process.mesen2Path()
    fun isMesen2Available(): Boolean = Mesen2Process.isAvailable()

    fun captureFrame(romPath: Path, frameNumber: Int, outputPath: Path) {
        val outputFile = "screenshot.png"
        val scriptDataDir = Mesen2Process.runScript(
            generateCaptureScript(frameNumber, outputFile),
            romPath,
            SCRIPT_NAME
        )
        val capturedPng = scriptDataDir.resolve(outputFile)
        if (!Files.exists(capturedPng)) {
            throw Mesen2ScreenshotException(
                "Mesen2 script ran but screenshot not found at $capturedPng. " +
                "I/O access may not be enabled."
            )
        }
        Files.createDirectories(outputPath.parent)
        Files.copy(capturedPng, outputPath)
    }

    private fun generateCaptureScript(targetFrame: Int, outputFile: String): String {
        // emu.getScriptDataFolder() returns no trailing separator on Windows;
        // without the fixup, basePath .. outputFile writes to a sibling file.
        return """
local targetFrame = $targetFrame
local outputFile = "$outputFile"
local frame = 0

function onEndFrame()
    frame = frame + 1
    if frame == targetFrame then
        local data = emu.takeScreenshot()
        local basePath = emu.getScriptDataFolder()
        local last = string.sub(basePath, -1)
        if last ~= "\\" and last ~= "/" then
            basePath = basePath .. "\\"
        end
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
}

class Mesen2NotFoundException(message: String) : RuntimeException(message)
class Mesen2ExecutionException(message: String) : RuntimeException(message)
class Mesen2ScreenshotException(message: String) : RuntimeException(message)
