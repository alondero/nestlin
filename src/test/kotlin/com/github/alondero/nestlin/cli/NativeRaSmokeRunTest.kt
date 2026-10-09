package com.github.alondero.nestlin.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Runs the full `nra-smoke` battery against the live native library and
 * requires every step to pass. Tagged `nativeRa`; run via `./gradlew testNativeRa`.
 *
 * The argument-parsing tests in [NativeRaSmokeTest] accept exit code 1, so a
 * broken smoke run stays green in `./gradlew test`. This test is the gate.
 */
@Tag("nativeRa")
class NativeRaSmokeRunTest {

    @Test
    fun `every nra-smoke step passes against the native library`() {
        val out = StringBuilder()

        val verdict = NativeRaSmoke.run(out)

        assertEquals(NativeRaSmoke.Verdict.PASS, verdict, "nra-smoke did not pass:\n$out")
        assertFalse(out.contains(" FAIL "), "a smoke step reported FAIL:\n$out")
    }
}
