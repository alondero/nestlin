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
}
