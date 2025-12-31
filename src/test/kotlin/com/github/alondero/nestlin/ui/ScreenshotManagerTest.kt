package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.imageio.ImageIO

class ScreenshotManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `should create screenshots directory if it does not exist`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        // Instantiate to trigger directory creation in init block
        @Suppress("UNUSED_VARIABLE")
        val manager = ScreenshotManager(screenshotsDir)

        assertThat(Files.exists(screenshotsDir), equalTo(true))
    }

    @Test
    fun `should be idempotent when directory already exists`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        // Create once
        ScreenshotManager(screenshotsDir)

        // Create again - should not fail
        @Suppress("UNUSED_VARIABLE")
        val manager = ScreenshotManager(screenshotsDir)

        assertThat(Files.exists(screenshotsDir), equalTo(true))
    }

    @Test
    fun `should generate screenshot filename with timestamp`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        val manager = ScreenshotManager(screenshotsDir)

        val filename = manager.generateScreenshotFilename()
        assert(filename.contains("screenshot-"))
        assert(filename.contains(".png"))
    }

    @Test
    fun `should save frame buffer as PNG file`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        val manager = ScreenshotManager(screenshotsDir)

        // Create a simple test frame buffer: 4x4 pixels, RGB (3 bytes per pixel)
        // All red: R=255, G=0, B=0
        val testFrame = ByteArray(4 * 4 * 3) { i ->
            when (i % 3) {
                0 -> 255.toByte()  // R channel
                else -> 0.toByte() // G and B channels
            }
        }

        val savedPath = manager.saveScreenshot(testFrame, width = 4, height = 4)

        // Verify file exists
        assertThat(Files.exists(savedPath), equalTo(true))

        // Verify file is a valid PNG and has correct dimensions
        val readImage = ImageIO.read(savedPath.toFile())
        assertThat(readImage.width, equalTo(4))
        assertThat(readImage.height, equalTo(4))

        // Verify pixel data: first pixel should be red (0xFFFF0000)
        val pixelColor = readImage.getRGB(0, 0)
        assertThat(pixelColor, equalTo(0xFFFF0000.toInt()))
    }

    @Test
    fun `should throw exception for invalid dimensions`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        val manager = ScreenshotManager(screenshotsDir)

        val testFrame = ByteArray(12)  // 2x2x3 bytes

        val exception = try {
            manager.saveScreenshot(testFrame, width = 0, height = 2)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assert(exception != null)
        assert(exception!!.message?.contains("positive") == true)
    }

    @Test
    fun `should throw exception for mismatched buffer size`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        val manager = ScreenshotManager(screenshotsDir)

        val testFrame = ByteArray(12)  // 12 bytes, but we're claiming 4x4 (should be 48)

        val exception = try {
            manager.saveScreenshot(testFrame, width = 4, height = 4)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assert(exception != null)
        assert(exception!!.message?.contains("width × height × 3") == true)
    }
}
