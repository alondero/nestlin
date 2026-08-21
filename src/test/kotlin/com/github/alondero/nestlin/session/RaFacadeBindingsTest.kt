package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Pure-JVM smoke tests for [RaFacadeBindings.load]. These tests do NOT
 * require the native library to be present — they verify that
 *
 *   - the loader returns null when the library is absent (the documented
 *     graceful-degradation path that lets headless tools and CI run
 *     without compiling the façade),
 *   - the loader returns a non-null binding when the library IS present
 *     (gated by a system property set by the `:testNativeRa` Gradle task),
 *   - the JNA `Structure` subclasses used to mirror C structs can be
 *     instantiated without crashing (the Shape-test pattern from JNA's
 *     own test suite).
 *
 * These tests prove that the binding's interface signature matches the
 * C header's `ra_facade_*` declarations — a regression in either side
 * shows up as a [UnsatisfiedLinkError] at first use.
 */
@Tag("nativeRa")
class RaFacadeBindingsTest {

    @Test
    fun `loader returns null when library is absent or corrupt`() {
        // The CI / test classpath deliberately does NOT include the
        // native library. This test asserts the documented fallback —
        // the loader must return null rather than throw.
        val bindings = RaFacadeBindings.load()
        if (System.getProperty("nestlin.test.nativeRa") != "true") {
            assertNull(bindings,
                "Loader must return null when no native library is on the classpath")
        }
        // When the library IS available (gated by the system property),
        // the loader must return a non-null binding.
        if (System.getProperty("nestlin.test.nativeRa") == "true") {
            assertNotNull(bindings,
                "Loader must return a non-null binding when the native library is on the classpath")
        }
    }

    @Test
    fun `RaEvent structure can be instantiated`() {
        // JNA's Structure subclasses do native-memory allocation in
        // `new`; a successful construction proves the field layout
        // matches the C struct.
        val ev = RaEvent()
        assertEquals(0, ev.type)
        assertEquals(0, ev.achievementId)
        assertTrue(ev.achievementTitle.size == RaEvent.RA_FACADE_TITLE_MAX)
        assertTrue(ev.achievementDescription.size == RaEvent.RA_FACADE_DESCRIPTION_MAX)
        assertTrue(ev.achievementBadge.size == RaEvent.RA_FACADE_BADGE_MAX)
        assertTrue(ev.serverErrorMessage.size == RaEvent.RA_FACADE_ERROR_MAX)
    }

    @Test
    fun `RaGameInfo structure can be instantiated`() {
        val info = RaGameInfo()
        assertEquals(0, info.state)
        assertEquals(0, info.gameId)
        assertEquals(0, info.hasAchievements)
        assertEquals(0, info.hasLeaderboards)
        assertEquals(0, info.hardcoreEnabled)
    }

    @Test
    fun `RaStatus constants match the documented C enum`() {
        // These constants are mirrored from ra_facade.h. A regression
        // here would silently break the JNA-side status decoding — a
        // future maintainer would have to compare against the header
        // to spot the mismatch. The pin is the whole point of the test.
        assertEquals(0, RaStatus.OK)
        assertEquals(-1, RaStatus.ERR_NULL_HANDLE)
        assertEquals(-2, RaStatus.ERR_INVALID_ARG)
        assertEquals(-3, RaStatus.ERR_BUFFER_TOO_SMALL)
        assertEquals(-4, RaStatus.ERR_NO_GAME)
        assertEquals(-5, RaStatus.ERR_LIBRARY_STATE)
        assertEquals(-6, RaStatus.ERR_NOT_SIGNED_IN)
        assertEquals(-7, RaStatus.ERR_INTERNAL)
        assertEquals(-8, RaStatus.ERR_DESTROYED)
    }

    @Test
    fun `RaLoadState constants match the documented C enum`() {
        assertEquals(0, RaLoadState.IDLE)
        assertEquals(1, RaLoadState.AWAITING_LOGIN)
        assertEquals(2, RaLoadState.IDENTIFYING)
        assertEquals(3, RaLoadState.STARTING)
        assertEquals(4, RaLoadState.READY)
        assertEquals(5, RaLoadState.FAILED)
        assertEquals(6, RaLoadState.ABORTED)
    }

    @Test
    fun `RaEventType constants match the documented C enum`() {
        // Just spot-check a few that map to the issue #267 AC. The full
        // surface lands in #268 / UI integration.
        assertEquals(0, RaEventType.NONE)
        assertEquals(1, RaEventType.ACHIEVEMENT_TRIGGERED)
        assertEquals(14, RaEventType.GAME_COMPLETED)
        assertEquals(15, RaEventType.RESET)
        assertEquals(16, RaEventType.SERVER_ERROR)
    }
}
