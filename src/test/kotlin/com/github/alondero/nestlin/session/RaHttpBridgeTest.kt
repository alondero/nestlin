package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.testutil.failTest
import com.sun.jna.Pointer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [RaHttpBridge] against the fake façade + fake transport. The bridge must
 * complete every request it hands to the transport exactly once — rcheevos
 * releases its per-request state (and a login's single-flight guard) only
 * on completion, so a lost completion hangs that operation forever.
 */
class RaHttpBridgeTest {

    private val handle: Pointer = Pointer.createConstant(0L)
    private val bindings = FakeRaFacadeBindings()
    private val transport = FakeRaHttpTransport()
    private val bridge = RaHttpBridge(bindings, handle, transport)

    @AfterEach
    fun tearDown() {
        bridge.stop()
    }

    /** rcheevos parses `body_length` bytes, so the length must be the UTF-8 byte count, not the char count. */
    @Test
    fun `response body reaches the facade as the exact bytes received`() {
        val body = """{"Success":true,"User":"Ålice ✓"}""".toByteArray(Charsets.UTF_8)
        transport.enqueueResponse(RaHttpResponse(status = 200, body = body))
        bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=login2")

        bridge.start()
        val completion = awaitCompletions(1).single()

        assertEquals(200, completion.status)
        assertArrayEquals(body, completion.body)
        assertEquals(body.size, completion.bodyLength)
    }

    @Test
    fun `a request whose transport throws is still completed as a client error`() {
        transport.throwOnSend = IllegalArgumentException("Illegal character in path")
        bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=login2")

        bridge.start()
        val completion = awaitCompletions(1).single()

        assertEquals(JavaHttpClientTransport.RC_API_SERVER_RESPONSE_CLIENT_ERROR, completion.status)
    }

    @Test
    fun `a transport that calls back twice completes the request only once`() {
        transport.doubleCallback = true
        bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=login2")

        bridge.start()
        awaitCompletions(1)
        Thread.sleep(100)

        assertEquals(1, bindings.completeCalls.size)
    }

    @Test
    fun `requests are completed in the order they were issued`() {
        bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=login2")
        bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=gameid")

        bridge.start()
        val completions = awaitCompletions(2)

        assertEquals(listOf("r=login2", "r=gameid"), transport.sent.map { it.postData })
        assertEquals(completions[0].requestId + 1, completions[1].requestId)
    }

    private fun awaitCompletions(count: Int): List<FakeRaFacadeBindings.Completion> {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (bindings.completeCalls.size >= count) return bindings.completeCalls.toList()
            Thread.sleep(5)
        }
        failTest("expected $count completion(s), saw ${bindings.completeCalls.size}")
    }
}
