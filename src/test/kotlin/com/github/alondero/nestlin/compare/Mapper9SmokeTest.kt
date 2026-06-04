package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Headless smoke test for Mapper 9 (MMC2/Punch-Out!!).
 * Uses NESTLIN_SCREENSHOT_ROMS, NESTLIN_SCREENSHOT_FRAME, NESTLIN_SCREENSHOT_OUTPUT.
 */
class Mapper9SmokeTest {

    companion object {
        private const val ENV_ROMS = "NESTLIN_SCREENSHOT_ROMS"
        private const val ENV_FRAME = "NESTLIN_SCREENSHOT_FRAME"
        private const val ENV_OUTPUT = "NESTLIN_SCREENSHOT_OUTPUT"
        private const val DEFAULT_FRAME = 60
        private val defaultOutput = Paths.get("build/reports/mapper-screenshots")

        fun getRoms(): List<String> {
            val prop = System.getenv(ENV_ROMS) ?: return emptyList()
            return prop.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        fun getFrame(): Int = (System.getenv(ENV_FRAME) ?: DEFAULT_FRAME.toString()).toInt()
        fun getOutputDir(): Path = Paths.get(System.getenv(ENV_OUTPUT) ?: defaultOutput.toString())
    }

    @Test
    fun captureMapper9Screenshots() {
        val roms = getRoms()
        if (roms.isEmpty()) {
            println("NESTLIN_SCREENSHOT_ROMS not set — skipping Mapper9SmokeTest")
            return
        }
        val frame = getFrame()
        val outputDir = getOutputDir()
        Files.createDirectories(outputDir)

        for (rom in roms) {
            val inputPath = Paths.get(rom.trim())
            val fileName = inputPath.fileName.toString().replace(".nes", "").replace(" ", "-")
            val outputPath = outputDir.resolve("${fileName}_frame${frame}.png")

            println("=== Mapper9SmokeTest ===")
            println("ROM: $inputPath")
            println("Frame: $frame")
            println("Output: $outputPath")

            var frameCount = 0
            val targetFrame = frame
            val nestlin = Nestlin().apply {
                config.speedThrottlingEnabled = false
                load(inputPath)
                addFrameListener(object : FrameListener {
                    override fun frameUpdated(f: Frame) {
                        if (++frameCount == targetFrame) {
                            saveFrameAsPng(f, outputPath)
                            stop()
                        }
                    }
                })
            }
            nestlin.powerReset()
            nestlin.start()

            // Wait for capture
            Thread.sleep(2000)
            println("Saved: $outputPath")
            println()
        }
    }

    private fun saveFrameAsPng(frame: Frame, outputPath: Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        Files.createDirectories(outputPath.parent)
        ImageIO.write(image, "PNG", outputPath.toFile())
    }
}