package com.github.alondero.nestlin.compare

import org.junit.Assert
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Captures screenshots from ROMs specified via environment variables.
 *
 * Configuration (environment variables):
 *   NESTLIN_SCREENSHOT_ROMS  - comma-separated list of ROM paths (required)
 *   NESTLIN_SCREENSHOT_FRAME - frame number to capture (default: 60)
 *   NESTLIN_SCREENSHOT_OUTPUT - output directory (default: build/reports/mapper-screenshots)
 *
 * Usage:
 *   export NESTLIN_SCREENSHOT_ROMS="/path/to/rom1.nes,/path/to/rom2.nes"
 *   ./gradlew test --tests "ScreenshotCapture" --no-daemon
 */
class ScreenshotCapture {

    companion object {
        private const val ENV_ROMS = "NESTLIN_SCREENSHOT_ROMS"
        private const val ENV_FRAME = "NESTLIN_SCREENSHOT_FRAME"
        private const val ENV_OUTPUT = "NESTLIN_SCREENSHOT_OUTPUT"

        private const val DEFAULT_FRAME = 60
        private val defaultOutput = Paths.get("build/reports/mapper-screenshots")

        private fun getEnvOrDefault(envName: String, default: String): String =
            System.getenv(envName) ?: default

        fun getRoms(): List<String> {
            val prop = System.getenv(ENV_ROMS) ?: return emptyList()
            return prop.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun getFrame(): Int = (System.getenv(ENV_FRAME) ?: DEFAULT_FRAME.toString()).toInt()

        fun getOutputDir(): Path = Paths.get(getEnvOrDefault(ENV_OUTPUT, defaultOutput.toString()))
    }

    @Test
    fun captureScreenshots() {
        val roms = getRoms()
        Assert.assertFalse("No ROMs specified. Set NESTLIN_SCREENSHOT_ROMS.", roms.isEmpty())

        val frame = getFrame()
        val outputDir = getOutputDir()

        println("=== Screenshot Capture ===")
        println("ROMs: ${roms.size}")
        println("Frame: $frame")
        println("Output: $outputDir")
        println()

        val results = mutableListOf<String>()
        for (rom in roms) {
            val inputPath = Paths.get(rom.trim())
            val fileName = inputPath.fileName.toString().replace(".nes", "").replace(" ", "-")
            val outputPath = outputDir.resolve("screenshot_${fileName}_frame$frame.png")

            print("Capturing: ${inputPath.fileName} ... ")
            try {
                NestlinHeadlessRunner.captureFrame(inputPath, frame, outputPath)
                println("OK")
                results.add("✓ $rom")
            } catch (e: Exception) {
                println("FAILED: ${e.message ?: e.javaClass.simpleName}")
                results.add("✗ $rom")
            }
        }

        println("\n=== Summary ===")
        results.forEach { println(it) }
        val passed = results.count { it.startsWith("✓") }
        Assert.assertTrue("Some screenshots failed: $results", passed == results.size)
    }
}