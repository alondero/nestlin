package com.github.alondero.nestlin.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [RaBench]'s CLI shape and CLI-parsing logic.
 *
 * Like [NativeRaSmokeTest], these tests run in the default
 * `./gradlew test` suite — they don't require the native library or
 * a ROM on disk. The actual benchmark (which boots a ROM + ticks N
 * frames) is exercised via `java -jar nestlin-all.jar ra-bench`.
 */
class RaBenchTest {

    @Test
    fun `USAGE mentions every documented CLI option`() {
        val usage = RaBench.USAGE
        assertTrue(usage.contains("--rom"), "USAGE must mention --rom: $usage")
        assertTrue(usage.contains("--frames"), "USAGE must mention --frames: $usage")
        assertTrue(usage.contains("--warmup"), "USAGE must mention --warmup: $usage")
        // Budget semantics must be visible.
        assertTrue(usage.contains("p95") || usage.contains("P95"),
            "USAGE must mention p95: $usage")
    }

    @Test
    fun `Cli rejects missing --rom`() {
        val out = StringBuilder()
        val rc = RaBenchCli.main(emptyList(), out)
        assertEquals(2, rc, "missing --rom must exit 2")
        assertTrue(out.toString().contains("--rom is required"),
            "missing-required-arg error must mention --rom: $out")
    }

    @Test
    fun `Cli rejects non-existent --rom path`() {
        val out = StringBuilder()
        val rc = RaBenchCli.main(listOf("--rom", "/no/such/path.nes"), out)
        assertEquals(2, rc, "non-existent ROM must exit 2")
        assertTrue(out.toString().contains("does not exist"),
            "missing-path error must mention 'does not exist': $out")
    }

    @Test
    fun `Cli rejects negative --frames`() {
        val out = StringBuilder()
        val rc = RaBenchCli.main(listOf("--rom", "x.nes", "--frames", "-1"), out)
        assertEquals(2, rc, "negative --frames must exit 2")
    }

    @Test
    fun `Cli rejects non-numeric --warmup`() {
        val out = StringBuilder()
        val rc = RaBenchCli.main(listOf("--rom", "x.nes", "--warmup", "abc"), out)
        assertEquals(2, rc, "non-numeric --warmup must exit 2")
    }

    @Test
    fun `Result meetsBudget is false only when native + breaches`() {
        // NoOp / Fake: meetsBudget is true (no measurements, no breaches).
        val noop = RaBench.Result(
            frames = 100, totalMillis = 100, framesPerSecond = 1000.0,
            p95Micros = 0.0, budgetBreaches = 0,
            silentReadsMeasured = 0L, usingNativeService = false,
        )
        assertTrue(noop.meetsBudget)

        // Native with no breaches: meetsBudget.
        val nativeOk = noop.copy(usingNativeService = true, p95Micros = 500.0)
        assertTrue(nativeOk.meetsBudget)

        // Native with a breach: fails.
        val nativeBad = nativeOk.copy(budgetBreaches = 1)
        assertEquals(false, nativeBad.meetsBudget)
    }
}
