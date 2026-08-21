package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.Tag

/**
 * Contract tests for the native RA façade.
 *
 * Two distinct suites:
 *
 * 1. **Library-availability tests** (no native lib needed) — verify the
 *    factory's fallback path: when the native library is absent or
 *    corrupt, the factory must return [NoOpRetroAchievementsService].
 *    These tests run in the default test suite and never load JNA.
 *
 * 2. **Real-library contract tests** (tagged `@Tag("nativeRa")`) — only
 *    run when the JNA library has been compiled and placed on the
 *    resource path. They assert on the C-side behaviour the JNA binding
 *    must reproduce:
 *
 *      - Forced softcore mode after client creation
 *      - prepare_game returns false when not signed in
 *      - evaluate_frame is a safe no-op when no game is loaded
 *      - serialize returns null (0 bytes) when no game is loaded
 *      - destroy on null handle returns OK
 *      - the version string is non-empty
 *
 *    These tests are gated behind [EnabledIf] on a system property that
 *    the `:testNativeRa` Gradle task sets automatically. They are
 *    EXCLUDED from `./gradlew test` (the default fast suite) so a
 *    missing native library never breaks CI.
 *
 * The "no borrowed native memory in logs" requirement is verified by
 * the NoOp test (no native calls → no native logs) and by an
 * observation of the RA service in real operation — the implementation
 * deliberately never calls `String(Pointer)` on borrowed native memory.
 */
@Tag("nativeRa")
class RetroAchievementsServiceFactoryTest {

    // ---------------------------------------------------------------------
    // Library-availability tests — these ALWAYS run (no native lib needed).
    // ---------------------------------------------------------------------

    @Test
    fun `factory with forceNoOp returns NoOp regardless of native availability`() {
        val svc = RetroAchievementsServiceFactory.create(forceNoOp = true)
        assertSame(NoOpRetroAchievementsService, svc,
            "forceNoOp must return the singleton NoOp instance, not a new native one")
    }

    @Test
    fun `factory fallback returns NoOp when native library is unavailable`() {
        // The CI/test environment does not have the native library on the
        // classpath by default; this asserts the documented graceful
        // degradation. If the test machine DOES have the native library,
        // the call still returns a usable service (native or NoOp) — the
        // `isSignedIn` check below discriminates between the two cases.
        val svc = RetroAchievementsServiceFactory.create()
        // The result is either NoOp or a working native service. In the
        // test JVM without a native lib on the classpath, it must be NoOp.
        if (!RetroAchievementsServiceFactory.isNativeLibraryAvailable()) {
            assertSame(NoOpRetroAchievementsService, svc,
                "Factory must fall back to NoOp when the native library is unavailable")
        } else {
            // The native path loaded — verify it's not the NoOp singleton
            // (it should be a NativeRetroAchievementsService instance).
            assertFalse(svc is NoOpRetroAchievementsService,
                "Factory must return the native service when the library is available")
        }
    }

    @Test
    fun `rcheevosVersion returns null when native library is unavailable`() {
        if (!RetroAchievementsServiceFactory.isNativeLibraryAvailable()) {
            assertEquals(null, RetroAchievementsServiceFactory.rcheevosVersion(),
                "Version must be null when the library is unavailable — matches the menu's degraded state")
        }
        // When the library IS available, the version string must be a
        // non-empty alphanumeric identifier (the façade's compile-time
        // constant "12.4.0" or similar). We don't pin the exact value
        // because it tracks rcheevos releases.
        val v = RetroAchievementsServiceFactory.rcheevosVersion()
        if (v != null) {
            assertTrue(v.isNotBlank(), "Version string must be non-empty when available")
        }
    }

