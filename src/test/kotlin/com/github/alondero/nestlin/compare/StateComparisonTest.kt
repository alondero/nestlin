package com.github.alondero.nestlin.compare

import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Parameterized test comparing Nestlin vs Mesen2 state at specific frames.
 * Uses state-snapshot comparison instead of screenshots (no display required for state capture).
 */
@RunWith(Parameterized::class)
class StateComparisonTest(
    private val romName: String,
    private val frameNumber: Int
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data(): Array<Array<Any>> {
            return arrayOf(
                arrayOf("tetris.nes", 60),      // Mapper 0 (NROM)
                arrayOf("lolo1.nes", 60),       // Mapper 1 (MMC1)
                arrayOf("kirby.nes", 150)        // Mapper 4 (MMC3)
            )
        }
    }

    @Test
    fun compareStates() {
        assumeTrue("Mesen2 not available", Mesen2StateCapturer.isMesen2Available())

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
            Assert.fail(diff.message + "\nSee: $diffReportPath")
        }
    }
}
