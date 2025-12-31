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
        ScreenshotManager(screenshotsDir)

        assertThat(Files.exists(screenshotsDir), equalTo(true))
    }
}
