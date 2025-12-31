package com.github.alondero.nestlin.ui

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Manages screenshot capture and file I/O operations.
 * Ensures the screenshots directory exists on initialization.
 */
class ScreenshotManager(private val screenshotsDir: Path) {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    init {
        Files.createDirectories(screenshotsDir)
    }

    fun generateScreenshotFilename(): String {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        return "screenshot-$timestamp.png"
    }

    fun saveScreenshot(frameBuffer: ByteArray, width: Int, height: Int): Path {
        // Create a BufferedImage from the frame buffer
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

        // Convert byte array (RGB) to BufferedImage pixels (int RGB)
        var byteIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = frameBuffer[byteIndex++].toInt() and 0xFF
                val g = frameBuffer[byteIndex++].toInt() and 0xFF
                val b = frameBuffer[byteIndex++].toInt() and 0xFF

                val rgb = (r shl 16) or (g shl 8) or b
                image.setRGB(x, y, rgb)
            }
        }

        // Save to file
        val filename = generateScreenshotFilename()
        val filepath = screenshotsDir.resolve(filename)
        ImageIO.write(image, "PNG", filepath.toFile())

        return filepath
    }
}
