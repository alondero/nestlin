package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.rewind.RewindBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pure state-transition table for the hold-Backspace rewind feature (issue #52),
 * lifted out of [com.github.alondero.nestlin.Nestlin] as part of issue #189.
 *
 * The machine has three states:
 *
 *  - [State.IDLE]      — no scrub in progress; the RunLoop is stepping the emulator forward.
 *  - [State.SCRUBBING] — a scrub is in progress. `tick()` returns [TickAction.STEP_REWIND]
 *                        so the RunLoop walks backward through [buffer]; `captureFrame()`
 *                        is invoked once per PPU frame during normal play to build the timeline.
 *  - [State.WAS_REWINDING] — momentary exit state. The previous tick was a rewind step;
 *                        the RunLoop should unmute audio and reset the throttle baseline
 *                        before resuming normal play. Clears itself on the next `tick()`.
 *
 * Why three states and not two? Because the boundary between SCRUBBING and IDLE has a
 * one-shot "reset the throttle baseline" side effect (the scrub rewound wall-clock time,
 * which the throttle would otherwise treat as drift to sprint through). Encoding the exit
 * as its own state makes the transition a property of the machine rather than a flag the
 * RunLoop has to remember.
 *
 * ### Threading
 *
 * [tick] and [captureFrame] run on the emulation thread (the RunLoop / PPU frame-completion
 * listener). [setRewindActive] runs on the JavaFX thread (the Backspace key handler), hence
 * `@Volatile` on [rewindActive] and [state]. [isRewinding] is read on the JavaFX thread
 * each frame to drive the on-screen `<<` indicator — also `@Volatile`.
 */
class RewindStateMachine(
    private val buffer: RewindBuffer,
    private val saveState: () -> ByteArray,
    private val loadState: (ByteArray) -> Unit,
    private val renderOneFrame: () -> Unit,
    private val setMuted: (Boolean) -> Unit,
    /** Sleep ~one display frame between rewind steps so scrubbing reads at ~3× real-time backward. */
    private val paceFrame: () -> Unit,
    /**
     * Idle sleep when the buffer is empty (Backspace held before any frame was captured).
     * Keeps the emulation thread off the CPU instead of busy-spinning.
     */
    private val park: () -> Unit,
    /** True when a ROM is loaded — rewind is a no-op without a GamePak (mapper guard rejects the snapshot). */
    private val isGameLoaded: () -> Boolean,
    /** True when the rewind feature is enabled in `EmulatorConfig`. */
    private val isEnabled: () -> Boolean,
) {

    enum class State { IDLE, SCRUBBING, WAS_REWINDING }
    enum class TickAction { STEP_NORMAL, STEP_REWIND, NOOP }

    // @Volatile: written by the JavaFX thread (Backspace handler), read by the emulation loop.
    @Volatile
    private var rewindActive = false

    // @Volatile: written by the emulation thread, read by the JavaFX thread each frame for the indicator.
    @Volatile
    var state: State = State.IDLE
        private set

    /** True while a rewind scrub pass is currently in progress (drives the on-screen `<<` indicator). */
    fun isRewinding(): Boolean = state == State.SCRUBBING

    /** Frames to walk back per rewind step. Paced at ~display rate (60 Hz), 3 frames/step = ~3× real-time backward. */
    var framesPerStep: Int = 3

    /** Pre-size the per-frame snapshot stream to skip the early doubling reallocs. A typical savestate is ~10 KB. */
    private val initialSnapshotCapacity = 16 * 1024

    // Set while a rewind step is re-rendering a frame, so the capture listener doesn't record
    // re-simulated frames back into the buffer (which would corrupt the timeline being scrubbed).
    private var suppressCapture = false

    /** Called by the JavaFX thread on Backspace press/release. Idempotent. */
    fun setRewindActive(active: Boolean) {
        rewindActive = active
    }

    /**
     * Called by the RunLoop once per iteration. Returns the next action the RunLoop should take.
     *
     *  - [TickAction.STEP_REWIND]   — load a snapshot from the past, re-render one frame, pace.
     *  - [TickAction.STEP_NORMAL]   — advance the emulator one CPU cycle (or, while in
     *                                 [State.WAS_REWINDING], first unmute + reset throttle).
     *  - [TickAction.NOOP]          — nothing to do (paused + no game loaded, etc.) — caller's call.
     */
    fun tick(): TickAction {
        val wantScrub = rewindActive && isEnabled() && isGameLoaded()
        return when {
            // Idle -> scrubbing: engage the enter-transition (mute audio).
            wantScrub && state == State.IDLE -> {
                enterScrub()
                tick()
            }
            // Was scrubbing, now released: exit transition (unmute + throttle reset).
            !wantScrub && state == State.SCRUBBING -> {
                exitScrub()
                TickAction.STEP_NORMAL
            }
            // Scrubbing: do a rewind step.
            wantScrub && state == State.SCRUBBING -> {
                stepScrub()
                TickAction.STEP_REWIND
            }
            // Was rewinding last tick — RunLoop needs STEP_NORMAL this tick to flush the
            // exit transition (mute=false) BEFORE another scrub resumes. Encoding WAS_REWINDING
            // as a real state means we don't have to thread a "was scrubbing last tick" flag
            // through the RunLoop.
            state == State.WAS_REWINDING -> {
                state = State.IDLE
                TickAction.STEP_NORMAL
            }
            else -> TickAction.STEP_NORMAL
        }
    }

    /** One-shot transition into a rewind pass: mute audio so scrubbing is silent, not crackly. */
    private fun enterScrub() {
        state = State.SCRUBBING
        setMuted(true)
    }

    /** One-shot transition out of a rewind pass: unmute. (Throttle baseline reset is the RunLoop's job.) */
    private fun exitScrub() {
        state = State.WAS_REWINDING
        setMuted(false)
    }

    /**
     * Advance one rewind step: jump back [framesPerStep] snapshots, load the snapshot now at
     * the head, and re-render exactly one frame so the screen shows the rewound state (savestates
     * carry no pixels). The re-render advances the machine one frame past the snapshot, but the
     * next step reloads from the ring, so that drift never compounds.
     */
    private fun stepScrub() {
        val snapshot = buffer.rewind(framesPerStep)
        if (snapshot == null) {
            // Empty buffer (Backspace held before any frame was captured): nothing to scrub to,
            // and capture only runs during normal play, so the buffer can never fill while we
            // sit here. Sleep one frame UNCONDITIONALLY — paceFrame would skip the sleep with
            // throttling off, leaving the emulation thread spinning at 100% CPU for nothing.
            park()
            return
        }
        suppressCapture = true
        try {
            loadState(snapshot)
            renderOneFrame()
        } finally {
            suppressCapture = false
        }
        paceFrame()
    }

    /**
     * Capture the just-completed frame into the rewind ring. Invoked by the PPU
     * frame-completion listener. No-op when rewind is disabled, when no game is loaded, or while
     * a rewind step is re-rendering (so re-simulated frames don't pollute the buffer).
     *
     * On capture failure (mapper with an incomplete `saveState`, OOM building the ~6 MB buffer),
     * disables rewind and clears the partial history so the game keeps running. This runs on the
     * emulation thread for every frame, so an unhandled throw would kill the emulator and hang
     * the UI on its join().
     */
    fun captureFrame(onCaptureFailure: (Throwable) -> Unit) {
        if (!isEnabled()) return
        if (suppressCapture) return
        if (!isGameLoaded()) return
        try {
            val out = ByteArrayOutputStream(initialSnapshotCapacity)
            // SaveState.save is itself a free function that takes a Nestlin; we route via the
            // saveState lambda so the state machine is testable with a fake.
            val blob = saveState()
            out.write(blob)
            buffer.capture(out.toByteArray())
        } catch (t: Throwable) {
            onCaptureFailure(t)
        }
    }

    /**
     * Drop every snapshot. Called on ROM swap / hard reset (a snapshot made against ROM A can't
     * be loaded into ROM B — the savestate ROM/mapper guard rejects it). Dropping here is the
     * right time even though the buffer is the rewind's backing store: the state machine owns
     * the lifecycle.
     */
    fun clearBuffer() {
        buffer.clear()
    }
}
