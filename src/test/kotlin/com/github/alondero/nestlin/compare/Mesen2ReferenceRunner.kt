package com.github.alondero.nestlin.compare

import java.nio.file.Path

/**
 * Mesen2 screenshot capture, delegated to [Mesen2Session] (issue #61).
 *
 * The legacy implementation directly spawned `Mesen.exe --testRunner` per
 * call. The new path goes through the long-running session pool: one
 * process per unique ROM, shared across the whole test JVM.
 *
 * The exception classes declared at the bottom of this file remain the
 * canonical Mesen2-failure types — `Mesen2ProcessInstance` throws them.
 */
object Mesen2ReferenceRunner {

    /** Canonical Mesen2 executable path. Delegates to [Mesen2Session.mesen2Path]. */
    fun getMesen2Path(): Path = Mesen2Session.mesen2Path()

    /** True if Mesen2 is available. Delegates to [Mesen2Session.isAvailable]. */
    fun isMesen2Available(): Boolean = Mesen2Session.isAvailable()

    /**
     * Capture a PNG screenshot of [romPath] at [frameNumber], copying it
     * to [outputPath]. Signature unchanged — `ScreenshotComparisonTest`
     * and `Mapper64KlaxMesen2ScreenshotTest` call this without changes.
     */
    fun captureFrame(romPath: Path, frameNumber: Int, outputPath: Path) {
        Mesen2Session.forRom(romPath).runToAndCaptureScreenshot(frameNumber, outputPath)
    }
}

class Mesen2NotFoundException(message: String) : RuntimeException(message)
class Mesen2ExecutionException(message: String) : RuntimeException(message)
class Mesen2ScreenshotException(message: String) : RuntimeException(message)
