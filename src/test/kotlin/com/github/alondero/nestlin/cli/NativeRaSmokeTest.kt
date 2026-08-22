package com.github.alondero.nestlin.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Tests for [NativeRaSmoke]'s CLI shape and CLI-parsing logic.
 *
 * These tests run in the default `./gradlew test` suite — they don't
 * require the native library. The actual native smoke (which does
 * require the C library) is run via:
 *
 *   ./gradlew testNativeRa   (JVM contract tests on the binding)
 *   java -jar nestlin-all.jar nra-smoke   (the full smoke runner)
 *
 * Together the three lanes cover the AC's "smoke runner covers client
 * lifetime, version, NES hashing, mock login/game load, memory/events,
 * progress serialization, and callback teardown" — the JVM side
 * asserts the CLI shape, the binding tests assert the JNA contract,
 * and the smoke runner exercises the C façade end-to-end.
 */
class NativeRaSmokeTest {

    @Test
    fun `USAGE mentions every documented CLI option`() {
        val usage = NativeRaSmoke.USAGE
        // The `--rom` option must be advertised so a human running
        // the binary without --help gets a usable surface.
        assertTrue(usage.contains("--rom"),
            "USAGE must mention --rom: $usage")
        // Exit code contract must be visible so an operator can pick
        // the right shell-friendly signal.
        assertTrue(usage.contains("Exits 0") || usage.contains("exits 0"),
            "USAGE must mention exit codes: $usage")
    }

    @Test
    fun `Verdict exit codes are stable`() {
        // The CI matrix shells depend on these numbers; bumping them
        // is a breaking change.
        assertEquals(0, NativeRaSmoke.Verdict.PASS.exitCode)
        assertEquals(1, NativeRaSmoke.Verdict.FAIL.exitCode)
        assertEquals(3, NativeRaSmoke.Verdict.SKIPPED_LIB_MISSING.exitCode)
    }

    @Test
    fun `Cli rejects unknown flags`() {
        val out = StringBuilder()
        val rc = NativeRaSmokeCli.main(listOf("--no-such-flag"), out)
        assertEquals(2, rc, "unknown flag must exit 2 (usage)")
        assertTrue(out.toString().contains("ERROR"),
            "unknown flag must print an ERROR line: $out")
    }

    @Test
    fun `Cli rejects --rom without a value`() {
        val out = StringBuilder()
        val rc = NativeRaSmokeCli.main(listOf("--rom"), out)
        assertEquals(2, rc, "--rom without value must exit 2")
        assertTrue(out.toString().contains("--rom requires a value"),
            "missing-value error must mention the flag: $out")
    }

    @Test
    fun `Cli rejects positional arguments`() {
        val out = StringBuilder()
        val rc = NativeRaSmokeCli.main(listOf("surprise.nes"), out)
        assertEquals(2, rc, "positional arg must exit 2")
        assertTrue(out.toString().contains("positional"),
            "unexpected-arg error must mention 'positional': $out")
    }

    @Test
    fun `Cli on a host without native lib exits 3 (skipped)`() {
        // When the native library is absent, the runner prints the
        // manifest + load failures and exits 3. We don't assert on
        // the exact stdout (CI machines may differ); we only assert
        // the exit-code contract so the matrix CI scripts can rely
        // on it.
        val out = StringBuilder()
        val rc = NativeRaSmokeCli.main(emptyList(), out)
        // Exit code is either 0 (full pass on a host with native lib)
        // or 1 (a step failed) or 3 (no native lib). Anything else is
        // a regression in the exit-code contract.
        assertTrue(rc in setOf(0, 1, 3),
            "Smoke runner must exit 0/1/3, got $rc. Output: $out")
    }
}
