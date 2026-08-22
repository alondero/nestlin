package com.github.alondero.nestlin.session

import java.util.prefs.Preferences

/**
 * Persistent credentials store for the RetroAchievements account (issue #268).
 *
 * Backed by [java.util.prefs.Preferences] under a RetroAchievements-specific
 * node so the credentials never bleed into the rest of Nestlin's saved
 * settings and never appear in JSON, INI, save-state bytes, or screenshots.
 *
 * **Security policy:** the password is NEVER persisted. Only the case-corrected
 * username and the returned API token survive a Nestlin launch. The username
 * is stored so the menu can pre-fill the login dialog.
 *
 * **Platform storage:**
 *   - Windows: `HKCU\Software\JavaSoft\Prefs\com\github\alondero\nestlin\ra`
 *   - Linux:   `~/.java/.userPrefs/com/github/alondero/nestlin/ra/`
 *   - macOS:   `~/Library/Preferences/com.github.alondero.nestlin.plist`
 *
 * The interface is intentionally narrow — only [save], [load], and [clear] —
 * so callers can't accidentally write arbitrary keys into the same node.
 *
 * Test seam: pass a custom [Preferences] via the secondary constructor to
 * redirect to an in-memory node (the `Preferences.userRoot().node(...)`
 * factory always uses the system root in production).
 */
class RaCredentialsStore(
    /** Backing [Preferences] node. Visible to tests in the same package so they
     *  can introspect which keys were actually written. */
    internal val prefs: Preferences = Preferences.userRoot().node(PREF_NODE_PATH),
) {
    /**
     * Persist [credentials]. The username and token are stored verbatim;
     * the password is silently discarded (never reaches the prefs node).
     */
    fun save(credentials: RaCredentials) {
        prefs.put(KEY_USERNAME, credentials.username)
        prefs.put(KEY_TOKEN, credentials.token)
        prefs.flush()
    }

    /**
     * Load the persisted credentials, or `null` when no token has ever been
     * saved. The token is the source of truth; if it is missing but the
     * username is present (corrupt prefs), both are discarded.
     */
    fun load(): RaCredentials? {
        val username = prefs.get(KEY_USERNAME, null)
        val token = prefs.get(KEY_TOKEN, null)
        if (username.isNullOrEmpty() || token.isNullOrEmpty()) return null
        return RaCredentials(username = username, token = token)
    }

    /** Clear any persisted credentials. Idempotent — safe to call when nothing is saved. */
    fun clear() {
        prefs.remove(KEY_USERNAME)
        prefs.remove(KEY_TOKEN)
        prefs.flush()
    }

    companion object {
        /** Java Preferences node path, anchored at the Nestlin package. */
        private const val PREF_NODE_PATH: String = "/com/github/alondero/nestlin/ra"

        /** Username key (case-corrected; populated by rc_client after a successful login). */
        private const val KEY_USERNAME: String = "username"

        /** API token key (rcheevos issues a 32-char hex string per successful login). */
        private const val KEY_TOKEN: String = "token"
    }
}

/**
 * Persistable credentials. The username is the *case-corrected* value
 * rcheevos returns — never the user-typed form — so the next launch's
 * login can reuse it without case-mismatch friction. The token is
 * rcheevos's reusable API token (NOT the user's password).
 */
data class RaCredentials(
    val username: String,
    val token: String,
) {
    init {
        require(username.isNotEmpty()) { "username must not be empty" }
        require(token.isNotEmpty()) { "token must not be empty" }
    }
}