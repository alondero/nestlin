package com.github.alondero.nestlin.session

import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the coordinator's ROM-load lifecycle to a JavaFX boot-placard
 * observer (issue #269 AC #6, #7, #8, #10).
 *
 * The controller is the only place a UI component can learn about a newly
 * recognized game, a freshly loaded ROM with no core achievements, or an
 * unrecognized ROM. The coordinator pushes lifecycle events here; the UI
 * binds a listener via [addListener] and renders whatever the latest state
 * implies.
 *
 * ## State model
 *
 * The controller publishes a [BootPlacardEvent] for every ROM-load transition.
 * A UI consumer typically wants to render ONE event per ROM (the most recent),
 * so the controller keeps a single "latest" reference. Listeners that want to
 * observe the full sequence can attach a recorder and assert against the
 * `events` log instead of the latest snapshot.
 *
 * The events fire on whatever thread the coordinator called from (typically
 * the JavaFX thread for UI paths, the worker thread for CLI bootcheck).
 * UI listeners MUST re-post to the JavaFX thread before touching scene-graph
 * nodes — see `RaBootPlacard` for the recommended wrapper.
 *
 * ## Generation guards
 *
 * Every event carries the [generation] it was produced under. The UI consumer
 * MUST check `event.generation == currentGeneration` before applying the
 * event, otherwise a slow image-fetch completion from ROM A could overwrite
 * the placard for ROM B.
 *
 * The coordinator bumps [generation] on every ROM change and on every
 * sign-in / sign-out transition, so a rapid switch discards every in-flight
 * completion that doesn't match the new generation. The UI's "current
 * generation" mirror lives in the controller and is updated whenever a new
 * generation event arrives.
 */
class RaBootPlacardController {

    /**
     * Snapshot of the latest event. UI listeners that re-post to the
     * JavaFX thread and read this on the way through see the most recent
     * state without observing intermediate values.
     */
    private val latest: AtomicReference<BootPlacardEvent> =
        AtomicReference(BootPlacardEvent.Idle(generation = 0))

    /**
     * Ordered log of every event ever published. Tests assert against this
     * log; production consumers do not.
     */
    private val events: MutableList<BootPlacardEvent> = mutableListOf()

    private val listeners: CopyOnWriteArrayList<(BootPlacardEvent) -> Unit> = CopyOnWriteArrayList()

    /** Monotonic counter — bumped on every ROM / sign-in / sign-out transition. */
    @Volatile private var currentGeneration: Long = 0L

    /** Current generation. UI consumers compare event.generation to this value. */
    val generation: Long get() = currentGeneration

    /** Snapshot of the latest published event. */
    val currentEvent: BootPlacardEvent get() = latest.get()

    /** Append-only log of every event. Test-only. */
    val recordedEvents: List<BootPlacardEvent> get() = events.toList()

    /**
     * Add a listener that fires on every state transition. The listener is
     * invoked synchronously on the calling thread; UI listeners that
     * mutate scene-graph nodes MUST re-post to the JavaFX thread.
     *
     * Returns an opaque token; pass it to [removeListener] to unsubscribe.
     */
    fun addListener(listener: (BootPlacardEvent) -> Unit): ListenerToken {
        listeners += listener
        return ListenerToken(listener)
    }

    /** Idempotent. */
    fun removeListener(token: ListenerToken) {
        listeners.remove(token.listener)
    }

    /**
     * Advance the generation counter. Called by the coordinator on every
     * ROM change and every sign-in / sign-out. The new generation rejects
     * every in-flight image / summary completion that matches an older
     * generation.
     */
    fun bumpGeneration(): Long {
        currentGeneration += 1
        return currentGeneration
    }

    /**
     * Publish a [BootPlacardEvent] under [generation]. Listeners that
     * receive an event whose generation doesn't match [currentGeneration]
     * MUST discard it (the controller has already done so for the events
     * list, but listeners that render scene-graph state need their own
     * guard).
     */
    fun publish(event: BootPlacardEvent) {
        if (event.generation != currentGeneration) return  // stale, drop silently
        synchronized(events) { events += event }
        latest.set(event)
        for (l in listeners) {
            try {
                l(event)
            } catch (e: Exception) {
                System.err.println("[RA] Boot-placard listener threw: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Clear every event without bumping the generation. Used by the
     * coordinator when the active ROM is unloaded so the UI doesn't display
     * a stale placard.
     */
    fun clear() {
        val gen = currentGeneration
        val idle = BootPlacardEvent.Idle(generation = gen)
        synchronized(events) { events += idle }
        latest.set(idle)
        for (l in listeners) {
            try { l(idle) } catch (_: Exception) {}
        }
    }

    /** Opaque token for [addListener] / [removeListener]. */
    data class ListenerToken internal constructor(internal val listener: (BootPlacardEvent) -> Unit)
}

/**
 * Lifecycle event published by the coordinator and consumed by the boot
 * placard (issue #269).
 *
 * Each variant carries the generation it was produced under. The UI's
 * "current generation" comparison is what guards against rapid ROM switches.
 */
sealed interface BootPlacardEvent {
    /** The generation this event was produced under. Always equal to the controller's `generation`. */
    val generation: Long

    /**
     * No game is currently loaded, or no placard should be displayed.
     * Used by `coordinator.unloadRom` and as the initial state.
     */
    data class Idle(override val generation: Long) : BootPlacardEvent

    /**
     * The ROM was loaded but the user is signed out — do NOT display the
     * placard (AC #8). The JavaFX side shows nothing on this event.
     */
    data class SignedOut(override val generation: Long) : BootPlacardEvent

    /**
     * Service is unavailable (the native library isn't loaded, the network
     * is unreachable, or identification timed out). The UI shows a subtle
     * unrecognized explanation, NOT a login nag.
     */
    data class ServiceUnavailable(override val generation: Long, val cause: String) : BootPlacardEvent

    /**
     * ROM bytes were hashed and identified against the RA database; the game
     * is recognized. The placard shows title + unlocked/total + earned/total.
     */
    data class Recognized(
        override val generation: Long,
        val summary: RaGameSummary,
        /** Optional badge image; null when the fetch is still in flight or has failed. */
        val badgeImage: BufferedImage?,
    ) : BootPlacardEvent

    /**
     * ROM bytes were hashed and identified; the game is recognized but has
     * no core achievements. AC #7 distinguishes this from "service
     * unavailable" — the placard says so clearly, no nag.
     */
    data class RecognizedNoCore(
        override val generation: Long,
        val summary: RaGameSummary,
    ) : BootPlacardEvent

    /**
     * ROM bytes were hashed but the server returned no matching game. This
     * covers ROM hacks, translations, alternate dumps. The placard says so
     * subtly (AC #7).
     */
    data class Unrecognized(
        override val generation: Long,
        val displayName: String,
        val virtualFilename: String,
    ) : BootPlacardEvent
}
