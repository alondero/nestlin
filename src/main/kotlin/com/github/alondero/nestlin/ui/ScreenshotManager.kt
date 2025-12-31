package com.github.alondero.nestlin.ui

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
}
