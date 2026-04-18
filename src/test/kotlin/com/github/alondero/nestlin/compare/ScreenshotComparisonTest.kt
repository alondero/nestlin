package com.github.alondero.nestlin.compare

import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Paths

@RunWith(Parameterized::class)
class ScreenshotComparisonTest(
    private val romName: String,
    private val frameNumber: Int
) {

    companion object {
        private const val MESEN2_SKIP_MESSAGE = "Mesen2 not available or screenshot capture not supported"

        @JvmStatic
        @Parameterized.Parameters(name = "{0} frame {1}")
        fun data(): List<Array<Any>> {
            return listOf(
                arrayOf("tetris.nes", 60),
                arrayOf("lolo1.nes", 60)
            )
        }
    }

    @Test
    fun compareFrames() {
        assumeTrue(MESEN2_SKIP_MESSAGE, Mesen2ReferenceRunner.isMesen2Available())

        val romPath = Paths.get("testroms/$romName")
        val reportsDir = Paths.get("build/reports/screenshot-diffs/$romName-frame-$frameNumber")
        val nestlinPng = reportsDir.resolve("nestlin.png")
        val mesen2Png = reportsDir.resolve("mesen2.png")
        val diffPng = reportsDir.resolve("diff.png")

        NestlinHeadlessRunner.captureFrame(romPath, frameNumber, nestlinPng)

        try {
            Mesen2ReferenceRunner.captureFrame(romPath, frameNumber, mesen2Png)
        } catch (e: Mesen2ScreenshotException) {
            // Screenshot capture failed (I/O access disabled, no display, etc.)
            // Skip rather than fail - this is an environment limitation, not a code bug.
            assumeTrue(e.message, false)
        } catch (e: Mesen2ExecutionException) {
            // Mesen2 crashed or couldn't run (missing, permissions, etc.)
            // Skip rather than fail.
            assumeTrue(e.message, false)
        }

        val result = diff(nestlinPng, mesen2Png)

        if (!result.match) {
            writeDiffImage(nestlinPng, mesen2Png, diffPng)
            Assert.fail(
                "Frame mismatch for $romName at frame $frameNumber: " +
                "${result.mismatchedPixels}/${result.totalPixels} pixels differ. " +
                "First mismatch at ${result.firstMismatch}. " +
                "See diff at: $diffPng"
            )
        }
    }
}
