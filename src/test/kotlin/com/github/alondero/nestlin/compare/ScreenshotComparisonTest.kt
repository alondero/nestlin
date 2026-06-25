package com.github.alondero.nestlin.compare

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths

/**
 * Pixel-diff vs Mesen2 across a small smoke set (Tetris/Lolo1/Kirby).
 *
 * Lane membership + Mesen2 availability are gated by [RequiresMesen2]: the test
 * is excluded from `./gradlew test` (tag-inherited), included by
 * `./gradlew testMesenComparison`, and SKIPs loudly with the resolved Mesen2 path
 * when Mesen2 is absent (or hard-FAILs when `NESTLIN_REQUIRE_MESEN2` is set —
 * see `RequiresMesen2Condition`). Mesen2-execution errors mid-capture
 * (`Mesen2ScreenshotException`, `Mesen2ExecutionException`) propagate as hard
 * FAILs instead of being swallowed into SKIPPED — those are real harness
 * failures (broken `MESEN2_PATH`, missing I/O permissions, Mesen2 non-zero
 * exit), not "no oracle" situations, and they must surface loudly (GH #44).
 */
@RequiresMesen2
class ScreenshotComparisonTest {

    companion object {
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
        val romPath = Paths.get("testroms/$romName")
        val reportsDir = Paths.get("build/reports/screenshot-diffs/$romName-frame-$frameNumber")
        val nestlinPng = reportsDir.resolve("nestlin.png")
        val mesen2Png = reportsDir.resolve("mesen2.png")
        val diffPng = reportsDir.resolve("diff.png")

        NestlinHeadlessRunner.captureFrame(romPath, frameNumber, nestlinPng)

        // Let Mesen2ScreenshotException / Mesen2ExecutionException propagate as
        // hard FAILs. These are real harness errors — a missing screenshot file,
        // a Mesen2 non-zero exit, a missing display — and the GH #44 issue is
        // explicit that they must NOT be converted to SKIPPED.
        Mesen2ReferenceRunner.captureFrame(romPath, frameNumber, mesen2Png)

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
