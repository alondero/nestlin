package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Memory
import com.sun.jna.Pointer

/**
 * Production [RetroAchievementsService] that bridges to the native
 * rcheevos v12.4.0 client via the flat C ABI declared in
 * `native/ra_facade/ra_facade.h`.
 *
 * ## Failure semantics
 *
 * This class is the only place JNA touches native code. The class is
 * constructed by [load] which returns:
 *
 *  - A working `NativeRetroAchievementsService` when the façade shared
 *    library is on the search path and exports the expected symbols.
 *  - `null` when the library is absent, corrupt, or incompatible with the
 *    current platform. The coordinator's factory picks NoOp in that case
 *    so every existing emulator flow works without a native library.
 *
 * ## Memory discipline
 *
 * The façade never holds a Java reference past the call that produced it
 * (the C side copies strings into caller-owned arrays and the JNA side
 * reads them out immediately). The only Java-side state held across calls
 * is the [handle] pointer (one pointer per facade instance) and a
 * per-process `ConcurrentHashMap<Pointer, MemoryReader>` for the
 * memory-reader callback lookup.
 *
 * ## Threading
 *
 * Single-threaded. The emulation thread calls every method. The
 * underlying rcheevos client has its own internal thread pool for HTTP
 * work, but those threads never call back into JVM code in this slice
 * (the network is stubbed off in the C side per issue #267's contract).
 */
