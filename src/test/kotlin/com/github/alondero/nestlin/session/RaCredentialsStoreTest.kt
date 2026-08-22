package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences

/**
 * Tests for [RaCredentialsStore] (issue #268). The store is backed by
 * [java.util.prefs.Preferences] which is normally the OS registry / plist /
 * XDG config dir. Tests inject an in-memory [Preferences] via a
 * minimal-subclass pattern so the suite is hermetic and side-effect free.
 */
class RaCredentialsStoreTest {

    private fun memoryPrefs(): Preferences = InMemoryPreferences()

    @Test
    fun `load returns null when nothing has been saved`() {
        val store = RaCredentialsStore(memoryPrefs())
        assertNull(store.load())
    }

    @Test
    fun `save and load round-trip the username and token`() {
        val store = RaCredentialsStore(memoryPrefs())
        val credentials = RaCredentials(
            username = "Alice42",
            token = "ABCDEFGHIJ1234567890ABCDEFGHIJ12",
        )
        store.save(credentials)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("Alice42", loaded!!.username)
        assertEquals("ABCDEFGHIJ1234567890ABCDEFGHIJ12", loaded.token)
    }

    @Test
    fun `save overwrites previous credentials`() {
        val store = RaCredentialsStore(memoryPrefs())
        store.save(RaCredentials("first", "FIRSTTOKEN12345678901234"))
        store.save(RaCredentials("second", "SECONDTOKEN123456789012"))
        val loaded = store.load()
        assertEquals("second", loaded?.username)
        assertEquals("SECONDTOKEN123456789012", loaded?.token)
    }

    @Test
    fun `clear removes the saved credentials`() {
        val store = RaCredentialsStore(memoryPrefs())
        store.save(RaCredentials("alice", "ALICETOKEN1234567890AB"))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `clear is idempotent`() {
        val store = RaCredentialsStore(memoryPrefs())
        store.clear()
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `corrupt preferences (token missing) returns null`() {
        // Simulate the partial-write case where only the username key made
        // it to disk. The store treats this as "no credentials" rather than
        // surfacing a half-state to the UI.
        val prefs = memoryPrefs()
        prefs.put("username", "Alice42")
        val store = RaCredentialsStore(prefs)
        assertNull(store.load())
    }

    @Test
    fun `password is never accepted by the data class`() {
        // The RaCredentials data class only carries username + token — there's
        // no place for a password in the persisted shape. This is a guard
        // test: it documents the contract that the type system enforces it.
        val store = RaCredentialsStore(memoryPrefs())
        val creds = RaCredentials(username = "alice", token = "longToken1234567890AB")
        store.save(creds)
        // Verify nothing else was written: only the two documented keys.
        val keys = store.prefs.keys()
        assertEquals(2, keys.size)
        assertEquals(true, keys.contains("username"))
        assertEquals(true, keys.contains("token"))
    }

    @Test
    fun `RaCredentials rejects blank username and token`() {
        try {
            RaCredentials(username = "", token = "validToken1234567890123")
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            RaCredentials(username = "validUser", token = "")
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

/**
 * Minimal in-memory [Preferences] implementation for tests. The standard
 * `Preferences.userRoot()` writes to the OS registry / plist which is not
 * hermetic; this lets every test run with a fresh empty prefs tree.
 *
 * Only the methods [RaCredentialsStore] actually calls are implemented.
 * Other methods throw [UnsupportedOperationException] so any future change
 * to the store that reaches for an unimplemented method fails loud in
 * tests instead of silently touching the OS.
 */
// InMemoryPreferences lives in its own file (see InMemoryPreferences.kt)
// so the same instance is shared across this test suite.