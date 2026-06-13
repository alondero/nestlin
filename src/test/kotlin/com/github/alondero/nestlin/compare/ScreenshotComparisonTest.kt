package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths

@org.junit.jupiter.api.Tag("mesen")
class ScreenshotComparisonTest {

    companion object {
        private const val MESEN2_SKIP_MESSAGE = "Mesen2 not available or screenshot capture not supported"

        @JvmStatic
        fun data(): List<Arguments> = listOf(
            Arguments.of("tetris.nes", 60, 0.0),
            Arguments.of("lolo1.nes", 60, 0.0),
            Arguments.of("kirby.nes", 150, 20.0)
        )
    }

    @ParameterizedTest(name = "{0} @ frame {1}")
    @MethodSource("data")
    fun compareFrames(romName: String, frameNumber: Int, threshold: Double) {
        assumeTrue(Mesen2ReferenceRunner.isMesen2Available(), MESEN2_SKIP_MESSAGE)

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
            assumeTrue(false, e.message)
        } catch (e: Mesen2ExecutionException) {
            // Mesen2 crashed or couldn't run (missing, permissions, etc.)
            // Skip rather than fail.
            assumeTrue(false, e.message)
        }

        val result = diff(nestlinPng, mesen2Png, threshold)

        println("$romName frame $frameNumber: ${result.matchPercentage}% match (${result.mismatchedPixels}/${result.totalPixels} pixels differ)")

        if (!result.match) {
            writeDiffImage(nestlinPng, mesen2Png, diffPng)
            // Extract the message to a String-typed local var to disambiguate
            // JUnit 5's fail(Supplier<String>) overload (which has a phantom
            // <V> type variable the Kotlin compiler cannot infer).
            val failMessage = "Frame mismatch for $romName at frame $frameNumber: " +
                "${result.mismatchedPixels}/${result.totalPixels} pixels differ " +
                "(${result.matchPercentage}% match, threshold requires ${100.0 - threshold}%). " +
                "First mismatch at ${result.firstMismatch}. " +
                "See diff at: $diffPng"
            // JUnit 5's Assertions.fail(Supplier<String>) has a phantom <V>
            // type variable that breaks Kotlin's overload resolution for the
            // plain-String form. Bypass by throwing AssertionFailedError
            // directly — that's what fail(String) does internally anyway.
            throw org.opentest4j.AssertionFailedError(failMessage)
        }
    }
}
