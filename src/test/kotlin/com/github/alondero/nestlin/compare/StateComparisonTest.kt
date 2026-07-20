package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Parameterized test comparing Nestlin vs Mesen2 state at specific frames.
 * Uses state-snapshot comparison instead of screenshots (no display required for state capture).
 *
 * Migrated to JUnit 5: the class no longer takes a constructor (the parameters
 * are method parameters now), and the data source is a `List<Arguments>`
 * discovered by `@MethodSource("data")`. The test name template moves from
 * `@Parameterized.Parameters(name = ...)` to `@ParameterizedTest(name = ...)`.
 *
 * Disabled because the available frame hooks are not synchronized: Mesen2 captures
 * at scanline 240 before NMI, while Nestlin captures at scanline 261 after NMI.
 * Mapper regression tests compare the stable render-output subset instead.
 */
@Disabled("Cross-emulator full state is captured at different points in the frame")
@org.junit.jupiter.api.Tag("mesen")
class StateComparisonTest {

    companion object {
        @JvmStatic
        fun data(): List<Arguments> = listOf(
            Arguments.of("tetris.nes", 60),     // Mapper 0 (NROM)
            Arguments.of("lolo1.nes", 60),      // Mapper 1 (MMC1)
            Arguments.of("kirby.nes", 150)      // Mapper 4 (MMC3)
        )
    }

    @ParameterizedTest(name = "{0} @ frame {1}")
    @MethodSource("data")
    fun compareStates(romName: String, frameNumber: Int) {
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")

        val romPath = Paths.get("testroms/$romName")
        val reportsDir = Paths.get("build/reports/state-diffs/$romName-frame-$frameNumber")

        // Capture state from both emulators
        val nestlinState = NestlinStateCapturer.captureState(romPath, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(romPath, frameNumber)

        // Write JSON snapshots for human inspection
        val nestlinJsonPath = reportsDir.resolve("nestlin-state.json")
        val mesen2JsonPath = reportsDir.resolve("mesen2-state.json")

        Files.createDirectories(nestlinJsonPath.parent)
        Files.writeString(nestlinJsonPath, nestlinState.toJson())
        Files.writeString(mesen2JsonPath, mesen2State.toJson())

        // Compare states
        val diff = StateComparator.compare(nestlinState, mesen2State)

        println("$romName frame $frameNumber: ${if (diff.match) "MATCH" else "MISMATCH"}")
        if (!diff.match) {
            println(diff.message)
        }

        // Write diff report
        val diffReportPath = reportsDir.resolve("diff-report.txt")
        StateComparator.writeReport(diff, nestlinState, mesen2State, diffReportPath)

        if (!diff.match) {
            // Extract the message to a String-typed local var. The `diff.message`
            // is a Java platform type (String!), and the `+` concat confuses the
            // compiler when picking between Assertions.fail(String) and
            // Assertions.fail(Supplier<String>). An explicit String binding fixes
            // the inference without changing runtime behaviour.
            val failMessage: String = diff.message + "\nSee: $diffReportPath"
            // JUnit 5's Assertions.fail(Supplier<String>) has a phantom <V>
            // type variable that breaks Kotlin's overload resolution for the
            // plain-String form. Bypass by throwing AssertionFailedError
            // directly — that's what fail(String) does internally anyway.
            throw org.opentest4j.AssertionFailedError(failMessage)
        }
    }
}
