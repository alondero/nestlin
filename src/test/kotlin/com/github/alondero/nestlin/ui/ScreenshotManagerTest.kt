package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

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

        assertThat(Files.exists(savedPath), equalTo(true))
    }
}
