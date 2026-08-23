package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.testutil.failTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression test for issue #273's "Unaddressed Crash in Production":
 * `NativeRetroAchievementsService.shutdown()` calls into rcheevos's
 * `rc_client_destroy`, which in turn calls `rc_client_unload_game`.
 *
 * Before the vendored rcheevos patch (see native/rcheevos/NOTICE),
 * `rc_client_unload_game` SIGABRT'd on a client that had never been
 * loaded with a game — i.e. any user who enabled RA but never
 * successfully resolved a ROM, then quit the app.
 *
 * Tagged `@Tag("nativeRa")` because it actually loads the native
 * library; run via `./gradlew testNativeRa`. On a host without the
 * library, the `lib ?: return` guard skips the test.
 */
@Tag("nativeRa")
class NativeRetroAchievementsServiceBareShutdownTest {

    /**
     * The bare-client destroy path: create → destroy with no
     * prepare_game round-trip. Before the rcheevos patch this
     * SIGABRT'd the JVM. After the patch it returns RA_OK without
     * touching the scheduled_callbacks list.
     */
    @Test
    fun `destroy on a bare client does not crash`() {
        val lib = RaFacadeBindings.load() ?: return  // lib missing — silently skip
        assertNotNull(lib)
        val handle = lib.ra_facade_create(null, null)
        if (handle == null) failTest("ra_facade_create returned null")
        // The critical call: rc_client_destroy → rc_client_unload_game
        // on a client that never had prepare_game succeed.
        val firstDestroy = lib.ra_facade_destroy(handle)
        // Idempotency: a second destroy on the same (now-freed) handle
        // must also be safe. After the patch both calls return RA_OK.
        val secondDestroy = lib.ra_facade_destroy(handle)
        assertEquals(RaStatus.OK, firstDestroy,
            "first destroy on a bare client must return RA_OK (post-rcheevos patch)")
        assertEquals(RaStatus.OK, secondDestroy,
            "second destroy on a bare client must return RA_OK (idempotency)")
    }
}
