package com.github.alondero.nestlin.session

import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-level RetroAchievements sign-in orchestrator (issue #268).
 *
 * Wraps the rcheevos façade's password/token login + logout methods, the
 * [RaCredentialsStore] for persistent credentials, and the [RaSignInState]
 * hierarchy that the UI binds to. Owns the [generation] counter that
 * guards every async operation: a stale callback (login attempt returns
 * after the user logged out, profile fetch returns after a new login,
 * etc.) cannot overwrite the current state.
 *
 * ## Threading
 *
 * Every public method is safe to call from any thread. The [state]
 * observable is an [AtomicReference] updated on the calling thread so
 * the UI's listener sees a consistent snapshot without locks. The HTTP
 * bridge runs on its own executor; login-completion events are
 * serialised by the bridge onto its poll thread before we touch the
 * service again.
 *
 * ## Lifecycle
 *
 * - [start] reads any persisted credentials and attempts a token-restore
 *   login. If none are saved, the state stays [RaSignInState.SignedOut].
 * - [signInWithPassword] / [signInWithToken] submit credentials; state
 *   moves to [RaSignInState.Authenticating] then [RaSignInState.SignedIn]
 *   on success or [RaSignInState.Offline] on transport failure.
 * - [signOut] clears persisted credentials and tears down the rcheevos
 *   session. Does NOT stop gameplay — the application may keep the ROM
 *   loaded; the coordinator's service is just idle for this session.
 * - [shutdown] stops the HTTP bridge and tears down the native handle.
 *
 * ## Generation guards
 *
 * Every login attempt increments [generation]; the façade's C side
 * compares its own generation to the one the request was enqueued under
 * and silently drops stale responses. From the Kotlin side, every async
 * completion path reads the current generation before mutating [state],
 * so a slow login from generation N can never overwrite a state owned
 * by generation N+1 (e.g. the user logged in, then logged out, then a
 * previous attempt's response arrives).
 */
class RaSignInManager internal constructor(
    private val native: NativeRetroAchievementsService?,
    bindings: RaFacadeBindings?,
    handle: Pointer?,
    private val credentialsStore: RaCredentialsStore,
    private val httpBridgeFactory: (RaFacadeBindings, Pointer) -> RaHttpBridge,
) {
    /**
     * Re-exposed as fields (instead of constructor vals) so the lazy
     * [state] init can read them reliably. Kotlin reserves constructor `val`
     * parameters for the primary constructor's own name resolution and
     * they may not be visible from a field initializer in some overload
     * resolution paths.
     */
    private val bindings: RaFacadeBindings? = bindings
    private val handle: Pointer? = handle

    private val _state: AtomicReference<RaSignInState> = AtomicReference(
        if (bindings != null && handle != null) RaSignInState.SignedOut
        else RaSignInState.Unavailable
    )

    private val listeners: CopyOnWriteArrayList<(RaSignInState) -> Unit> = CopyOnWriteArrayList()

    /** Monotonic counter; every login/logout attempt advances it. */
    @Volatile private var generation: Long = 0L

    /** HTTP bridge; null until [start] wires it up. */
    private var bridge: RaHttpBridge? = null

    /** Current sign-in state. Bind a listener via [addListener]. */
    val state: RaSignInState get() = _state.get()

    /**
     * Add a listener that fires on every state transition. The listener is
     * invoked synchronously on the calling thread. Listeners that throw
     * are caught and logged — a misbehaving listener must not poison the
     * state machine.
     *
     * Returns an opaque token; pass it to [removeListener] to unsubscribe.
     */
    fun addListener(listener: (RaSignInState) -> Unit): ListenerToken {
        listeners += listener
        return ListenerToken(listener)
    }

    /** Stop receiving state transitions. Idempotent. */
    fun removeListener(token: ListenerToken) {
        listeners.remove(token.listener)
    }

    /**
     * Restore any persisted credentials. Safe to call at startup before
     * the UI is ready — the listener fires whenever [state] actually changes.
     *
     * If credentials exist, immediately attempts a token login. The state
     * moves through [RaSignInState.Authenticating] → [RaSignInState.SignedIn]
     * on success or [RaSignInState.Offline] on transport failure (the
     * credentials are preserved so the next manual sign-in retry can use
     * them).
     */
    fun start() {
        if (bindings == null || handle == null) return
        val saved = credentialsStore.load() ?: run {
            updateState(RaSignInState.SignedOut)
            return
        }
        attemptTokenLogin(saved, restore = true)
    }

    /**
     * Submit username/password for a password login. Returns immediately;
     * the state transitions through [RaSignInState.Authenticating] to
     * [RaSignInState.SignedIn] (with persisted credentials) or
     * [RaSignInState.Offline] on transport failure (no persistence; the
     * user can retry).
     *
     * Password is consumed locally and never persisted. The case-corrected
     * username + token are persisted on success only.
     *
     * No-op when [state] is [RaSignInState.Authenticating] — the menu
     * also disables the submit action to make the rule visible to users,
     * but the service is the source of truth.
     */
    fun signInWithPassword(username: String, password: String) {
        if (bindings == null || handle == null) return
        val current = _state.get()
        if (current is RaSignInState.Authenticating) return
        require(username.isNotEmpty()) { "username must not be empty" }
        require(password.isNotEmpty()) { "password must not be empty" }
        generation++
        bridge?.start() ?: ensureBridge().start()
        updateState(RaSignInState.Authenticating)
        val rc = try {
            bindings.ra_facade_begin_login_with_password(handle, username, password)
        } catch (e: UnsatisfiedLinkError) {
            -1
        }
        if (rc != RaStatus.OK) {
            // C side rejected (login-in-flight or destroyed). Surface as
            // signed-out so the menu can re-enable the action.
            updateState(RaSignInState.SignedOut)
        }
    }

    /**
     * Restore from a saved [credentials] token. Used by [start] at boot;
     * also available for tests / manual retries. Same rules as
     * [signInWithPassword]: no persistence (caller already loaded the
     * credentials from store), no-op when authenticating.
     */
    fun signInWithToken(credentials: RaCredentials) {
        if (bindings == null || handle == null) return
        val current = _state.get()
        if (current is RaSignInState.Authenticating) return
        attemptTokenLogin(credentials, restore = false)
    }

    /**
     * Logout. Tears down the rcheevos session, clears persisted credentials,
     * and resets state to [RaSignInState.SignedOut]. Does NOT stop gameplay
     * — the ROM stays loaded, the service stays attached but idle.
     *
     * Bumps [generation] so any in-flight HTTP response from the just-ended
     * session is silently dropped on the C side. Stops the HTTP bridge so
     * no further requests are dispatched.
     *
     * Idempotent — safe to call when not signed in.
     */
    fun signOut() {
        if (bindings == null || handle == null) {
            updateState(RaSignInState.SignedOut)
            return
        }
        generation++
        bridge?.stop()
        bridge = null
        try {
            bindings.ra_facade_logout(handle)
        } catch (e: UnsatisfiedLinkError) {
            // Library went away — assume already signed out.
        }
        credentialsStore.clear()
        updateState(RaSignInState.SignedOut)
    }

    /**
     * Permanent teardown. Called by the application's shutdown path. Stops
     * the HTTP bridge and clears all listener references so a late state
     * update doesn't reach a dead UI.
     */
    fun shutdown() {
        generation++
        bridge?.stop()
        bridge = null
        listeners.clear()
        // We do NOT call ra_facade_destroy here — the NativeRetroAchievementsService
        // owns the handle and will tear it down via its own shutdown() path.
    }

    /**
     * Mark the sign-in state as offline (transport failure that should be
     * retried). Called by the HTTP bridge on repeated retryable failures
     * during a login attempt, OR by the menu when the user reports a
     * network problem.
     *
     * Credentials are PRESERVED on this path so the user can retry without
     * re-entering their username/password. The generation counter is
     * bumped so the in-flight request's eventual response is dropped.
     */
    fun markOffline(cause: String) {
        if (bindings == null || handle == null) return
        generation++
        updateState(RaSignInState.Offline(cause))
    }

    /**
     * Pull the current [RaAccount] from the façade and emit a
     * [RaSignInState.SignedIn] transition. Used by the login-completion
     * callback path AND by the profile window's refresh button.
     */
    fun refreshAccount() {
        if (bindings == null || handle == null) return
        val info = RaUserInfo()
        info.write()
        val rc = try {
            bindings.ra_facade_get_user_info(handle, info)
        } catch (e: UnsatisfiedLinkError) {
            return
        }
        info.read()
        if (rc != RaStatus.OK) return
        val account = RaAccount(
            username = bytesToString(info.username),
            displayName = bytesToString(info.displayName),
            score = info.score,
            scoreSoftcore = info.scoreSoftcore,
            unreadMessages = info.numUnreadMessages,
            avatarUrl = bytesToString(info.avatarUrl),
        )
        if (account.username.isEmpty()) return  // not actually signed in
        updateState(RaSignInState.SignedIn(account))
    }

    /**
     * Read the current token from the façade's user-info snapshot. Returns
     * null if the façade doesn't hold a token (e.g. signed-out). Used by
     * the persistence path after a successful login to capture the
     * case-corrected username + the rcheevos-issued API token for next launch.
     */
    private fun readCurrentToken(): String? {
        if (bindings == null || handle == null) return null
        val info = RaUserInfo()
        info.write()
        val rc = try {
            bindings.ra_facade_get_user_info(handle, info)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        info.read()
        if (rc != RaStatus.OK) return null
        return bytesToString(info.token).takeIf { it.isNotEmpty() }
    }

    private fun attemptTokenLogin(credentials: RaCredentials, restore: Boolean) {
        if (bindings == null || handle == null) return
        generation++
        bridge?.start() ?: ensureBridge().start()
        updateState(RaSignInState.Authenticating)
        val rc = try {
            bindings.ra_facade_begin_login_with_token(handle, credentials.username, credentials.token)
        } catch (e: UnsatisfiedLinkError) {
            -1
        }
        if (rc != RaStatus.OK) {
            // Drop the persisted credentials so the user is forced to sign in
            // again — a stale token that the C side refused is no longer
            // trustworthy.
            if (restore) credentialsStore.clear()
            updateState(RaSignInState.SignedOut)
        }
    }

    private fun ensureBridge(): RaHttpBridge {
        val b = bridge
        if (b != null) return b
        if (bindings == null || handle == null) {
            error("Cannot start HTTP bridge without a native handle")
        }
        val created = httpBridgeFactory(bindings, handle)
        // Hook the bridge so we observe login HTTP round-trips. When the
        // rcheevos login server responds we pull the new [RaAccount] and
        // update the state to [RaSignInState.SignedIn] (or [Offline] on a
        // transport failure). The listener fires on the bridge's poll
        // thread, so we just call refreshAccount / markOffline directly.
        created.responseListener = listener@{ req, resp ->
            if (!isLoginUrl(req.url)) return@listener
            val currentGen = generation
            if (resp.status in 200..299) {
                refreshAccount()
                persistIfSignedIn()
            } else if (resp.status < 0) {
                // Transport failure — keep credentials, surface offline state.
                if (currentGen == generation) markOffline("Network error during sign-in")
            } else if (resp.status in 400..499) {
                // Server-confirmed invalid — drop credentials, return to signed-out.
                if (currentGen == generation) {
                    credentialsStore.clear()
                    updateState(RaSignInState.SignedOut)
                }
            }
        }
        bridge = created
        return created
    }

    /**
     * Persist the case-corrected username + token if a token login (or
     * password login that produced a valid token) succeeded. rcheevos
     * holds the token inside its user_info struct; we pull it out via
     * get_user_info so the next Nestlin launch can use it directly.
     *
     * The password is NEVER written to the credentials store — only the
     * reusable API token returned by the server (a 32-char hex string in
     * rcheevos v12.4.0). The username is the case-corrected form rcheevos
     * reports back, not what the user typed.
     */
    private fun persistIfSignedIn() {
        if (bindings == null || handle == null) return
        val token = readCurrentToken() ?: return
        val info = RaUserInfo()
        info.write()
        val rc = try {
            bindings.ra_facade_get_user_info(handle, info)
        } catch (e: UnsatisfiedLinkError) {
            return
        }
        info.read()
        if (rc != RaStatus.OK) return
        val username = bytesToString(info.username)
        if (username.isNotEmpty()) {
            credentialsStore.save(RaCredentials(username = username, token = token))
        }
    }

    /**
     * Identify the RA login API path so the bridge hook can fire only on
     * login responses (not on every achievement-load fetch). rcheevos uses
     * `login2.php` for both password and token logins.
     */
    private fun isLoginUrl(url: String): Boolean = url.contains("login2.php")

    /**
     * Update [_state] and fire listeners. Idempotent on the same value —
     * a transition to the current state is a no-op (the listener does not
     * fire). Listeners run on the calling thread; a listener that throws
     * is caught and logged so a misbehaving observer can't poison the
     * state machine.
     */
    private fun updateState(next: RaSignInState) {
        val previous = _state.getAndSet(next)
        if (previous == next) return
        for (l in listeners) {
            try {
                l(next)
            } catch (e: Exception) {
                System.err.println("[RA] Sign-in listener threw: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun bytesToString(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        val trimmed = if (end >= 0) bytes.copyOf(end) else bytes
        return String(trimmed, Charsets.UTF_8)
    }

    /** Opaque token returned by [addListener]; pass to [removeListener]. */
    data class ListenerToken internal constructor(internal val listener: (RaSignInState) -> Unit)

    companion object {
        /**
         * Build a manager from the public service seam. When [service] is the
         * native implementation (the only case where login is meaningful),
         * the manager binds its HTTP bridge to the underlying façade; when
         * it's the no-op (default), the manager starts in
         * [RaSignInState.Unavailable] and every sign-in attempt is a no-op.
         */
        fun from(
            service: RetroAchievementsService,
            credentialsStore: RaCredentialsStore = RaCredentialsStore(),
            transport: RaHttpTransport = JavaHttpClientTransport(),
        ): RaSignInManager {
            val native = service as? NativeRetroAchievementsService
            return RaSignInManager(
                native = native,
                bindings = native?.bridgeBindings(),
                handle = native?.bridgeHandle(),
                credentialsStore = credentialsStore,
                httpBridgeFactory = { b, h ->
                    RaHttpBridge(
                        bindings = b,
                        handle = h,
                        transport = transport,
                    )
                },
            )
        }
    }
}