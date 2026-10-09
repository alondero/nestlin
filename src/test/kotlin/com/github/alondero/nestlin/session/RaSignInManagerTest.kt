package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.testutil.failTest
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for [RaSignInManager] (issue #268). Uses [FakeRaFacadeBindings]
 * to stand in for the JNA façade without loading the native library —
 * every test in this class is hermetic and offline.
 */
class RaSignInManagerTest {

    /** Combined fixture: returns a manager, the underlying fake bindings, the fake transport, and the credentials store. */
    private data class Fixture(
        val manager: RaSignInManager,
        val bindings: FakeRaFacadeBindings,
        val transport: FakeRaHttpTransport,
        val store: RaCredentialsStore,
    )

    /** A non-null [Pointer] used as the façade handle in tests. JNA's
     *  `Pointer.NULL` is a singleton Kotlin treats specially and evaluates
     *  to the Kotlin null literal, so we create a fresh pointer instead. */
    private val testHandle: Pointer = Pointer.createConstant(0L)

    private fun fixture(saved: RaCredentials? = null): Fixture {
        val transport = FakeRaHttpTransport()
        val bindings = FakeRaFacadeBindings()
        val store = RaCredentialsStore(InMemoryPreferences())
        if (saved != null) store.save(saved)
        val manager = RaSignInManager(
            native = null,  // unused when bindings/handle are supplied directly
            bindings = bindings,
            handle = testHandle,
            credentialsStore = store,
            httpBridgeFactory = { _, _ -> RaHttpBridge(bindings, testHandle, transport) },
        )
        return Fixture(manager, bindings, transport, store)
    }

    @Test
    fun `no-op manager starts in Unavailable state`() {
        val manager = RaSignInManager.from(NoOpRetroAchievementsService)
        assertSame(RaSignInState.Unavailable, manager.state)
    }

    @Test
    fun `start with no saved credentials stays SignedOut`() {
        val f = fixture()
        f.manager.start()
        assertSame(RaSignInState.SignedOut, f.manager.state)
    }

    @Test
    fun `start with saved credentials transitions through Authenticating`() {
        val f = fixture(
            saved = RaCredentials("alice", "ALICETOKEN1234567890ABCDEFGHIJ12"),
        )
        f.manager.start()
        assertTrue(
            f.manager.state is RaSignInState.Authenticating ||
                f.manager.state is RaSignInState.SignedOut,
            "expected Authenticating or SignedOut, got ${f.manager.state}",
        )
        assertTrue(bingsCalledLogin(f.bindings), "façade should have received a login call")
    }

    @Test
    fun `signInWithPassword transitions to Authenticating`() {
        val f = fixture()
        f.manager.signInWithPassword("alice", "secret123")
        assertSame(RaSignInState.Authenticating, f.manager.state)
        assertTrue(bingsCalledLogin(f.bindings), "façade should have received a login call")
    }

    @Test
    fun `signInWithPassword rejects blank inputs without state change`() {
        val f = fixture()
        val before = f.manager.state
        try {
            f.manager.signInWithPassword("", "pass")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            f.manager.signInWithPassword("user", "")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertSame(before, f.manager.state)
        assertEquals(0, f.bindings.beginPasswordCalls.size, "no C-side calls should have happened")
    }

    @Test
    fun `signInWithPassword is single-flight`() {
        val f = fixture()
        f.manager.signInWithPassword("alice", "pw1")
        val after1 = f.manager.state
        f.manager.signInWithPassword("bob", "pw2")
        assertSame(after1, f.manager.state)
    }

    @Test
    fun `listener fires on every state transition`() {
        val f = fixture()
        val log = CopyOnWriteArrayList<RaSignInState>()
        f.manager.addListener { state -> log += state }
        f.manager.signInWithPassword("alice", "pw")
        assertTrue(log.any { it is RaSignInState.Authenticating }, "listener should have seen Authenticating: $log")
    }

    @Test
    fun `removeListener stops the listener from firing`() {
        val f = fixture()
        var calls = 0
        val token = f.manager.addListener { calls++ }
        f.manager.signInWithPassword("alice", "pw")
        assertEquals(1, calls)
        f.manager.removeListener(token)
        f.manager.signInWithPassword("bob", "pw")
        assertEquals(1, calls)
    }

    @Test
    fun `markOffline keeps credentials so a manual retry can use them`() {
        val f = fixture(saved = RaCredentials("alice", "ALICETOKEN1234567890ABCDEFGHIJ12"))
        f.manager.markOffline("Network error during sign-in")
        assertTrue(f.manager.state is RaSignInState.Offline)
        assertNotNull(f.store.load())
    }

    @Test
    fun `signOut clears stored credentials and returns to SignedOut`() {
        val f = fixture(saved = RaCredentials("alice", "ALICETOKEN1234567890ABCDEFGHIJ12"))
        f.manager.signOut()
        assertSame(RaSignInState.SignedOut, f.manager.state)
        assertNull(f.store.load())
    }

    @Test
    fun `password does not leak into stored credentials`() {
        val f = fixture()
        val keys = f.store.prefs.keys()
        assertTrue(keys.isEmpty() || keys.all { it == "username" || it == "token" },
            "credentials store must only have username + token keys, got: ${keys.toList()}")
        f.manager.signInWithPassword("alice", "DO_NOT_PERSIST")
        val keysAfter = f.store.prefs.keys()
        assertTrue(keysAfter.isEmpty() || keysAfter.all { it == "username" || it == "token" },
            "no password-like keys should be written: ${keysAfter.toList()}")
        for (k in keysAfter) {
            val v = f.store.prefs.get(k, "")
            assertFalse(v.contains("DO_NOT_PERSIST"),
                "password should not appear under key '$k'")
        }
    }

    /**
     * Regression for "sign-in stays on Signing in… forever". rcheevos sends
     * every API call to `/dorequest.php` with the method (`r=login2`) in the
     * POST body; the manager used to wait for a `login2.php` URL that never
     * arrives, so a successful round-trip never left Authenticating.
     */
    @Test
    fun `password login reaches SignedIn once the login response is delivered`() {
        val f = fixture()
        f.bindings.scriptLoginRoundTrip(username = "Alice", token = "ALICETOKEN1234567890ABCDEFGHIJ12")
        f.manager.signInWithPassword("alice", "secret123")
        try {
            val state = awaitState(f.manager) { it is RaSignInState.SignedIn }
            assertEquals("Alice", (state as RaSignInState.SignedIn).account.username)
            assertEquals(RaCredentials("Alice", "ALICETOKEN1234567890ABCDEFGHIJ12"), f.store.load())
        } finally {
            f.manager.shutdown()
        }
    }

    @Test
    fun `rejected credentials surface the server reason and clear saved credentials`() {
        val f = fixture(saved = RaCredentials("alice", "OLDTOKEN"))
        f.bindings.scriptLoginRoundTrip(username = "alice", token = "unused")
        f.transport.enqueueResponse(401, """{"Success":false,"Error":"Invalid User/Password combination. Please try again","Code":"invalid_credentials"}""")
        f.manager.signInWithPassword("alice", "wrong")
        try {
            val state = awaitState(f.manager) { it !is RaSignInState.Authenticating }
            assertTrue(state is RaSignInState.Rejected, "expected Rejected, got $state")
            assertEquals(
                "Invalid User/Password combination. Please try again",
                (state as RaSignInState.Rejected).reason,
            )
            assertNull(f.store.load(), "a rejected login must not leave stale credentials behind")
        } finally {
            f.manager.shutdown()
        }
    }

    @Test
    fun `network failure while restoring a saved token goes Offline and keeps the credentials`() {
        val saved = RaCredentials("alice", "ALICETOKEN1234567890ABCDEFGHIJ12")
        val f = fixture(saved = saved)
        f.bindings.scriptLoginRoundTrip(username = "alice", token = saved.token)
        f.transport.enqueueTransportFailure()
        f.manager.start()
        try {
            val state = awaitState(f.manager) { it !is RaSignInState.Authenticating }
            assertTrue(state is RaSignInState.Offline, "expected Offline, got $state")
            assertEquals(saved, f.store.load())
        } finally {
            f.manager.shutdown()
        }
    }

    @Test
    fun `a login the facade settles up front never waits on the network`() {
        val f = fixture()
        f.bindings.settleLoginUpFront = RcResult.INVALID_CREDENTIALS to "username is required"
        f.manager.signInWithPassword("alice", "pw")
        try {
            val state = awaitState(f.manager) { it !is RaSignInState.Authenticating }
            assertEquals(RaSignInState.Rejected("username is required"), state)
            assertTrue(f.transport.sent.isEmpty())
        } finally {
            f.manager.shutdown()
        }
    }

    /**
     * rcheevos only releases a request's bookkeeping (and a login's
     * single-flight guard) when its response is delivered, so signing out
     * must not strand requests by stopping the bridge.
     */
    @Test
    fun `signing out keeps delivering responses for requests already issued`() {
        val f = fixture()
        f.bindings.scriptLoginRoundTrip(username = "alice", token = "ALICETOKEN1234567890ABCDEFGHIJ12")
        f.manager.signInWithPassword("alice", "pw")
        try {
            awaitState(f.manager) { it is RaSignInState.SignedIn }
            f.manager.signOut()
            f.bindings.enqueueRequest("https://retroachievements.org/dorequest.php", "r=ping")

            val deadline = System.currentTimeMillis() + 3_000
            while (f.bindings.completeCalls.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(2, f.bindings.completeCalls.size, "the post-sign-out request was never completed")
        } finally {
            f.manager.shutdown()
        }
    }

    @Test
    fun `addListener then removeListener works in any order`() {
        val f = fixture()
        val tokenA = f.manager.addListener { /* never fires */ }
        val tokenB = f.manager.addListener { /* never fires */ }
        f.manager.removeListener(tokenA)
        f.manager.removeListener(tokenB)
    }

    /**
     * Helper: did the façade receive any login-related call? The fake
     * tracks every method invocation; we accept either password or token.
     */
    private fun bingsCalledLogin(bindings: FakeRaFacadeBindings): Boolean =
        bindings.beginPasswordCalls.isNotEmpty() || bindings.beginTokenCalls.isNotEmpty()

    /** Poll [manager] until [predicate] holds; the HTTP bridge settles on its own thread. */
    private fun awaitState(
        manager: RaSignInManager,
        timeoutMs: Long = 3_000,
        predicate: (RaSignInState) -> Boolean,
    ): RaSignInState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = manager.state
            if (predicate(s)) return s
            Thread.sleep(10)
        }
        failTest("timed out waiting for sign-in state; last state was ${manager.state}")
    }
}

/**
 * Test double for [RaFacadeBindings]. Records every method invocation
 * instead of touching JNA; tests assert against the recorded list.
 *
 * Only the methods used by [RaSignInManager] are tracked; adding new
 * methods to [RaFacadeBindings] without extending this fake fails the
 * build at compile time, which is the desired signal.
 */
internal class FakeRaFacadeBindings : RaFacadeBindings {
    val beginPasswordCalls: MutableList<Pair<String, String>> = mutableListOf()
    val beginTokenCalls: MutableList<Pair<String, String>> = mutableListOf()
    val logoutCalls: Int = 0
    var isSignedInReturn: Int = 0
    val dequeueCalls: Int = 0

    /** One response the bridge delivered back to the "façade". */
    class Completion(val requestId: Int, val status: Int, val body: ByteArray?, val bodyLength: Int)

    /** Every completion the bridge delivered, in order. */
    val completeCalls: MutableList<Completion> = java.util.concurrent.CopyOnWriteArrayList()
    var pendingUserInfo: String? = null

    /**
     * Server reason reported for a login the server rejects (4xx). Mirrors
     * what rcheevos copies out of the `Error` field of the login2 response.
     */
    var rejectionMessage: String = "Invalid User/Password combination. Please try again"

    /**
     * When set, begin-login settles synchronously with this (rc result,
     * message) and issues no HTTP — rcheevos's "rejected up front" path.
     */
    var settleLoginUpFront: Pair<Int, String>? = null

    private class Request(val id: Int, val url: String, val body: String?, val isLogin: Boolean)

    private val lock = Any()
    private val queued: ArrayDeque<Request> = ArrayDeque()
    private val dispatched: MutableMap<Int, Request> = mutableMapOf()
    private var nextRequestId = 1
    private var scriptedLogin: Pair<String, String>? = null
    @Volatile private var signedInUser: Pair<String, String>? = null
    private var loginOutcome = RaLoginOutcome.NONE
    private var loginResult = 0
    private var loginMessage = ""

    /**
     * A begin-login call enqueues one HTTP request shaped exactly like
     * rcheevos's (`POST /dorequest.php`, `r=login2` in the body). A 2xx
     * completion signs this user in; a negative status settles as
     * RC_NO_RESPONSE; any other status as RC_INVALID_CREDENTIALS.
     */
    fun scriptLoginRoundTrip(username: String, token: String) {
        scriptedLogin = username to token
    }

    /** Simulate rcheevos issuing a non-login request (e.g. a game load). */
    fun enqueueRequest(url: String, body: String? = null) {
        synchronized(lock) { queued.addLast(Request(nextRequestId++, url, body, isLogin = false)) }
    }

    private fun beginLogin(username: String): Int {
        settleLoginUpFront?.let { (result, message) ->
            settleLogin(RaLoginOutcome.FAILED, result, message)
            return RaStatus.OK
        }
        if (scriptedLogin == null) return RaStatus.OK
        synchronized(lock) {
            queued.addLast(
                Request(nextRequestId++, "https://retroachievements.org/dorequest.php", "r=login2&u=$username&p=***", isLogin = true),
            )
        }
        return RaStatus.OK
    }

    private fun settleLogin(outcome: Int, result: Int, message: String) {
        synchronized(lock) {
            loginOutcome = outcome
            loginResult = result
            loginMessage = message
        }
    }

    override fun ra_facade_poll_event(handle: Pointer, out: RaEvent): Int = 0
    override fun ra_facade_clear_events(handle: Pointer) {}
    override fun ra_facade_create(serverUrl: String?, userAgent: String?): Pointer? = Pointer.NULL
    override fun ra_facade_destroy(handle: Pointer?): Int = 0
    override fun ra_facade_is_signed_in(handle: Pointer): Int = isSignedInReturn
    override fun ra_facade_begin_login_with_password(handle: Pointer, username: String, password: String): Int {
        beginPasswordCalls += username to password
        return beginLogin(username)
    }
    override fun ra_facade_begin_login_with_token(handle: Pointer, username: String, token: String): Int {
        beginTokenCalls += username to token
        return beginLogin(username)
    }
    override fun ra_facade_logout(handle: Pointer) {
        signedInUser = null
    }
    override fun ra_facade_take_login_result(handle: Pointer, outResult: IntByReference, outMessage: ByteArray, messageCapacity: Int): Int =
        synchronized(lock) {
            val outcome = loginOutcome
            outResult.value = loginResult
            fixed(loginMessage, messageCapacity).copyInto(outMessage)
            loginOutcome = RaLoginOutcome.NONE
            outcome
        }
    override fun ra_facade_get_user_info(handle: Pointer, out: RaUserInfo): Int {
        val user = signedInUser ?: return RaStatus.ERR_NOT_SIGNED_IN
        out.username = fixed(user.first, RaUserInfo.RA_FACADE_USERNAME_MAX)
        out.displayName = fixed(user.first, RaUserInfo.RA_FACADE_DISPLAY_NAME_MAX)
        out.token = fixed(user.second, RaUserInfo.RA_FACADE_TOKEN_MAX)
        out.write()
        return RaStatus.OK
    }
    override fun ra_facade_dequeue_http_request(handle: Pointer, out: RaHttpRequestSlot): Int {
        val request = synchronized(lock) {
            queued.removeFirstOrNull()?.also { dispatched[it.id] = it }
        } ?: return 0
        out.requestId = request.id
        out.url = fixed(request.url, RaHttpRequestSlot.RA_FACADE_HTTP_URL_MAX)
        out.postData = fixed(request.body ?: "", RaHttpRequestSlot.RA_FACADE_HTTP_BODY_MAX)
        out.hasPostData = if (request.body != null) 1 else 0
        out.write()
        return 1
    }
    override fun ra_facade_complete_http_request(handle: Pointer, requestId: Int, status: Int, body: ByteArray?, bodyLength: Int): Int {
        val request = synchronized(lock) { dispatched.remove(requestId) } ?: return 0
        completeCalls += Completion(requestId, status, body?.copyOf(), bodyLength)
        if (request.isLogin) {
            when {
                status in 200..299 -> {
                    signedInUser = scriptedLogin
                    settleLogin(RaLoginOutcome.SUCCEEDED, 0, "")
                }
                status < 0 -> settleLogin(RaLoginOutcome.FAILED, RcResult.NO_RESPONSE, "No response from server")
                else -> settleLogin(RaLoginOutcome.FAILED, RcResult.INVALID_CREDENTIALS, rejectionMessage)
            }
        }
        return 1
    }
    override fun ra_facade_prepare_game(handle: Pointer, romBytes: ByteArray, romLen: Int, displayName: String?): Int = 0
    override fun ra_facade_evaluate_frame(handle: Pointer, frameIndex: Long) {}
    override fun ra_facade_idle(handle: Pointer) {}
    override fun ra_facade_reset(handle: Pointer) {}
    override fun ra_facade_unload_game(handle: Pointer) {}
    override fun ra_facade_get_load_state(handle: Pointer): Int = 0
    override fun ra_facade_get_game_info(handle: Pointer, out: RaGameInfo): Int = 0
    override fun ra_facade_set_memory_reader(handle: Pointer, fn: RaReadMemoryFn?, userdata: Pointer?): Int = 0
    override fun ra_facade_progress_size(handle: Pointer): Int = 0
    override fun ra_facade_serialize_progress(handle: Pointer, out: ByteArray, outCapacity: Int): Int = 0
    override fun ra_facade_restore_progress(handle: Pointer, data: ByteArray?, dataLen: Int): Int = 0
    override fun ra_facade_rcheevos_version(): String = "12.4.0-test"
    override fun ra_facade_version(): String = "1.1.0-test"
    override fun ra_facade_hash_nes_rom(romBytes: ByteArray, romLen: Int, outHash: ByteArray): Int = 0
    override fun ra_facade_get_user_game_summary(handle: Pointer, out: RaUserGameSummary): Int = 0
    override fun ra_facade_get_game_summary(handle: Pointer, out: RaGameSummarySlot): Int = 0
    override fun ra_facade_wait_for_load_settle(handle: Pointer, timeoutMs: Int, pollMs: Int, outState: IntByReference): Int = 0
    override fun ra_facade_badge_url(badgeName: String, outUrl: ByteArray, outUrlCapacity: Int): Int = 0
    // Per-achievement list (issue #272) — the sign-in manager doesn't
    // touch these directly, but the interface still has to be fully
    // implemented for the test compile to pass.
    override fun ra_facade_has_achievements(handle: Pointer): Int = 0
    override fun ra_facade_create_achievement_list(handle: Pointer, category: Int, grouping: Int): Int = 0
    override fun ra_facade_achievement_list_bucket_count(handle: Pointer): Int = 0
    override fun ra_facade_get_achievement_bucket(handle: Pointer, bucketIndex: Int, out: RaAchievementBucketSlot): Int = 0
    override fun ra_facade_get_achievement_at(handle: Pointer, bucketIndex: Int, achievementIndex: Int, out: RaAchievementSlot): Int = 0
    override fun ra_facade_destroy_achievement_list(handle: Pointer) {}

    /** NUL-padded fixed-size buffer, as the C side writes into the struct arrays. */
    private fun fixed(text: String, size: Int): ByteArray =
        text.toByteArray(Charsets.UTF_8).copyOf(size)
}

/** In-memory [Preferences] is in InMemoryPreferences.kt — shared with RaCredentialsStoreTest. */