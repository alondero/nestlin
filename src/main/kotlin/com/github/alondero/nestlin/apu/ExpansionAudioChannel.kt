package com.github.alondero.nestlin.apu

/**
 * A single mapper-side audio channel that piggy-backs on the native APU mixer.
 *
 * Issue #50 (the API foundation #58's VRC6 and future N163 / VRC7 / Sunsoft 5B
 * support builds on). Each cartridge audio chip drops its own oscillators in
 * via [Apu.registerExpansionChannel]; the APU clocks them at CPU rate (one cycle
 * per call) and folds their output into the final mix.
 *
 * Why the channel owns its own clock instead of letting the APU clock dedicated
 * per-channel timers: every expansion chip has its own cadence. A VRC6 sawtooth
 * runs a 12-bit divider every CPU cycle but only advances its 8-bit accumulator
 * every other tick; a Namco 163 cycles through up to 8 voices on a shared
 * divider; an OPLL (VRC7) on a 49.7 kHz internal sample clock. Burying the
 * cadence inside the channel keeps the mixer agnostic.
 *
 * The sample contract is a Float roughly in `[0.0, 1.0]` (0 = silent, 1 = full
 * scale for that channel). The mixer sums these linearly on top of the native
 * 2A03 mix, matching how real cartridges combined audio over the unfiltered
 * expansion-audio pin.
 */
interface ExpansionAudioChannel {
    /**
     * Advance this channel by [cycles] CPU (M2) cycles. The APU calls this
     * once per CPU cycle with `cycles=1` from [Apu.tick]; implementations may
     * see batched calls in the future, so don't assume `cycles == 1`.
     */
    fun tick(cycles: Int)

    /**
     * The channel's current output sample, normalised to roughly `[0.0, 1.0]`.
     * Read once per APU output sample (44.1 kHz by default), so this should be
     * cheap — do the heavy lifting in [tick] and cache the result.
     */
    fun currentSample(): Float
}
