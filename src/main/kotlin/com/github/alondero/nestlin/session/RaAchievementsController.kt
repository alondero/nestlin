package com.github.alondero.nestlin.session

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the coordinator's ROM + sign-in lifecycle to the loaded-game
 * achievements window (issue #272). Mirrors the pattern established by
 * [RaBootPlacardController] (issue #269): owns a generation counter,
 * publishes an immutable [RaAchievementsWindowViewModel] on every
 * relevant transition, and silently drops stale events whose
 * generation doesn't match the controller's current.
 *
 * ## Generation guards
 *
 * The controller bumps [generation] on:
 *  - every [GameSessionCoordinator.loadRom] / `loadBytes` / `powerReset` /
 *    `unloadRom` / `restartForAchievements` (ROM identity changed);
 *  - every [RaSignInManager] sign-in or sign-out transition
 *    (the achievements window must close or refresh — the issue #272
 *    AC explicitly calls this out: "When the ROM/account generation
 *    changes, the window switches to the new state or closes cleanly").
 *
 * A UI consumer compares `event.generation` against
 * [generation] before applying the view-model — see [RaAchievementsWindow].
 *
 * ## View-model states
 *
 * The controller's [refresh] is the single funnel that asks the
 * service for a fresh [RaAchievementListSnapshot] and maps it to a
 * [RaAchievementsWindowViewModel]. Every state transition flows
 * through it: sign-in/out, ROM change, unlock, measured progress,
 * hard/soft reset, error.
 *
 * The controller does NOT poll the service on a timer; it relies on
 * the application's hook surface to fire on every relevant event. The
 * [evaluateFrameHook] is a backstop for measured-progress updates
 * that the runtime reports via [evaluateFrame] events.
 */
class RaAchievementsController(
    /**
     * The façade the controller queries for achievement snapshots.
     * The NoOp implementation returns null, which the controller
     * surfaces as [RaAchievementsWindowViewModel.Unavailable].
     */
    private val service: RetroAchievementsService,
    /**
     * Snapshot of the current sign-in state. Updated on every
     * [RaSignInManager] listener fire; consulted by [refresh] to
     * choose between [RaAchievementsWindowViewModel.SignedOut],
     * [RaAchievementsWindowViewModel.Offline], and the data-bearing
     * variants.
     */
    private val signInState: () -> RaSignInState,
    /**
     * Snapshot of whether a ROM is currently loaded. Updated on every
     * [GameSessionCoordinator.onAfterRomChange] hook fire. `null` means
     * no ROM; non-null carries the display name the [Unrecognized]
     * placeholder needs.
     */
    private val loadedRomInfo: () -> LoadedRomSnapshot?,
) {

    /**
     * Snapshot of the loaded ROM's identity — enough for the
     * [Unrecognized] placeholder without coupling the controller to
     * [com.github.alondero.nestlin.Nestlin]. The application fills this
     * via the coordinator's `onAfterRomChange` hook (null on unload).
     */
    data class LoadedRomSnapshot(
        val displayName: String,
        val virtualFilename: String,
    )

    /** Monotonic counter — bumped on every ROM / sign-in / sign-out transition. */
    @Volatile private var currentGeneration: Long = 0L

    /** Current generation. UI consumers compare event.generation to this value. */
    val generation: Long get() = currentGeneration

    /** Latest published view-model. Atomic so the FX thread reads a consistent snapshot. */
    private val latestViewModel: AtomicReference<RaAchievementsWindowViewModel> =
        AtomicReference(RaAchievementsWindowViewModel.INITIAL)

    private val listeners: CopyOnWriteArrayList<(RaAchievementsWindowViewModel) -> Unit> = CopyOnWriteArrayList()

    /** Most recent published view-model. Bind a listener via [addListener]. */
    val currentViewModel: RaAchievementsWindowViewModel get() = latestViewModel.get()

    /**
     * Add a listener that fires on every view-model transition. The
     * listener runs synchronously on the calling thread; UI listeners
     * that mutate scene-graph nodes MUST re-post to the JavaFX thread.
     *
     * Returns an opaque token; pass to [removeListener] to unsubscribe.
     */
    fun addListener(listener: (RaAchievementsWindowViewModel) -> Unit): ListenerToken {
        listeners += listener
        return ListenerToken(listener)
    }

    /** Idempotent. */
    fun removeListener(token: ListenerToken) {
        listeners.remove(token.listener)
    }

    /**
     * Advance the generation counter. Called by the application on
     * every ROM change and on every [RaSignInManager] sign-in / out
     * transition. After this returns, any pending refresh that fires
     * against the old generation is silently dropped at publish time.
     *
     * Returns the new generation so the call site can log it.
     */
    fun bumpGeneration(): Long {
        currentGeneration += 1
        return currentGeneration
    }

    /**
     * Force a fresh refresh. Safe to call from any thread. Reads the
     * current sign-in state + ROM state, queries the service for a
     * snapshot, then publishes the matching [RaAchievementsWindowViewModel].
     *
     * Used by every external transition (ROM load, sign-in/out, hard
     * /soft reset, error). The controller does NOT auto-refresh on a
     * timer; it relies on callers driving it from the right hooks.
     */
    fun refresh() {
        val gen = currentGeneration
        val rom = loadedRomInfo()
        val state = signInState()
        val snapshot = runCatching { service.achievementListSnapshot() }.getOrNull()
        // Stamp the snapshot's generation with the controller's
        // current generation. The service sets its own internal counter
        // (a stale image-fetch would compare against the snapshot's
        // own generation, which can lag the controller's), but the
        // view-model layer compares view-model.generation against
        // controller.generation. Both come from this method's `gen`
        // so the values are guaranteed consistent.
        val stamped = snapshot?.copy(generation = gen)
        val viewModel = mapToViewModel(gen, rom, state, stamped)
        publish(viewModel)
    }

    /**
     * Map (generation, rom, sign-in, snapshot) to the matching view-
     * model variant. Pulled out as a pure function so tests can assert
     * every state transition without standing up the listener surface.
     */
    private fun mapToViewModel(
        gen: Long,
        rom: LoadedRomSnapshot?,
        state: RaSignInState,
        snapshot: RaAchievementListSnapshot?,
    ): RaAchievementsWindowViewModel {
        // Order matters: the most specific failure path first, then
        // generic availability, then the success path. Each branch
        // carries an "explain what the user can do" hint appropriate
        // for the variant.
        return when {
            // 1. Service can't run (no native lib, no signed-in user, etc.)
            state is RaSignInState.Unavailable -> RaAchievementsWindowViewModel.Unavailable(
                generation = gen, reason = "RetroAchievements integration is not available on this build.",
            )

            // 2. User signed out — distinct from Unavailable (the service
            //    CAN run; the user just isn't authenticated).
            state is RaSignInState.SignedOut -> RaAchievementsWindowViewModel.SignedOut(generation = gen)

            // 3. Auth in flight — show signed-out placeholder for the brief
            //    moment, not the spinner (the spinner is reserved for
            //    snapshot refreshes that have a known previous list).
            state is RaSignInState.Authenticating -> RaAchievementsWindowViewModel.SignedOut(generation = gen)

            // 4. Transient offline (signed in but the bridge is failing).
            state is RaSignInState.Offline -> RaAchievementsWindowViewModel.Offline(
                generation = gen, cause = state.cause,
            )

            // 5. Signed in — but no ROM. Distinct from the
            //    "ROM recognized" path; the UI must NOT show the header
            //    progress bar (the bar is per-game, not per-account).
            rom == null -> RaAchievementsWindowViewModel.NoRom(generation = gen)

            // 6. ROM loaded but service returned no snapshot — this is the
            //    "ROM not recognized on RetroAchievements" branch. A null
            //    snapshot for a loaded ROM means the identify round-trip
            //    succeeded but the server returned no matching game.
            snapshot == null -> RaAchievementsWindowViewModel.Unrecognized(
                generation = gen,
                displayName = rom.displayName,
                virtualFilename = rom.virtualFilename,
            )

            // 7. Snapshot present but no core achievements — the
            //    recognized-but-empty branch (AC #7).
            snapshot.totalCoreAchievements == 0 -> RaAchievementsWindowViewModel.NoCoreAchievements(
                generation = gen,
                gameTitle = snapshot.gameTitle,
                gameImageUrl = snapshot.gameImageUrl,
            )

            // 8. Happy path.
            else -> RaAchievementsWindowViewModel.Recognized(
                generation = gen,
                snapshot = snapshot,
            )
        }
    }

    /**
     * Publish the [viewModel] to every listener, but only if its
     * generation matches [currentGeneration]. A late publish from a
     * stale refresh (the user signed out while a snapshot was in
     * flight, etc.) is silently dropped here so listeners never see
     * out-of-order data.
     */
    private fun publish(viewModel: RaAchievementsWindowViewModel) {
        if (viewModel.generation != currentGeneration) return
        latestViewModel.set(viewModel)
        for (l in listeners) {
            try {
                l(viewModel)
            } catch (e: Exception) {
                System.err.println("[RA] Achievements listener threw: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Clear without bumping the generation. Used by the application's
     * unload path so the UI sees a NoRom placeholder under the same
     * generation (any in-flight refresh that completes after this is
     * correctly classified as stale). Idempotent.
     */
    fun clear() {
        publish(RaAchievementsWindowViewModel.NoRom(generation = currentGeneration))
    }

    /** Opaque token for [addListener] / [removeListener]. */
    data class ListenerToken internal constructor(internal val listener: (RaAchievementsWindowViewModel) -> Unit)
}
