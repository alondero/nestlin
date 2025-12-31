package com.github.alondero.nestlin.ui

import java.nio.file.Files
import java.nio.file.Path

class ScreenshotManager(private val screenshotsDir: Path) {
    init {
        Files.createDirectories(screenshotsDir)
    }
}
