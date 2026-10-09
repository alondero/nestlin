package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.testutil.failTest
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Contract tests for the façade's HTTP queue — the path every
 * RetroAchievements request takes (login, game identify, unlocks).
 *
 * Drives the real native library with no network: the test plays the
 * part of [RaHttpBridge], dequeuing what rcheevos asked for and posting
 * a canned server response back. Tagged `nativeRa`; run via
 * `./gradlew testNativeRa`.
 */
@Tag("nativeRa")
class RaFacadeHttpRoundTripTest {

    private lateinit var lib: RaFacadeBindings
    private lateinit var handle: Pointer

    @BeforeEach
    fun setUp() {
        lib = RaFacadeBindings.load() ?: failTest("native RA library not loadable — run via ./gradlew testNativeRa")
        handle = lib.ra_facade_create(null, null) ?: failTest("ra_facade_create returned null")
    }

    @AfterEach
    fun tearDown() {
        lib.ra_facade_destroy(handle)
    }

    /**
     * rcheevos builds each request in a stack-local struct and frees it as
     * soon as the server-call callback returns, so the façade must copy the
     * strings rather than keep pointers into that struct.
     */
    @Test
    fun `login request handed to the bridge carries the rcheevos login call`() {
        assertEquals(RaStatus.OK, lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2"))

        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        assertEquals("https://retroachievements.org/dorequest.php", text(slot.url))
        val body = text(slot.postData)
        assertTrue(body.startsWith("r=login2&u=alice&p=hunter2"), "unexpected login POST body: '$body'")
        assertEquals("application/x-www-form-urlencoded", text(slot.contentType))
    }

    @Test
    fun `each queued request is handed to the bridge exactly once`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        dequeue() ?: failTest("login did not enqueue an HTTP request")

        assertEquals(null, dequeue(), "an in-flight request must not be handed out a second time")
    }

    @Test
    fun `successful login response signs the user in`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        complete(slot, 200, SUCCESS_BODY)

        assertEquals(1, lib.ra_facade_is_signed_in(handle))
        val info = RaUserInfo().also { it.write() }
        assertEquals(RaStatus.OK, lib.ra_facade_get_user_info(handle, info))
        info.read()
        assertEquals("Alice", text(info.username))
        assertEquals("ABCDEF0123456789", text(info.token))
        assertEquals(120, info.score)
    }

    @Test
    fun `each login settlement is reported exactly once`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        assertEquals(RaLoginOutcome.NONE, takeLoginResult().outcome, "nothing settles before the response")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        complete(slot, 200, SUCCESS_BODY)

        assertEquals(RaLoginOutcome.SUCCEEDED, takeLoginResult().outcome)
        assertEquals(RaLoginOutcome.NONE, takeLoginResult().outcome)
    }

    @Test
    fun `rejected login reports the server reason`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "wrong")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        complete(slot, 401, """{"Success":false,"Error":"Invalid User/Password combination. Please try again","Code":"invalid_credentials"}""")

        val result = takeLoginResult()
        assertEquals(RaLoginOutcome.FAILED, result.outcome)
        assertEquals(RcResult.INVALID_CREDENTIALS, result.rcResult)
        assertEquals("Invalid User/Password combination. Please try again", result.message)
        assertEquals(0, lib.ra_facade_is_signed_in(handle))
    }

    @Test
    fun `transport failure settles the login as no response`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        lib.ra_facade_complete_http_request(handle, slot.requestId, JavaHttpClientTransport.RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR, null, 0)

        val result = takeLoginResult()
        assertEquals(RaLoginOutcome.FAILED, result.outcome)
        assertEquals(RcResult.NO_RESPONSE, result.rcResult)
    }

    /**
     * Logout must not discard the in-flight login request: rcheevos settles
     * it (as aborted) only when its response arrives, and until then a new
     * login is refused rather than racing the old one.
     */
    @Test
    fun `login aborted by logout settles when its response arrives`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")
        lib.ra_facade_logout(handle)

        assertEquals(RaStatus.ERR_LIBRARY_STATE, lib.ra_facade_begin_login_with_password(handle, "bob", "pw"))

        complete(slot, 200, SUCCESS_BODY)

        val result = takeLoginResult()
        assertEquals(RaLoginOutcome.FAILED, result.outcome)
        assertEquals(RcResult.ABORTED, result.rcResult)
        assertEquals(0, lib.ra_facade_is_signed_in(handle), "an aborted login must not sign anyone in")
        assertEquals(RaStatus.OK, lib.ra_facade_begin_login_with_password(handle, "bob", "pw"))
    }

    @Test
    fun `response body is parsed using its UTF-8 byte length`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        val slot = dequeue() ?: failTest("login did not enqueue an HTTP request")

        complete(slot, 200, """{"Success":true,"User":"Ålicé","Token":"ABCDEF0123456789"}""")

        val info = RaUserInfo().also { it.write() }
        assertEquals(RaStatus.OK, lib.ra_facade_get_user_info(handle, info))
        info.read()
        assertEquals("Ålicé", text(info.username))
    }

    @Test
    fun `destroy settles a request that is still in flight`() {
        lib.ra_facade_begin_login_with_password(handle, "alice", "hunter2")
        dequeue() ?: failTest("login did not enqueue an HTTP request")
        val other = lib.ra_facade_create(null, null) ?: failTest("second ra_facade_create returned null")
        lib.ra_facade_begin_login_with_password(other, "bob", "pw")

        // Must not crash or double-free; tearDown destroys `handle` the same way.
        assertEquals(RaStatus.OK, lib.ra_facade_destroy(other))
    }

    private data class LoginResult(val outcome: Int, val rcResult: Int, val message: String)

    private fun takeLoginResult(): LoginResult {
        val rc = IntByReference(0)
        val message = ByteArray(RaEvent.RA_FACADE_ERROR_MAX)
        val outcome = lib.ra_facade_take_login_result(handle, rc, message, message.size)
        return LoginResult(outcome, rc.value, text(message))
    }

    private fun dequeue(): RaHttpRequestSlot? {
        val slot = RaHttpRequestSlot().also { it.write() }
        val has = lib.ra_facade_dequeue_http_request(handle, slot)
        slot.read()
        return if (has == 0) null else slot
    }

    private fun complete(slot: RaHttpRequestSlot, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(1, lib.ra_facade_complete_http_request(handle, slot.requestId, status, bytes, bytes.size))
    }

    private fun text(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        return String(if (end >= 0) bytes.copyOf(end) else bytes, Charsets.UTF_8)
    }

    private companion object {
        const val SUCCESS_BODY =
            """{"Success":true,"User":"Alice","Token":"ABCDEF0123456789","Score":120,"SoftcoreScore":30,"Messages":2}"""
    }
}
