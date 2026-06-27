package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.movie.PendingInputBuffer

/**
 * The answer to "who feeds the controller right now" (issue #191). Sealed so the
 * set of legitimate sources is enumerated at compile time and a missing case is a
 * build error, not a runtime `null`.
 *
 * Every variant exposes a single [snapshot] method returning the current button
 * bitmap (8-bit, in `Controller.Button` order: A=0 … RIGHT=7). The
 * [com.github.alondero.nestlin.Controller] polls this every `$4016` read and
 * forwards the result to its `StrobeRegister`.
 *
 * Variants cover the four legitimate roles in the emulator:
 *  - [None]:           no game running, no input wired (returns 0).
 *  - [Live]:           a single live provider function (keyboard or gamepad).
 *  - [FromPendingBuffer]: a movie recording in progress; the keyboard writes to a
 *                        [PendingInputBuffer] and the live pad reads from it.
 *  - [FixedBitmap]:    a fixed value the caller controls (movie playback, the
 *                        replayer feeding the next row each frame).
 *  - [Composite]:      multiple sources OR-aggregated (e.g. keyboard + gamepad).
 */
sealed interface InputSource {
    /** Current button bitmap. 0 if no source is active. */
    fun snapshot(): Int

    /**
     * No input. Returns 0 forever. The default state when no game is loaded and
     * the default constructor argument for [com.github.alondero.nestlin.Controller].
     */
    data object None : InputSource {
        override fun snapshot(): Int = 0
    }

    /**
     * Wraps a single live provider function. Used to bind the Controller's internal
     * button bitmap so the standard `$4016` read path observes whatever the
     * keyboard handler / gamepad poll / movie latch wrote last.
     *
     * The provider is invoked on every [snapshot], so it MUST be cheap and
     * non-blocking. A backing [IntArray] of size 1 is the typical implementation
     * (one volatile write, one volatile read).
     */
    class Live(private val provider: () -> Int) : InputSource {
        override fun snapshot(): Int = provider()
    }

    /**
     * Reads from a [PendingInputBuffer] (the keyboard buffer used during real-time
     * movie recording). The buffer is mutable on the keyboard thread; the snapshot
     * is whatever value the buffer holds at the moment of the call. While this
     * source is active the live pad reflects the keyboard input AS WRITTEN — but
     * the [com.github.alondero.nestlin.MovieLiveRecorder] is responsible for the
     * commit semantics (one frame-end latch per frame).
     */
    class FromPendingBuffer(private val buffer: PendingInputBuffer) : InputSource {
        override fun snapshot(): Int = buffer.value
    }

    /**
     * Returns whatever value [provider] yields. Used by [com.github.alondero.nestlin.MovieLivePlayer]
     * to feed the next movie row into the controller without owning the bitmap
     * itself (the row list lives in the player; the controller just reads it).
     */
    class FixedBitmap(private val provider: () -> Int) : InputSource {
        override fun snapshot(): Int = provider()
    }

    /**
     * OR-aggregates multiple sources. Useful when both keyboard and gamepad can
     * contribute to the same controller in the same frame — e.g. gamepad UP
     * combined with keyboard A. The order of [sources] does not affect the result
     * because OR is associative and commutative.
     */
    class Composite(private val sources: List<InputSource>) : InputSource {
        override fun snapshot(): Int = sources.fold(0) { acc, src -> acc or src.snapshot() }
    }
}
