package com.github.alondero.nestlin.compare

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Captures CPU, PPU, and memory state from Mesen2 at a specific frame
 * boundary. The actual Mesen2 invocation is delegated to [Mesen2Session]
 * (issue #61) which reuses a long-running Mesen2 process per ROM.
 *
 * The legacy one-process-per-call implementation is preserved here as the
 * `Mesen2StateJsonParser` extract so the JSON format is unchanged for
 * Phase 2; Phase 3 of the testing strategy (`docs/TESTING_STRATEGY.md:197-203`)
 * plans a binary-blob rewrite.
 *
 * Test code that wants the historical shape can keep calling
 * `Mesen2StateCapturer.captureState(rom, frame)` — the signature is
 * unchanged. Under the hood it now boots a single Mesen2 process the first
 * time it's called for a given ROM and reuses it for every subsequent
 * capture on that ROM (across the whole test JVM).
 */
object Mesen2StateCapturer {

    /**
     * Resolved Mesen2 executable path. Kept as a public method for
     * backwards compat with `RequiresMesen2Condition` (`RequiresMesen2.kt:38`)
     * and any test that calls `Mesen2StateCapturer.getMesen2Path()` directly.
     * Delegates to the canonical [Mesen2Session.mesen2Path].
     */
    fun getMesen2Path(): Path = Mesen2Session.mesen2Path()

    /**
     * True if Mesen2 is available at the resolved path. Delegates to
     * [Mesen2Session.isAvailable] so all four callers
     * (`Mesen2StateCapturer.isMesen2Available`, `Mesen2ReferenceRunner.isMesen2Available`,
     * `RequiresMesen2Condition`, manual `assumeTrue` in tests) agree on
     * the answer.
     */
    fun isMesen2Available(): Boolean = Mesen2Session.isAvailable()

    /**
     * Capture state at [frameNumber] for [romPath]. Signature unchanged
     * from the legacy implementation — all existing test call sites
     * (`StateComparisonTest`, `GxRomStateComparisonTest`,
     * `MicroMachinesMapper71StateComparisonTest`, every Mapper*RegressionTest
     * via `MapperRegressionTestBase`, etc.) keep working unmodified.
     */
    fun captureState(romPath: Path, frameNumber: Int): EmulatorStateSnapshot =
        Mesen2Session.forRom(romPath).runToAndCaptureState(frameNumber)
}
