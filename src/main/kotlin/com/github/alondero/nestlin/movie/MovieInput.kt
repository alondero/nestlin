package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.isBitSet

/**
 * One frame's worth of input in a movie's input log.
 *
 * [controller1]/[controller2] are button bitmaps in Nestlin's *internal* Controller bit order
 * (bit 0 = A … bit 7 = Right — see [com.github.alondero.nestlin.Controller.Button]), NOT the FM2
 * on-disk order. [Fm2Format] translates to/from FM2's reversed `RLDUTSBA` column layout.
 *
 * [commands] is the FM2 command field: bit 0 = soft reset, bit 1 = hard reset (power cycle).
 * Defaults to 0 (no console command this frame). Use [applyCommands] to translate the bits
 * into the matching [Nestlin] entry point — both [MoviePlayer.replayInto] and the
 * [com.github.alondero.nestlin.cli.ReplayCommand] CLI use the same helper so the two
 * replay paths can't drift apart on what a `commands=3` row means.
 */
data class MovieInput(
    val controller1: Int,
    val controller2: Int = 0,
    val commands: Int = 0,
) {
    /** True if this row carries a hard-reset / power-cycle bit (issue #125). */
    private val hasHardReset: Boolean get() = commands.isBitSet(Movie.HARD_RESET_BIT)

    /** True if this row carries a soft-reset bit (issue #125). */
    private val hasSoftReset: Boolean get() = commands.isBitSet(Movie.SOFT_RESET_BIT)

    /**
     * Apply this row's [commands] to [nestlin]. When both reset bits are set, hard reset
     * dominates (a power-cycle obliterates whatever the soft reset would have done).
     *
     * The CPU-level reset is fired directly on [nestlin] (`powerReset` zeroes RAM and
     * re-vectors the CPU; `softReset` preserves RAM). The optional [serviceReset] hook
     * is invoked AFTER the CPU reset so a production caller can fire
     * [com.github.alondero.nestlin.session.GameSessionCoordinator.resetServiceRuntime]
     * to keep the service's runtime condition progress in sync with the in-flight CPU
     * reset. Defaults to a no-op so test / CLI callers that don't have a service seam
     * can use the same helper without a coordinator instance.
     *
     * Used by [MoviePlayer.replayInto], [MovieLivePlayer]'s latch hook, and
     * [com.github.alondero.nestlin.cli.ReplayCommand] — the FM2 commands field has one
     * interpretation regardless of which replay path drives the machine.
     */
    fun applyCommands(
        nestlin: Nestlin,
        serviceReset: () -> Unit = {},
    ) {
        if (hasHardReset) {
            nestlin.powerReset()
            serviceReset()
        } else if (hasSoftReset) {
            nestlin.softReset()
            serviceReset()
        }
    }
}