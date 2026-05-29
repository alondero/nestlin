package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.EmulatorConfig

/**
 * Drives the hold-Tab fast-forward gesture.
 *
 * Fast-forward reuses the existing throttle mechanism: while engaged, speed
 * throttling is disabled so the emulation loop runs at maximum wall-clock speed
 * (see [com.github.alondero.nestlin.Nestlin.syncToWallClock], which early-returns
 * when throttling is off). On release the *prior* throttle setting is restored, so
 * a user who had already toggled throttling off via Ctrl+T isn't surprised by it
 * snapping back on.
 *
 * Audio coherence is preserved without any work here: the APU's circular
 * [com.github.alondero.nestlin.apu.AudioBuffer] drops the oldest sample on overrun
 * rather than injecting silence, so the consumer is fed continuously even when the
 * producer races ahead.
 */
class FastForwardController(private val config: EmulatorConfig) {

    /** True while the fast-forward gesture is held. Drives the on-screen indicator. */
    var active: Boolean = false
        private set

    private var savedThrottling: Boolean = config.speedThrottlingEnabled

    /**
     * Begin fast-forward. Idempotent: key auto-repeat fires KEY_PRESSED repeatedly
     * while held, but only the first call captures the prior throttle setting.
     */
    fun engage() {
        if (active) return
        savedThrottling = config.speedThrottlingEnabled
        config.speedThrottlingEnabled = false
        active = true
    }

    /** End fast-forward, restoring the throttle setting captured at [engage]. No-op if not active. */
    fun release() {
        if (!active) return
        config.speedThrottlingEnabled = savedThrottling
        active = false
    }
}
