package com.github.alondero.nestlin.session

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-level RetroAchievements sign-in orchestrator (issue #268).
 *
 * Wraps the rcheevos façade's password/token login + logout methods, the
 * [RaCredentialsStore] for persistent credentials, and the [RaSignInState]
 * hierarchy that the UI binds to.
 *
 * ## Threading
 *
 * Every public method is safe to call from any thread. The [state]
 * observable is an [AtomicReference]; listeners fire on whichever thread
 * caused the transition — the caller's for an immediate transition, the
 * HTTP bridge's poll thread when a login settles.
 *
 * ## Lifecycle
 *
 * - [start] reads any persisted credentials and attempts a token-restore
 *   login. If none are saved, the state stays [RaSignInState.SignedOut].
 * - [signInWithPassword] / [signInWithToken] submit credentials; state
 *   moves to [RaSignInState.Authenticating], then settles as
 *   [RaSignInState.SignedIn], [RaSignInState.Rejected] (server refused the
 *   credentials) or [RaSignInState.Offline] (transport/server failure).
 * - [signOut] clears persisted credentials and tears down the rcheevos
 *   session. Does NOT stop gameplay or the HTTP bridge — requests rcheevos
 *   already issued must still be answered for it to release them.
 * - [shutdown] stops the HTTP bridge. It must run before the native handle
 *   is destroyed; [from] arranges that by hooking the service's shutdown.
 *
 * ## How a login settles
 *
 * rcheevos settles a login inside the HTTP completion the bridge delivers
 * (or synchronously inside `begin_login_*` when it rejects the attempt up
 * front). The manager reads that outcome with `ra_facade_take_login_result`
 * after both — never by guessing from the request URL: rcheevos sends every
 * API call to `dorequest.php`, with the method in the POST body. A
 * settlement only moves the state while it is still
 * [RaSignInState.Authenticating], so an attempt abandoned by [signOut]
 * can't overwrite the state the user has since moved to.
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

    /** HTTP bridge; null until the first sign-in attempt starts it. */
    @Volatile private var bridge: RaHttpBridge? = null

    /** Current sign-in state. Bind a listener via [addListener]. */
    val state: RaSignInState get() = _state.get()

    /** True while the HTTP bridge is polling the façade. */
    internal val isBridgeRunning: Boolean get() = bridge?.isRunning == true

    /**
     * Add a listener that fires on every state transition. The listener is
     * invoked synchronously on the thread that caused the transition.
     * Listeners that throw are caught and logged — a misbehaving listener
     * must not poison the state machine.
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
     * If credentials exist, immediately attempts a token login. A transport
     * failure leaves them in place ([RaSignInState.Offline]) so a later retry
     * can use them; a server rejection clears them.
     */
    fun start() {
        if (bindings == null || handle == null) return
        val saved = credentialsStore.load() ?: run {
            updateState(RaSignInState.SignedOut)
            return
        }
        attemptTokenLogin(saved)
    }

    /**
     * Submit username/password for a password login. Returns immediately;
     * the state transitions through [RaSignInState.Authenticating] to a
     * settled state (see the class docs).
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
        if (_state.get() is RaSignInState.Authenticating) return
        require(username.isNotEmpty()) { "username must not be empty" }
        require(password.isNotEmpty()) { "password must not be empty" }
        beginLogin { bindings.ra_facade_begin_login_with_password(handle, username, password) }
    }

    /**
     * Restore from a saved [credentials] token. Used by [start] at boot;
     * also available for tests / manual retries. Same rules as
     * [signInWithPassword]: no-op when authenticating.
     */
    fun signInWithToken(credentials: RaCredentials) {
        if (bindings == null || handle == null) return
        if (_state.get() is RaSignInState.Authenticating) return
        attemptTokenLogin(credentials)
    }

    /**
     * Logout. Tears down the rcheevos session, clears persisted credentials,
     * and resets state to [RaSignInState.SignedOut]. Does NOT stop gameplay
     * — the ROM stays loaded, the service stays attached but idle.
     *
     * The HTTP bridge keeps running: rcheevos only releases a request (and
     * settles an in-flight login, as aborted) when its response arrives.
     *
     * Idempotent — safe to call when not signed in.
     */
    fun signOut() {
        if (bindings == null || handle == null) {
            updateState(RaSignInState.SignedOut)
            return
        }
        try {
            bindings.ra_facade_logout(handle)
        } catch (e: UnsatisfiedLinkError) {
            // Library went away — assume already signed out.
        }
        credentialsStore.clear()
        updateState(RaSignInState.SignedOut)
    }

    /**
     * Permanent teardown: stops the HTTP bridge and clears all listener
     * references so a late state update doesn't reach a dead UI. Runs
     * before the native handle is destroyed (see [from]). Idempotent.
     */
    fun shutdown() {
        bridge?.stop()
        bridge = null
        listeners.clear()
        // We do NOT call ra_facade_destroy here — the NativeRetroAchievementsService
        // owns the handle and will tear it down via its own shutdown() path.
    }

    /**
     * Mark the sign-in state as offline (transport failure that should be
     * retried), e.g. when the user reports a network problem. Credentials
     * are PRESERVED so the user can retry without re-entering them.
     */
    fun markOffline(cause: String) {
        if (bindings == null || handle == null) return
        updateState(RaSignInState.Offline(cause))
    }

    /**
     * Pull the current [RaAccount] from the façade and emit a
     * [RaSignInState.SignedIn] transition. Used by the profile window's
     * refresh button.
     */
    fun refreshAccount() {
        val account = readAccount() ?: return
        updateState(RaSignInState.SignedIn(account))
    }

    private fun attemptTokenLogin(credentials: RaCredentials) {
        beginLogin { bindings!!.ra_facade_begin_login_with_token(handle!!, credentials.username, credentials.token) }
    }

    /**
     * Shared password/token path: make sure the bridge is polling, enter
     * [RaSignInState.Authenticating], start the attempt, then check whether
     * it already settled (rcheevos rejects some attempts synchronously).
     */
    private fun beginLogin(begin: () -> Int) {
        ensureBridge().start()
        updateState(RaSignInState.Authenticating)
        val rc = try {
            begin()
        } catch (e: UnsatisfiedLinkError) {
            RaStatus.ERR_DESTROYED
        }
        when (rc) {
            RaStatus.OK -> settleLoginIfDone()
            // An earlier attempt (one abandoned by sign-out) is still waiting
            // on its server response; the façade refuses to race it.
            RaStatus.ERR_LIBRARY_STATE -> settle(
                RaSignInState.Offline("A previous sign-in attempt is still finishing — try again in a moment"),
            )
            else -> settle(RaSignInState.SignedOut)
        }
    }

    /** Apply the façade's login outcome, if one has settled since the last check. */
    private fun settleLoginIfDone() {
        if (bindings == null || handle == null) return
        val rcResult = IntByReference(0)
        val message = ByteArray(LOGIN_MESSAGE_MAX)
        val outcome = try {
            bindings.ra_facade_take_login_result(handle, rcResult, message, message.size)
        } catch (e: UnsatisfiedLinkError) {
            return
        }
        when (outcome) {
            RaLoginOutcome.SUCCEEDED -> {
                val account = readAccount()
                if (account == null) {
                    settle(RaSignInState.Offline("Signed in, but the account details could not be read"))
                } else if (settle(RaSignInState.SignedIn(account))) {
                    persistCredentials()
                }
            }
            RaLoginOutcome.FAILED -> onLoginFailed(rcResult.value, bytesToString(message))
        }
    }

    private fun onLoginFailed(rcResult: Int, reason: String) {
        when (rcResult) {
            RcResult.INVALID_CREDENTIALS, RcResult.EXPIRED_TOKEN, RcResult.ACCESS_DENIED -> {
                if (settle(RaSignInState.Rejected(reason.ifEmpty { "RetroAchievements rejected the sign-in" }))) {
                    // A rejected password or a revoked token must not be retried next launch.
                    credentialsStore.clear()
                }
            }
            // Abandoned by sign-out; the state has normally moved on already.
            RcResult.ABORTED -> settle(RaSignInState.SignedOut)
            else -> settle(RaSignInState.Offline(reason.ifEmpty { "Network error during sign-in" }))
        }
    }

    private fun ensureBridge(): RaHttpBridge {
        bridge?.let { return it }
        if (bindings == null || handle == null) {
            error("Cannot start HTTP bridge without a native handle")
        }
        val created = httpBridgeFactory(bindings, handle)
        // Every delivered response may be the one that settles a login
        // (and rcheevos may chain requests), so check after each one.
        created.responseListener = { _, _ -> settleLoginIfDone() }
        bridge = created
        return created
    }

    private fun readAccount(): RaAccount? {
        val info = readUserInfo() ?: return null
        val account = RaAccount(
            username = bytesToString(info.username),
            displayName = bytesToString(info.displayName),
            score = info.score,
            scoreSoftcore = info.scoreSoftcore,
            unreadMessages = info.numUnreadMessages,
            avatarUrl = bytesToString(info.avatarUrl),
        )
        return account.takeIf { it.username.isNotEmpty() }
    }

    private fun readUserInfo(): RaUserInfo? {
        if (bindings == null || handle == null) return null
        val info = RaUserInfo()
        info.write()
        val rc = try {
            bindings.ra_facade_get_user_info(handle, info)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        info.read()
        return info.takeIf { rc == RaStatus.OK }
    }

    /**
     * Persist the case-corrected username + the API token rcheevos holds
     * after a successful login, so the next launch can restore the session.
     *
     * The password is NEVER written to the credentials store — only the
     * reusable API token returned by the server. The username is the
     * case-corrected form rcheevos reports back, not what the user typed.
     */
    private fun persistCredentials() {
        val info = readUserInfo() ?: return
        val username = bytesToString(info.username)
        val token = bytesToString(info.token)
        if (username.isNotEmpty() && token.isNotEmpty()) {
            credentialsStore.save(RaCredentials(username = username, token = token))
        }
    }

    /**
     * Move out of [RaSignInState.Authenticating] to [next]. Returns false
     * (and changes nothing) when the state has already moved on — e.g. the
     * user signed out while the attempt was in flight.
     */
    private fun settle(next: RaSignInState): Boolean {
        if (!_state.compareAndSet(RaSignInState.Authenticating, next)) return false
        notifyListeners(next)
        return true
    }

    /**
     * Update [_state] and fire listeners. Idempotent on the same value —
     * a transition to the current state is a no-op (the listener does not
     * fire).
     */
    private fun updateState(next: RaSignInState) {
        val previous = _state.getAndSet(next)
        if (previous == next) return
        notifyListeners(next)
    }

    private fun notifyListeners(next: RaSignInState) {
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

    /** Opaque token returned by [addListener]; pass it to [removeListener]. */
    data class ListenerToken internal constructor(internal val listener: (RaSignInState) -> Unit)

    companion object {
        /** Mirrors RA_FACADE_ERROR_MAX — the façade truncates the server's reason to this. */
        private const val LOGIN_MESSAGE_MAX = RaEvent.RA_FACADE_ERROR_MAX

        /**
         * Build a manager from the public service seam. When [service] is the
         * native implementation (the only case where login is meaningful),
         * the manager binds its HTTP bridge to the underlying façade and
         * registers to be shut down before the service destroys that façade;
         * when it's the no-op (default), the manager starts in
         * [RaSignInState.Unavailable] and every sign-in attempt is a no-op.
         */
        fun from(
            service: RetroAchievementsService,
            credentialsStore: RaCredentialsStore = RaCredentialsStore(),
            transport: RaHttpTransport = JavaHttpClientTransport(),
        ): RaSignInManager {
            val native = service as? NativeRetroAchievementsService
            val manager = RaSignInManager(
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
            // The bridge polls the same native handle; it must stop before
            // the handle is freed, whatever order the app shuts down in.
            native?.beforeShutdown { manager.shutdown() }
            return manager
        }
    }
}
