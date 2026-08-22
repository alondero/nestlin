package com.github.alondero.nestlin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [Redactor]. Verifies the redaction policy from issue #268:
 * passwords, tokens, POST bodies, and authenticated URLs must never appear
 * in log lines, exception messages, or any other output.
 */
class RedactorTest {

    // -------------------- redactUrl --------------------

    @Test
    fun `redactUrl leaves URLs without query strings untouched`() {
        val url = "https://retroachievements.org/api/v1/login2.php"
        assertEquals(url, Redactor.redactUrl(url))
    }

    @Test
    fun `redactUrl replaces token query parameter`() {
        val url = "https://retroachievements.org/api?token=abcdef0123456789abcdef01&game=1"
        val redacted = Redactor.redactUrl(url)
        assertTrue(redacted.contains("token=***"), "Should redact token: $redacted")
        assertTrue(redacted.contains("game=1"), "Should preserve non-sensitive params: $redacted")
        assertTrue(!redacted.contains("abcdef0123456789abcdef01"), "Token value should not leak: $redacted")
    }

    @Test
    fun `redactUrl replaces api_token query parameter`() {
        val url = "https://example.com/api?api_token=ABCDEFGHIJ1234567890&other=keep"
        val redacted = Redactor.redactUrl(url)
        assertTrue(redacted.contains("api_token=***"))
        assertTrue(redacted.contains("other=keep"))
    }

    @Test
    fun `redactUrl replaces t query parameter (RA's shorthand)`() {
        val url = "https://retroachievements.org/dorequest.php?r=login&t=abcdef0123456789abcdef0123456789"
        val redacted = Redactor.redactUrl(url)
        assertTrue(redacted.contains("t=***"))
        assertTrue(!redacted.contains("abcdef0123456789abcdef0123456789"))
    }

    @Test
    fun `redactUrl replaces password and username params`() {
        val url = "https://example.com/?password=secret&username=alice"
        val redacted = Redactor.redactUrl(url)
        assertTrue(redacted.contains("password=***"))
        assertTrue(redacted.contains("username=***"))
        assertTrue(!redacted.contains("secret"))
        assertTrue(!redacted.contains("alice"))
    }

    @Test
    fun `redactUrl is case-insensitive on parameter names`() {
        val url = "https://example.com/?Token=abcdef0123456789abcdef01&Other=keep"
        val redacted = Redactor.redactUrl(url)
        assertTrue(redacted.contains("Token=***"), "Mixed-case sensitive param should still be scrubbed")
    }

    @Test
    fun `redactUrl preserves parameter order`() {
        val url = "https://example.com/?a=1&token=secret1234567890ABCDE&b=2"
        val redacted = Redactor.redactUrl(url)
        // Token must be scrubbed; non-sensitive params retained.
        val aIdx = redacted.indexOf("a=1")
        val tIdx = redacted.indexOf("token=***")
        val bIdx = redacted.indexOf("b=2")
        assertTrue(aIdx in 0 until tIdx && tIdx < bIdx, "Order should be preserved: $redacted")
    }

    @Test
    fun `redactUrl returns null-or-blank unchanged`() {
        assertEquals("", Redactor.redactUrl(""))
        assertEquals("", Redactor.redactUrl(null))
    }

    // -------------------- redactMessage --------------------

    @Test
    fun `redactMessage scrubs long alphanumeric tokens`() {
        val msg = "Got error: token=ABCDEFGHIJ1234567890XYZ was rejected"
        val redacted = Redactor.redactMessage(msg)
        assertTrue(!redacted.contains("ABCDEFGHIJ1234567890XYZ"))
        assertTrue(redacted.contains("token=***"))
    }

    @Test
    fun `redactMessage preserves short tokens and prose`() {
        val msg = "User said: hi!"
        assertEquals(msg, Redactor.redactMessage(msg))
    }

    @Test
    fun `redactMessage handles multiple tokens in one line`() {
        val msg = "auth1=ABCDEFGHIJ1234567890XYZW auth2=0987654321ABCDEFGHIJKL"
        val redacted = Redactor.redactMessage(msg)
        assertTrue(!redacted.contains("ABCDEFGHIJ1234567890XYZW"))
        assertTrue(!redacted.contains("0987654321ABCDEFGHIJKL"))
    }

    @Test
    fun `redactMessage returns null-or-blank unchanged`() {
        assertEquals("", Redactor.redactMessage(""))
        assertEquals("", Redactor.redactMessage(null))
    }

    // -------------------- redactLogLine --------------------

    @Test
    fun `redactLogLine scrubs URLs and long tokens in one pass`() {
        val line = "GET https://retroachievements.org/api?t=ABCDEFGHIJ1234567890ABCDEFGHIJ token=ABCDEFGHIJ1234567890XYZW"
        val redacted = Redactor.redactLogLine(line)
        assertTrue(!redacted.contains("ABCDEFGHIJ1234567890ABCDEFGHIJ"), "URL token must be redacted: $redacted")
        assertTrue(!redacted.contains("ABCDEFGHIJ1234567890XYZW"), "Standalone token must be redacted: $redacted")
        assertNotNull(redacted)
    }

    @Test
    fun `redactLogLine preserves non-sensitive prose`() {
        val line = "[RA] Achievement unlocked: id=42 points=5 title=JumpMan"
        assertEquals(line, Redactor.redactLogLine(line))
    }
}