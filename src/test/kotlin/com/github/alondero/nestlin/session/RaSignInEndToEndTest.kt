package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.testutil.failTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The whole sign-in stack — real native façade + rcheevos, real
 * [RaHttpBridge], real [RaSignInManager] — with only the network replaced
 * by [FakeRaHttpTransport]. Tagged `nativeRa`; run via `./gradlew testNativeRa`.
 */
@Tag("nativeRa")
class RaSignInEndToEndTest {

    private val service: NativeRetroAchievementsService =
        NativeRetroAchievementsService.load() ?: failTest("native RA library not loadable — run via ./gradlew testNativeRa")
    private val transport = FakeRaHttpTransport()
    private val store = RaCredentialsStore(InMemoryPreferences())
    private val manager = RaSignInManager.from(service, store, transport)

    @AfterEach
    fun tearDown() {
        service.shutdown()
    }

    @Test
    fun `password login signs in and saves the token`() {
        transport.enqueueResponse(200, """{"Success":true,"User":"Alice","Token":"ABCDEF0123456789","Score":120,"SoftcoreScore":30,"Messages":2}""")

        manager.signInWithPassword("alice", "hunter2")
        val state = awaitSettled()

        assertTrue(state is RaSignInState.SignedIn, "expected SignedIn, got $state")
        assertEquals("Alice", (state as RaSignInState.SignedIn).account.username)
        assertEquals(RaCredentials("Alice", "ABCDEF0123456789"), store.load())
        val login = transport.sent.single()
        assertTrue(login.url.endsWith("/dorequest.php"), "unexpected URL ${login.url}")
        assertTrue(login.postData!!.startsWith("r=login2&u=alice&"), "unexpected body ${login.postData}")
    }

    @Test
    fun `wrong password is rejected with the server reason`() {
        transport.enqueueResponse(401, """{"Success":false,"Error":"Invalid User/Password combination. Please try again","Code":"invalid_credentials"}""")

        manager.signInWithPassword("alice", "wrong")

        assertEquals(RaSignInState.Rejected("Invalid User/Password combination. Please try again"), awaitSettled())
        assertNull(store.load())
    }

    @Test
    fun `unreachable server goes Offline`() {
        transport.enqueueTransportFailure()

        manager.signInWithPassword("alice", "hunter2")

        assertTrue(awaitSettled() is RaSignInState.Offline)
    }

    @Test
    fun `service shutdown stops the sign-in bridge before destroying the handle`() {
        manager.signInWithPassword("alice", "hunter2")
        awaitSettled()
        assertTrue(manager.isBridgeRunning)

        service.shutdown()

        assertFalse(manager.isBridgeRunning, "the bridge must not outlive the native handle it polls")
    }

    private fun awaitSettled(): RaSignInState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = manager.state
            if (s !is RaSignInState.Authenticating) return s
            Thread.sleep(10)
        }
        failTest("sign-in never left Authenticating")
    }
}
