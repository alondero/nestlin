package com.github.alondero.nestlin.ui

import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages screenshot capture and file I/O operations.
 * Ensures the screenshots directory exists on initialization.
 */
class ScreenshotManager(private val screenshotsDir: Path) {
    init {
        Files.createDirectories(screenshotsDir)
    }
}
