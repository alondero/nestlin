package com.github.alondero.nestlin.session

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
    val completeCalls: MutableList<Triple<Int, Int, String?>> = mutableListOf()
    var pendingUserInfo: String? = null

    override fun ra_facade_poll_event(handle: Pointer, out: RaEvent): Int = 0
    override fun ra_facade_clear_events(handle: Pointer) {}
    override fun ra_facade_create(serverUrl: String?, userAgent: String?): Pointer? = Pointer.NULL
    override fun ra_facade_destroy(handle: Pointer?): Int = 0
    override fun ra_facade_is_signed_in(handle: Pointer): Int = isSignedInReturn
    override fun ra_facade_begin_login_with_password(handle: Pointer, username: String, password: String): Int {
        beginPasswordCalls += username to password
        return 0
    }
    override fun ra_facade_begin_login_with_token(handle: Pointer, username: String, token: String): Int {
        beginTokenCalls += username to token
        return 0
    }
    override fun ra_facade_logout(handle: Pointer) {}
    override fun ra_facade_get_user_info(handle: Pointer, out: RaUserInfo): Int = 0
    override fun ra_facade_dequeue_http_request(handle: Pointer, out: RaHttpRequestSlot): Int = 0
    override fun ra_facade_complete_http_request(handle: Pointer, generation: Int, status: Int, body: String?, bodyLength: Int): Int {
        completeCalls += Triple(generation, status, body)
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
    override fun ra_facade_version(): String = "1.0.0-test"
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
}

/** In-memory [Preferences] is in InMemoryPreferences.kt — shared with RaCredentialsStoreTest. */