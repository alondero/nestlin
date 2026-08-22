package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.util.Redactor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pin the diagnostic-safety contract (issue #273 AC: "Failures
 * contain enough safe diagnostic context to troubleshoot
 * platform/runtime issues without exposing credentials").
 *
 * Every [Redactor]-routed log path in the RA integration must
 * scrub the credentials a misbehaving service or a verbose C
 * error message might leak. This test asserts the redaction
 * rules on the contract surface itself:
 *
 *  - URLs with sensitive query params have those params replaced
 *    with `***`.
 *  - Long alphanumeric runs (>= 16 chars) in free-form messages
 *    are replaced with `***`.
 *  - The combined `redactLogLine` does both in one call.
 *
 * It does NOT scan source code for unprotected println calls —
 * that's a static-analysis concern handled by a future
 * `RaLogSafetyLintTest`. This test pins the contract the lint
 * would enforce.
 */
class RaDiagnosticSafetyTest {

    @Test
    fun `redactUrl scrubs token-bearing query params`() {
        val safe = Redactor.redactUrl(
            "https://retroachievements.org/dorequest.php?r=login&t=abcdef0123456789abcdef0123456789"
        )
        // Path preserved; credential param value scrubbed.
        assertTrue(safe.startsWith("https://retroachievements.org/dorequest.php?"),
            "URL path must be preserved: $safe")
        assertTrue(safe.contains("t=***"), "token param must be scrubbed: $safe")
        assertFalse(safe.contains("abcdef0123456789"),
            "raw token must NOT appear in the redacted URL: $safe")
    }

    @Test
    fun `redactMessage scrubs long alphanumeric runs of 16 or more chars`() {
        val raw = "Server returned: token=abcdef0123456789abcdef status=200"
        val safe = Redactor.redactMessage(raw)
        assertFalse(safe.contains("abcdef0123456789abcdef"),
            "long token run must be scrubbed: $safe")
        assertTrue(safe.contains("status=200"),
            "short numeric status must NOT be scrubbed: $safe")
    }

    @Test
    fun `redactLogLine scrubs both URLs and free-form tokens`() {
        val raw = "[RA] HTTP request to " +
            "https://retroachievements.org/api?t=ABCDEFGHIJ1234567890ABCDEFGHIJ " +
            "failed with token=ABCDEFGHIJ1234567890ABCDEFGHIJ1234"
        val safe = Redactor.redactLogLine(raw)
        assertFalse(safe.contains("ABCDEFGHIJ1234567890ABCDEFGHIJ"),
            "URL-embedded token must be scrubbed: $safe")
        assertFalse(safe.contains("ABCDEFGHIJ1234567890ABCDEFGHIJ1234"),
            "message-embedded token must be scrubbed: $safe")
    }

    @Test
    fun `redactMessage preserves short tokens and human-readable text`() {
        val raw = "prepareGame failed: status=401 reason=missing_token"
        val safe = Redactor.redactMessage(raw)
        assertTrue(safe == raw,
            "short tokens / human-readable text must NOT be redacted. raw='$raw' safe='$safe'")
    }

    @Test
    fun `redactUrl preserves the path and non-sensitive params`() {
        val safe = Redactor.redactUrl(
            "https://retroachievements.org/api/v1/patchdata.php?game=12345"
        )
        assertTrue(safe.contains("game=12345"),
            "non-sensitive param value must be preserved: $safe")
    }

    @Test
    fun `every documented failure reason can be expressed without a token-shaped run`() {
        // The Redactor scrubs anything >= 16 alphanumeric chars. A
        // maintainer adding a new failure message must NOT include a
        // 16+ char run, or the Redactor will wipe the diagnostic.
        // We assert the actual messages we ship stay clean.
        val messages = listOf(
            "Manifest not bundled in JAR (expected at native-ra/MANIFEST.json).",
            "Manifest has no entry for platform 'linux-x86_64'.",
            "Unsupported OS/architecture: Linux amd64.",
            "Library size 123B != manifest 456B for linux-x86_64 (truncated or corrupt bundle).",
            "Library SHA-256 mismatch for linux-x86_64 (expected abc123def456…, got 9876543210fe…).",
            "rcheevos version mismatch: expected 12.4.0, library reports 12.5.0.",
            "Façade version mismatch: expected 1.0.0, library reports 0.9.0.",
        )
        val longRun = Regex("[A-Za-z0-9]{16,}")
        for (m in messages) {
            val hits = longRun.findAll(m).toList()
            assertTrue(hits.isEmpty(),
                "Diagnostic message contains a 16+ char alphanumeric run that Redactor would scrub. " +
                    "Use shorter prefixes (≤12 chars) or non-alphanumeric separators. " +
                    "Message: $m -> $hits")
        }
    }
}