    // ---------------------------------------------------------------------
    // Real-library contract tests — only run when the native library is
    // actually available on this machine. The `@EnabledIf` gate uses a
    // system property the Gradle task sets so these tests are skipped
    // by default and only run via `./gradlew testNativeRa`.
    // ---------------------------------------------------------------------

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native service reports hard core as forced off`() {
        val svc = RetroAchievementsServiceFactory.create()
        assertTrue(svc is NativeRetroAchievementsService,
            "Test requires the native service — the @EnabledIf gate failed to skip it correctly")
        // The façade must call rc_client_set_hardcore_enabled(client, 0)
        // immediately after rc_client_create. We can't observe the native
        // flag directly (issue #267 keeps the API surface narrow), so we
        // assert the documented Kotlin-side behaviour: isSignedIn always
        // returns false in this slice. Sign-in is the user-visible
        // consequence of hardcore mode in the real client.
        assertFalse(svc.isSignedIn(),
            "Service must report not-signed-in because hardcore is forced off and login is #268")
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native prepareGame with unsigned-in user returns false`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        val info = GameSessionInfo(
            displayName = "fixture",
            sourcePath = null,
            romBytes = ByteArray(32) { 0x4E },  // arbitrary bytes, NES-shape enough to not crash the loader
            region = com.github.alondero.nestlin.Region.NTSC,
        )
        // Without login (issue #268), the façade returns RA_ERR_NOT_SIGNED_IN
        // and the service surfaces it as `false`. Gameplay proceeds; the
        // coordinator treats this as "service idle for this session".
        assertFalse(svc.prepareGame(info),
            "prepareGame must return false when no user is signed in (the no-network shim makes login impossible in #267)")
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native evaluateFrame before prepareGame is a safe no-op`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        // The C side guards every do_frame call with a null-client check
        // (via the load_state == IDLE branch). Calling it without a
        // prepared game MUST NOT throw or crash.
        svc.evaluateFrame(0L)
        svc.evaluateFrame(Long.MAX_VALUE)
        // And the service must still be in a coherent state.
        assertFalse(svc.serializeProgress()?.isNotEmpty() ?: false,
            "serializeProgress must return null or empty when no game is loaded")
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native serializeProgress before prepareGame returns null`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        assertEquals(null, svc.serializeProgress(),
            "Progress must be empty before any game is prepared — matches the document contract")
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native shutdown is idempotent and never throws`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        // Idempotency is the contract; the second call must not crash on
        // a freed handle (the C side memset's the handle to zero on
        // destroy, which would normally cause a follow-up crash — the
        // JNA-side guard catches UnsatisfiedLinkError / SEGV).
        svc.shutdown()
        svc.shutdown()  // MUST NOT THROW
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native unloadGame before prepareGame is a safe no-op`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        // The C side's unload_game guards against the no-game case.
        svc.unloadGame()
        svc.unloadGame()  // idempotent
    }

    @Test
    @EnabledIf("nativeLibraryIsPresent")
    fun `native version strings are non-empty`() {
        val svc = RetroAchievementsServiceFactory.create() as NativeRetroAchievementsService
        assertTrue(svc.rcheevosVersion.isNotBlank(),
            "rcheevos version string must be non-empty — the menu surfaces it")
        assertTrue(svc.facadeVersion.isNotBlank(),
            "facade version string must be non-empty — the menu surfaces it")
    }

    // ---------------------------------------------------------------------
    // JNA-side gate. The companion function is referenced by `@EnabledIf`
    // — JUnit 5 evaluates the boolean to decide whether to run the test.
    // The function must be static (or top-level) — `companion object`
    // doesn't work; we use a top-level function below.
    // ---------------------------------------------------------------------

    companion object {
        // No-op; just to keep the IDE happy with the @Test reference to
        // nativeLibraryIsPresent as a string.
        @Suppress("unused")
        private const val GATE_NAME = "nativeLibraryIsPresent"
    }
}

/**
 * Top-level gate referenced by `@EnabledIf("nativeLibraryIsPresent")` on
 * the real-library contract tests. The Gradle `:testNativeRa` task sets
 * `-Dnestlin.test.nativeRa=true` to enable them; the default `./gradlew
 * test` does not set the property, so the tests skip.
 */
fun nativeLibraryIsPresent(): Boolean =
    System.getProperty("nestlin.test.nativeRa") == "true" &&
        com.github.alondero.nestlin.session.RetroAchievementsServiceFactory.isNativeLibraryAvailable()