internal class NativeRetroAchievementsService private constructor(
    private val bindings: RaFacadeBindings,
    private val handle: Pointer,
) : RetroAchievementsService {

    /**
     * Package-private accessors used by [RaSignInManager] to wire the HTTP
     * bridge (issue #268). The bridge talks to the same façade instance —
     * sharing these pointers is the entire reason the manager and service
     * live in the same package. Production code that reaches for the
     * bindings/handle must use [RaSignInManager], not these.
     */
    internal fun bridgeBindings(): RaFacadeBindings = bindings
    internal fun bridgeHandle(): Pointer = handle

    // The native library version is captured at construction so the UI's
    // availability indicator doesn't have to re-call into the C side on
    // every menu redraw.
    val rcheevosVersion: String = bindings.ra_facade_rcheevos_version()
    val facadeVersion: String = bindings.ra_facade_version()

    // Single-flight guard around prepare_game. The coordinator's per-frame
    // wiring is expected to be serial; this guard makes a double-call
    // observable rather than corrupting native state.
    private val prepareLock = Any()

    // ----------------------------------------------------------------------
    // Issue #270: connectivity / pending-sync state
    //
    // The runtime emits DISCONNECTED / RECONNECTED events on the network
    // boundary. We mirror those into two volatile flags so the UI's
    // "pending sync" indicator can render without reaching into the
    // native event queue. `pendingSync` clears on the next idle poll once
    // the load state is READY AND no pending ACHIEVEMENT_TRIGGERED events
    // are queued — see [evaluateFrame].
    //
    // @Volatile: read on the JavaFX Application Thread (indicator render),
    // written on the emulation thread (event drain). No compound action
    // exists — both are independent booleans.
    @Volatile
    private var lastConnectivityHealthy: Boolean = true
    @Volatile
    private var pendingSync: Boolean = false

    /**
     * Sink for native events re-emitted as JVM-side [RaNotification]s
     * (issue #270). Set by the coordinator's UI hook; cleared on
     * [shutdown]. The drain path fires this synchronously after copying
     * every native string out, so listeners (the notification controller)
     * receive fully-owned data — see `bytesToString` below.
     */
    @Volatile
    internal var notificationListener: ((RaNotification) -> Unit)? = null

    /**
     * Sink for native achievement events re-emitted as JVM-side
     * [RaAchievementEvent]s (issue #288). Set by the application after
     * the coordinator is constructed; cleared on [shutdown]. The
     * drain path publishes synchronously after copying every native
     * string out, so listeners (the achievements-window refresh path)
     * receive a fully-owned event with the achievement ID the runtime
     * emitted. Generation guards live one layer up — the listener
     * typically calls [RaAchievementsController.refresh], which
     * discards stale publishes whose generation doesn't match the
     * controller's currentGeneration.
     *
     * Null by default — production code that doesn't care about
     * window refresh (CLI drivers, headless bench tools) never wires
     * this and the drain path's `?.publish(...)` becomes a no-op.
     */
    @Volatile
    internal var achievementEventBus: RetroAchievementsEventBus? = null

    /**
     * Inject the p95 latency probe (issue #270 "benchmark tracks p95 latency
     * at 1ms budget" AC). Null by default — production doesn't want a
     * per-frame allocation. Tests inject an [RaLatencyTracker] to assert
     * the budget holds across a synthetic workload.
     */
    @Volatile
    internal var latencyTracker: RaLatencyTracker? = null

    override fun isSignedIn(): Boolean {
        // The C side returns 0 unconditionally until issue #268 lands
        // login support. Reflected here so the UI menu shows the
        // documented "not signed in" state.
        return try {
            bindings.ra_facade_is_signed_in(handle) != 0
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun prepareGame(sessionInfo: GameSessionInfo, timeoutMillis: Long): Boolean {
        synchronized(prepareLock) {
            // Issue #269: the coordinator requires a bounded round-trip so
            // the first emulated frame is never blocked indefinitely. We
            // pass through the timeout (with a sensible default for the
            // legacy tests that don't supply one).
            val budgetMs = if (timeoutMillis > 0) timeoutMillis.toInt() else DEFAULT_PREPARE_TIMEOUT_MS
            val pollMs = DEFAULT_PREPARE_POLL_MS
            val rc = try {
                bindings.ra_facade_prepare_game(
                    handle,
                    sessionInfo.romBytes,
                    sessionInfo.romBytes.size,
                    sessionInfo.displayName,
                )
            } catch (e: UnsatisfiedLinkError) {
                return false
            }
            // Issue #270: a fresh prepare starts with a clean pending-sync
            // state. The runtime may immediately queue events for an
            // offline user (unlocks the runtime knows about but the
            // server hasn't seen) and the UI's indicator should reflect
            // that — [handleEvent] flips pendingSync = true as events
            // arrive. Reset here so we don't carry a stale flag across
            // ROM swaps.
            pendingSync = false
            // The C side returns RA_ERR_NOT_SIGNED_IN (-6) when no user is
            // authenticated; the coordinator treats any non-OK result as
            // "service is idle for this session" — gameplay proceeds, no
            // achievements. RA_OK means the request was accepted and the
            // runtime will settle (READY / FAILED) within the budget.
            if (rc != RaStatus.OK) return false
            // Block on the C side until the load settles. The poll happens
            // on the calling thread; we hold the prepareLock the entire
            // time so the coordinator's serial prepare-game semantics are
            // preserved. The C side polls rcheevos's load state and drives
            // rc_client_idle() internally, so the rcheevos internal thread
            // pool continues to drain HTTP callbacks during the wait.
            val stateRef = com.sun.jna.ptr.IntByReference(0)
            val settleRc = try {
                bindings.ra_facade_wait_for_load_settle(handle, budgetMs, pollMs, stateRef)
            } catch (e: UnsatisfiedLinkError) {
                return false
            }
            // RA_OK on settle = the load settled on READY or ABORTED.
            // RA_ERR_INTERNAL = timed out before settling. Both surface
            // as a "not ready" answer; the coordinator treats this as
            // "service is idle for this session" and proceeds with gameplay.
            // The boot-placard controller checks [gameSummary] separately
            // to decide what (if anything) to display.
            return settleRc == RaStatus.OK && stateRef.value == RaLoadState.READY
        }
    }

    override fun gameSummary(): RaGameSummary? {
        // The C side returns RA_ERR_NOT_SIGNED_IN when no user is logged in
        // and RA_ERR_NO_GAME when the load hasn't reached READY. Both are
        // expected non-error outcomes from the JNA layer's perspective —
        // the boot placard treats them as "no placard" (AC #8).
        val info = RaGameSummarySlot()
        try {
            info.write()
            val rc = bindings.ra_facade_get_game_summary(handle, info)
            if (rc != RaStatus.OK) return null
            info.read()
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        val progress = RaUserGameSummary()
        var unlocked = 0
        var pointsUnlocked = 0
        try {
            progress.write()
            val rc = bindings.ra_facade_get_user_game_summary(handle, progress)
            if (rc == RaStatus.OK) {
                progress.read()
                unlocked = progress.numUnlockedAchievements
                pointsUnlocked = progress.pointsUnlocked
            }
        } catch (e: UnsatisfiedLinkError) {
            // No game loaded / no signed-in user — leave zeros.
        }
        return RaGameSummary(
            gameId = info.id,
            title = bytesToString(info.title),
            hash = bytesToString(info.hash),
            badgeName = bytesToString(info.badgeName),
            imageUrl = bytesToString(info.imageUrl),
            numCoreAchievements = progress.numCoreAchievements,
            pointsCore = progress.pointsCore,
            numUnlockedAchievements = unlocked,
            pointsUnlocked = pointsUnlocked,
        )
    }

    override fun achievementListSnapshot(): RaAchievementListSnapshot? {
        // Issue #272 — loaded-game achievements window. The snapshot is
        // built up by allocating a list on the C side, walking every
        // bucket + achievement, copying each into a Kotlin-side value,
        // then destroying the list. The C side owns the list for the
        // duration of the walk; nothing is held across the call.
        //
        // On any failure (no game / unsigned in / UnsatisfiedLinkError
        // / list allocation failure) we return null — the view model
        // treats null as "not available right now".
        //
        // The snapshot's `generation` field is 0 here; the controller
        // stamps it with its own current generation on the way out so
        // view-model.generation and snapshot.generation always agree.
        try {
            val rc = bindings.ra_facade_create_achievement_list(
                handle,
                RaAchievementCategory.CORE,
                RaAchievementListGrouping.PROGRESS,
            )
            if (rc != RaStatus.OK) return null
        } catch (e: UnsatisfiedLinkError) {
            return null
        }

        try {
            val bucketCount = try {
                bindings.ra_facade_achievement_list_bucket_count(handle)
            } catch (e: UnsatisfiedLinkError) {
                return null
            }
            if (bucketCount <= 0) return null

            // Read the game-level summary once so the snapshot carries the
            // title + badge + unlocked/total counts alongside the bucket
            // data. A failure here is non-fatal — we just fall back to
            // empty strings and zero counts.
            val summary = gameSummary()
            val gameTitle = summary?.title ?: ""
            val gameImageUrl = summary?.imageUrl ?: ""
            val totalCore = summary?.numCoreAchievements ?: 0
            val totalCorePoints = summary?.pointsCore ?: 0
            val unlockedCore = summary?.numUnlockedAchievements ?: 0
            val unlockedCorePoints = summary?.pointsUnlocked ?: 0

            val buckets = ArrayList<RaAchievementBucketSnapshot>(bucketCount)
            for (bIdx in 0 until bucketCount) {
                val bucketSlot = RaAchievementBucketSlot()
                val bucketRc = try {
                    bucketSlot.write()
                    val r = bindings.ra_facade_get_achievement_bucket(handle, bIdx, bucketSlot)
                    bucketSlot.read()
                    r
                } catch (e: UnsatisfiedLinkError) {
                    return null
                }
                if (bucketRc != RaStatus.OK) continue

                val bucketEnum = RaAchievementBucket.fromCode(bucketSlot.bucketType)
                val label = bytesToString(bucketSlot.label).ifEmpty { bucketEnum.label }
                val achievements = ArrayList<RaAchievement>(bucketSlot.achievementCount)

                for (aIdx in 0 until bucketSlot.achievementCount) {
                    val achSlot = RaAchievementSlot()
                    val achRc = try {
                        achSlot.write()
                        val r = bindings.ra_facade_get_achievement_at(handle, bIdx, aIdx, achSlot)
                        achSlot.read()
                        r
                    } catch (e: UnsatisfiedLinkError) {
                        return null
                    }
                    if (achRc != RaStatus.OK) continue
                    achievements += RaAchievement(
                        id = achSlot.id,
                        title = bytesToString(achSlot.title),
                        description = bytesToString(achSlot.description),
                        points = achSlot.points,
                        badgeName = bytesToString(achSlot.badgeName),
                        badgeUrlUnlocked = bytesToString(achSlot.badgeUrlUnlocked),
                        badgeUrlLocked = bytesToString(achSlot.badgeUrlLocked),
                        bucket = bucketEnum,
                        measuredProgress = bytesToString(achSlot.measuredProgress),
                        measuredPercent = achSlot.measuredPercent,
                        isUnlocked = achSlot.state == RaAchievementState.UNLOCKED,
                    )
                }

                buckets += RaAchievementBucketSnapshot(
                    bucket = bucketEnum,
                    label = label,
                    achievements = achievements,
                )
            }

            return RaAchievementListSnapshot(
                gameTitle = gameTitle,
                gameImageUrl = gameImageUrl,
                totalCoreAchievements = totalCore,
                totalCorePoints = totalCorePoints,
                unlockedCoreAchievements = unlockedCore,
                unlockedCorePoints = unlockedCorePoints,
                buckets = buckets,
                // The controller stamps this with its own current
                // generation on the way out — see
                // [RaAchievementsController.refresh].
                generation = 0L,
            )
        } finally {
            // Always destroy the C-side list — even on early return paths —
            // so a partial walk doesn't leak a list across calls. The list
            // is unusable past this point regardless.
            try { bindings.ra_facade_destroy_achievement_list(handle) } catch (_: UnsatisfiedLinkError) {}
        }
    }

    override fun evaluateFrame(frameIndex: Long) {
        // Issue #270: p95 latency benchmark — record wall-clock duration
        // around the native call so the test can assert the 1 ms budget
        // holds across a synthetic workload. LatencyTracker is null in
        // production (no allocation per frame); tests inject one.
        val tracker = latencyTracker
        val startNanos = if (tracker != null) System.nanoTime() else 0L
        try {
            // Push our handle onto the thread-local stack so the
            // shared JNA callback can find the right JVM-side reader.
            // Popped in `finally` so a thrown exception from the native
            // side can't leak a stale handle to the next facade.
            val prior = currentHandle.get()
            currentHandle.set(handle)
            try {
                bindings.ra_facade_evaluate_frame(handle, frameIndex)
                bindings.ra_facade_idle(handle)
                drainEvents()
            } finally {
                currentHandle.set(prior)
            }
            // Clear pendingSync once the native queue is drained AND
            // the load state is READY. The pending-sync indicator stays
            // up while the runtime is still catching up on its submission
            // queue — clearing it the moment we observe an empty event
            // queue AND a settled load state gives the user a clean
            // "no more syncing" transition without flapping every frame.
            if (pendingSync && lastConnectivityHealthy &&
                bindings.ra_facade_get_load_state(handle) == RaLoadState.READY) {
                pendingSync = false
            }
        } catch (e: UnsatisfiedLinkError) {
            // Library was unloaded mid-session (e.g. during shutdown);
            // swallow — the coordinator has already moved on.
        } finally {
            if (tracker != null) {
                tracker.record(System.nanoTime() - startNanos)
            }
        }
    }

    override fun resetRuntime() {
        try {
            bindings.ra_facade_reset(handle)
        } catch (e: UnsatisfiedLinkError) { /* see evaluateFrame */ }
    }

    override fun serializeProgress(): ByteArray? {
        val size = try {
            bindings.ra_facade_progress_size(handle)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        if (size <= 0) return null
        val out = ByteArray(size)
        val written = try {
            bindings.ra_facade_serialize_progress(handle, out, size)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        if (written <= 0) return null
        return if (written == out.size) out else out.copyOf(written)
    }

    override fun restoreProgress(progress: ByteArray?) {
        if (progress == null) {
            // Null → reset the runtime to its post-prepareGame baseline.
            try { bindings.ra_facade_reset(handle) } catch (e: UnsatisfiedLinkError) {}
            return
        }
        try {
            bindings.ra_facade_restore_progress(handle, progress, progress.size)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    override fun unloadGame() {
        // Issue #270: a fresh ROM starts with no pending sync — the new
        // game's events will arrive through the normal drain path.
        pendingSync = false
        try {
            // Drop pending events BEFORE telling the C side to unload, so
            // an in-flight server-error event from the previous game
            // can't reach the new game's UI.
            bindings.ra_facade_clear_events(handle)
            bindings.ra_facade_unload_game(handle)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    override fun shutdown() {
        // Issue #270: drop the listener + tracker references BEFORE the
        // native handle goes away. A listener that fires after shutdown
        // would see a freed pointer; clearing here makes the post-shutdown
        // window observable.
        notificationListener = null
        // Issue #288: drop the achievement event bus reference too.
        // After shutdown the façade can't publish events (the handle
        // is gone), but a listener that fires for some other reason
        // would still be working with a stale service instance.
        achievementEventBus = null
        latencyTracker = null
        try {
            bindings.ra_facade_destroy(handle)
        } catch (e: UnsatisfiedLinkError) {
            // Already gone — nothing more to do.
        }
        // The native handle is now invalid. Mark this instance so a
        // follow-up call doesn't try to use a freed pointer.
        // (Subsequent calls go through `bindings.ra_facade_*` which would
        // crash on a NULL pointer; the try/catch above shields us from
        // every documented call site, and a fresh service instance is
        // the only path forward.)
    }

    // ------------------------------------------------------------------
    // Issue #270: connectivity flags for the UI's pending-sync indicator.
    // ------------------------------------------------------------------

    /** Last observed connectivity state — true until DISCONNECTED fires. */
    fun isConnectivityHealthy(): Boolean = lastConnectivityHealthy

    /**
     * True while the runtime has queued events that haven't yet been
     * confirmed by the server. The UI's "sync" badge stays up while this
     * is true and clears once the runtime drains its events on a healthy
     * connection (see [evaluateFrame]).
     */
    fun isPendingSync(): Boolean = pendingSync

    // ------------------------------------------------------------------
    // Memory reader wiring (Nestlin-side)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Memory reader wiring (Nestlin-side)
    // ------------------------------------------------------------------

    /**
     * Install the JVM-side memory reader so rcheevos can read emulated
     * RAM/registers during `evaluate_frame`. The callback runs
     * synchronously on the emulation thread — it must NOT call back into
     * any ra_facade_* method.
     *
     * The reader is stored in a process-wide map keyed by the façade
     * handle. The shared `jnaMemoryReader` callback is what rcheevos
     * actually calls; on each invocation it looks up the JVM-side
     * reader from the map using the [currentHandle] ThreadLocal that
     * `evaluate_frame` pushes before the native call.
     */
    override fun installMemoryReader(reader: RaReadMemoryFn) {
        memoryReaders[handle] = reader
        try {
            // The C shim stores the userdata pointer but never dereferences
            // it — the JVM-side uses the ThreadLocal handle instead. We
            // pass the handle pointer for symmetry, in case future
            // C-side debugging wants to inspect what's installed.
            bindings.ra_facade_set_memory_reader(handle, jnaMemoryReader, handle)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    /**
     * Drain pending events from the native queue. Each event is logged
     * (with sensitive fields redacted) and dropped. UI integration
     * (achievement toasts, leaderboard tracker) lands in issue #268 —
     * the seam is in place; the consumers aren't wired yet.
     */
    private fun drainEvents() {
        val ev = RaEvent()
        while (true) {
            ev.write()
            val has = try {
                bindings.ra_facade_poll_event(handle, ev)
            } catch (e: UnsatisfiedLinkError) {
                return
            }
            if (has == 0) return
            ev.read()
            handleEvent(ev)
        }
    }

    private fun handleEvent(ev: RaEvent) {
        // Every native event is fully copied out of native memory into
        // JVM strings (bytesToString below) BEFORE any listener fires.
        // That satisfies the issue #270 AC "Events fully copied before
        // callbacks return" — once a [RaNotification] reaches the
        // listener, there is no reference back into rcheevos / façade
        // buffers.
        when (ev.type) {
            RaEventType.ACHIEVEMENT_TRIGGERED -> {
                val title = bytesToString(ev.achievementTitle)
                val description = bytesToString(ev.achievementDescription)
                val badgeName = bytesToString(ev.achievementBadge)
                // Points + ID are non-sensitive; title is user-facing text
                // from the achievement set (not a token). Safe to log.
                System.err.println("[RA] Achievement unlocked: id=${ev.achievementId} points=${ev.achievementPoints} title=$title")
                // Build the official badge URL via the façade helper so
                // the listener gets the canonical absolute URL (empty
                // string when rcheevos hasn't assigned a badge yet).
                val badgeUrl = if (badgeName.isEmpty()) "" else buildBadgeUrl(badgeName)
                val n = UnlockNotification(
                    achievementId = ev.achievementId,
                    title = title,
                    description = description,
                    points = ev.achievementPoints,
                    badgeUrl = badgeUrl,
                    // displayUntilMillis is the controller's concern; we
                    // hand it a sentinel — the controller re-stamps with
                    // its own nowMillis at publish time.
                    displayUntilMillis = 0L,
                )
                pendingSync = true
                dispatchNotification(n)
                // Issue #288: surface the unlock to the achievements
                // window's refresh path. The bus's listeners typically
                // call [RaAchievementsController.refresh]; the
                // controller's generation guard discards stale
                // refreshes whose generation doesn't match the
                // controller's currentGeneration.
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementTriggered(achievementId = ev.achievementId)
                )
            }
            RaEventType.ACHIEVEMENT_CHALLENGE_SHOW -> {
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementChallengeShow(achievementId = ev.achievementId)
                )
            }
            RaEventType.ACHIEVEMENT_CHALLENGE_HIDE -> {
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementChallengeHide(achievementId = ev.achievementId)
                )
            }
            RaEventType.ACHIEVEMENT_PROGRESS_SHOW -> {
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementProgressShow(achievementId = ev.achievementId)
                )
            }
            RaEventType.ACHIEVEMENT_PROGRESS_HIDE -> {
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementProgressHide(achievementId = ev.achievementId)
                )
            }
            RaEventType.ACHIEVEMENT_PROGRESS_UPDATE -> {
                // A measured-progress update changes the
                // achievement's measuredProgress + measuredPercent
                // fields; the bucket assignment may shift between
                // ACTIVE_CHALLENGE and ALMOST_THERE. The achievements
                // window's snapshot rebuilds from the façade on
                // every refresh — a refresh on this event gives the
                // user immediate feedback on progress tracker bumps.
                achievementEventBus?.publish(
                    RaAchievementEvent.AchievementProgressUpdate(achievementId = ev.achievementId)
                )
            }
            RaEventType.GAME_COMPLETED -> {
                System.err.println("[RA] Game completed (no mastery notification)")
            }
            RaEventType.SERVER_ERROR -> {
                // Server errors may include API paths and result codes —
                // we deliberately do NOT log the message body, which can
                // contain server-internal context that shouldn't leak to
                // log files. Result code + ID are the actionable bits.
                System.err.println("[RA] Server error: code=${ev.serverResultCode} related_id=${ev.serverRelatedId}")
                // Surface to the UI without leaking the message body.
                dispatchNotification(SystemNotification(
                    severity = SystemSeverity.ERROR,
                    message = "RetroAchievements server error (code ${ev.serverResultCode})",
                    displayUntilMillis = 0L,
                ))
            }
            RaEventType.DISCONNECTED -> {
                System.err.println("[RA] Disconnected from server")
                lastConnectivityHealthy = false
                pendingSync = true
                dispatchNotification(SystemNotification(
                    severity = SystemSeverity.ERROR,
                    message = "RetroAchievements offline — unlocks will sync when reconnected",
                    displayUntilMillis = 0L,
                ))
            }
            RaEventType.RECONNECTED -> {
                System.err.println("[RA] Reconnected to server")
                lastConnectivityHealthy = true
                // The "Reconnected — syncing pending unlocks" banner stays
                // up until the next evaluateFrame observes an empty event
                // queue AND load_state == READY (see evaluateFrame).
                dispatchNotification(SystemNotification(
                    severity = SystemSeverity.INFO,
                    message = "Reconnected — syncing pending unlocks",
                    displayUntilMillis = 0L,
                ))
            }
            RaEventType.LEADERBOARD_SUBMITTED -> {
                dispatchNotification(SystemNotification(
                    severity = SystemSeverity.INFO,
                    message = "Leaderboard submission accepted",
                    displayUntilMillis = 0L,
                ))
            }
            RaEventType.LEADERBOARD_FAILED -> {
                dispatchNotification(SystemNotification(
                    severity = SystemSeverity.ERROR,
                    message = "Leaderboard submission failed (server error)",
                    displayUntilMillis = 0L,
                ))
            }
            // Other event types (leaderboard tracker, scoreboard,
            // reset) are transient UI hints that don't affect the
            // achievements window's snapshot — we deliberately don't
            // surface them here.
        }
    }

    /**
     * Build the canonical RA badge URL for a given badge name via the
     * façade's URL helper. The façade knows the server root; the JVM
     * side only knows the badge filename. Returns an empty string when
     * the helper declines (bad input) — the listener treats empty as
     // "no image".
     */
    private fun buildBadgeUrl(badgeName: String): String {
        val buf = ByteArray(RaGameSummarySlot.RA_FACADE_URL_MAX)
        return try {
            val rc = bindings.ra_facade_badge_url(badgeName, buf, buf.size)
            if (rc != RaStatus.OK) return ""
            val end = buf.indexOf(0)
            val trimmed = if (end >= 0) buf.copyOf(end) else buf
            String(trimmed, Charsets.UTF_8)
        } catch (e: UnsatisfiedLinkError) {
            ""
        }
    }

    /**
     * Forward a fully-copied notification to the registered listener (the
     * [RaNotificationController] in production). A throw from the
     * listener is swallowed — a UI-side bug must not propagate into the
     * emulation thread's per-frame hot path.
     */
    private fun dispatchNotification(n: RaNotification) {
        val listener = notificationListener ?: return
        try {
            listener(n)
        } catch (e: Exception) {
            System.err.println("[RA] Notification listener threw: ${e.javaClass.simpleName}")
        }
    }

    private fun bytesToString(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        val trimmed = if (end >= 0) bytes.copyOf(end) else bytes
        return String(trimmed, Charsets.UTF_8)
    }

    companion object {
        /**
         * Try to load the native library and create a fresh service.
         * Returns null when the library is missing, corrupt, or the
         * rcheevos client init fails. Logs a single one-line INFO
         * message on the no-op path so a developer can see why the
         * façade is degraded without a stack trace.
         *
         * The library is loaded lazily on first call. Tests that never
         * call this method never load the native library — that's the
         * "headless replay, bootcheck, and unrelated unit tests must
         * not load native library" requirement from issue #267.
         */
        @JvmStatic
        fun load(): NativeRetroAchievementsService? {
            val bindings = RaFacadeBindings.load() ?: run {
                System.err.println("[RA] Native library unavailable — using NoOp service")
                return null
            }
            val handle = try {
                bindings.ra_facade_create(SERVER_URL, USER_AGENT)
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("[RA] Native library failed to initialise — using NoOp service")
                return null
            }
            if (handle == null) {
                System.err.println("[RA] rcheevos client creation returned null — using NoOp service")
                return null
            }
            return NativeRetroAchievementsService(bindings, handle)
        }

        /** RA server URL passed to rcheevos. The C side ignores it until #268. */
        private val SERVER_URL: String? = null  // default — const val can't be nullable String

        /** User-Agent passed to rcheevos. */
        private const val USER_AGENT = "Nestlin/1.0"

        /**
         * Default upper bound on a `prepareGame` round-trip (issue #269).
         * RA achievement fetches typically settle in <2s on warm caches;
         * 10s gives plenty of headroom for cold caches and slow networks
         * while still bounding the wait before the first emulated frame.
         */
        const val DEFAULT_PREPARE_TIMEOUT_MS: Int = 10_000

        /** Poll interval used inside the C-side wait helper. */
        const val DEFAULT_PREPARE_POLL_MS: Int = 50

        // Per-instance map of native handles to JVM-side readers. The
        // pointer key is unique per facade instance, so a stale entry
        // for a destroyed facade can't be reached from a live one (the
        // reader closure captures the facade, and the facade's
        // shutdown() doesn't touch this map — destroyed façades are
        // garbage-collected and their entries are pruned when a new
        // facade happens to reuse the same pointer address, which is
        // not a correctness concern because the handle is freed).
        private val memoryReaders = java.util.concurrent.ConcurrentHashMap<Pointer, RaReadMemoryFn>()

        // Thread-local handle stack: pushed/popped by each evaluate_frame
        // call so jnaMemoryReader can find the right reader.
        private val currentHandle = ThreadLocal.withInitial<Pointer?> { null }

        // The shared JNA-side callback. JNA's "callback" mapping wraps
        // this interface so native code can call into the JVM. The
        // single static instance is shared across all façades because
        // JNA's `Callback` interface is stateless once bound; the
        // per-call façade identity comes from [currentHandle].
        //
        // Bounds-safe (issue #270 AC "Memory reads are side-effect-free
        // and bounds-safe"): three guards reject malformed conditions
        // that would otherwise crash the JVM with an out-of-bounds array
        // access. rcheevos is a trusted library but a malicious or
        // hand-crafted achievement set could probe the read path; the
        // worst case from a malformed condition is a missed trigger, not
        // a JVM crash.
        private val jnaMemoryReader = RaReadMemoryFn { address, buffer, numBytes ->
            // 1. Reject negative address (uint32_t wrapped to negative Int).
            // 2. Reject addresses above the NES CPU bus range.
            if (address < 0 || address > 0xFFFF) return@RaReadMemoryFn 0
            // 3. Reject non-positive counts and over-sized reads. A
            //    non-positive count means "no bytes requested" — return 0
            //    so the runtime falls back to its zero-fill default.
            if (numBytes <= 0) return@RaReadMemoryFn 0
            val handle = currentHandle.get() ?: return@RaReadMemoryFn 0
            val reader = memoryReaders[handle] ?: return@RaReadMemoryFn 0
            val n = numBytes.coerceAtMost(buffer.size)
            if (n <= 0) return@RaReadMemoryFn 0
            reader.read(address, buffer, n)
        }
    }
}

/**
 * Build a side-effect-free [RaReadMemoryFn] backed by [Memory.peek] (issue #270 AC
 * "Memory reads are side-effect-free and bounds-safe").
 *
 * `Memory.peek(address)`:
 *   - Skips PPU vblank clear, write-toggle reset, VRAM increment (issue #168),
 *   - Skips APU IRQ acknowledge (`$4015`),
 *   - Skips controller shift-register advance (`$4016`/`$4017`),
 *   - Does NOT touch the open-bus data latch (an emulator-only debug aid),
 *   - Returns 0 for unmapped / out-of-range addresses.
 *
 * The wrapping here enforces three extra invariants the JVM-side callback contract
 * requires (issue #270 AC):
 *   1. `address` outside `[0, 0xFFFF]` returns 0 (handles uint32_t-wrapped-to-negative
 *      values from a malformed condition).
 *   2. `numBytes <= 0` returns 0 (handles zero/negative read requests).
 *   3. `numBytes > buffer.size` clamps to `buffer.size` (defensive; the JNA side
 *      does this too but the coordinator's wrapping is the documented contract).
 *
 * Tests against this helper are in `MemoryPeekRaReaderTest`.
 */
internal fun peekReader(memory: Memory): RaReadMemoryFn =
    RaReadMemoryFn { address, buffer, numBytes ->
        if (address < 0 || address > 0xFFFF) return@RaReadMemoryFn 0
        if (numBytes <= 0) return@RaReadMemoryFn 0
        val n = numBytes.coerceAtMost(buffer.size)
        if (n <= 0) return@RaReadMemoryFn 0
        for (i in 0 until n) {
            buffer[i] = memory.peek(address + i)
        }
        n
    }
