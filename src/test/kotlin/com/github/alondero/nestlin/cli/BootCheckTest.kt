package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.testutil.TestRoms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests for the oracle-free `bootcheck` verdict. The grading branches are exercised purely
 * (no ROM needed) via [BootCheck.gradeSignals]; one end-to-end case boots the only in-git ROM
 * (nestest.nes) to prove the full load→run→report path works headlessly.
 */
class BootCheckTest {

    // --- pure grading: every PASS/WARN/FAIL branch, deterministic, no emulator ---

    @Test
    fun `FAIL when never rendered and screen is blank`() {
        val (grade, reasons) = BootCheck.gradeSignals(
            renderingEnabledByFrame = null, maxNonBlankRatio = 0.0,
            distinctPrgStates = 1, distinctChrStates = 1, distinctFramePCs = 5,
        )
        assertEquals(BootCheck.Grade.FAIL, grade)
        assertTrue(reasons.single().contains("did not boot to a picture"))
    }

    @Test
    fun `PASS when rendered, drawn, banks moved, PC advancing`() {
        val (grade, reasons) = BootCheck.gradeSignals(
            renderingEnabledByFrame = 8, maxNonBlankRatio = 0.42,
            distinctPrgStates = 3, distinctChrStates = 4, distinctFramePCs = 40,
        )
        assertEquals(BootCheck.Grade.PASS, grade)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `WARN when rendering on but screen near-empty (CHR-bank smell)`() {
        val (grade, reasons) = BootCheck.gradeSignals(
            renderingEnabledByFrame = 10, maxNonBlankRatio = 0.005,
            distinctPrgStates = 2, distinctChrStates = 2, distinctFramePCs = 30,
        )
        assertEquals(BootCheck.Grade.WARN, grade)
        assertTrue(reasons.any { it.contains("almost empty") })
    }

    @Test
    fun `WARN (not FAIL) when blank but rendering WAS enabled`() {
        // A blank screen on its own is only a FAIL when rendering also never armed; if the game
        // armed rendering we down-grade to WARN so a legitimately-dark early frame isn't a hard fail.
        val (grade, _) = BootCheck.gradeSignals(
            renderingEnabledByFrame = 3, maxNonBlankRatio = 0.0,
            distinctPrgStates = 2, distinctChrStates = 1, distinctFramePCs = 20,
        )
        assertEquals(BootCheck.Grade.WARN, grade)
    }

    @Test
    fun `WARN notes a wedged PC`() {
        val (grade, reasons) = BootCheck.gradeSignals(
            renderingEnabledByFrame = 5, maxNonBlankRatio = 0.3,
            distinctPrgStates = 2, distinctChrStates = 2, distinctFramePCs = 1,
        )
        assertEquals(BootCheck.Grade.WARN, grade)
        assertTrue(reasons.any { it.contains("wedged") })
    }

    // --- CLI parsing ---

    @Test
    fun `usage error when no rom given`() {
        val out = StringBuilder()
        assertEquals(BootCheck.EXIT_USAGE, BootCheckCli.main(emptyList(), out))
        assertTrue(out.contains("usage:"))
    }

    @Test
    fun `usage error on non-integer frames`() {
        val out = StringBuilder()
        assertEquals(BootCheck.EXIT_USAGE, BootCheckCli.main(listOf("x.nes", "--frames", "lots"), out))
    }

    // --- end-to-end on the only in-git ROM ---

    @Test
    fun `boots nestest end-to-end and reports a verdict`() {
        val rom = TestRoms.nestestPath()
        assumeTrue(Files.exists(rom), "nestest.nes not found at $rom")

        val out = StringBuilder()
        val verdict = BootCheck.run(BootCheck.Options(rom, frames = 30), out)

        // We don't assert PASS — nestest runs in CPU-automation mode and never enables rendering,
        // so it legitimately grades FAIL/WARN. What we prove is the headless path itself works:
        // a mapper loaded, nothing threw, frames ran, and the greppable verdict block is emitted.
        assertTrue(verdict.loaded, "expected a mapper to load for nestest")
        assertFalse(verdict.threw, "nestest should not throw: ${verdict.threwMessage}")
        assertEquals("Mapper0", verdict.mapperName)
        assertEquals(30, verdict.framesRun)
        assertTrue(out.contains("BOOTCHECK VERDICT:"), "report must contain the greppable verdict line")
    }
}
