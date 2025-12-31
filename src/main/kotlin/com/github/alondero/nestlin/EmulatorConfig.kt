package com.github.alondero.nestlin

/**
 * Configuration settings for the emulator.
 *
 * This class holds runtime configuration options that can be modified during emulation.
 */
data class EmulatorConfig(
    /**
     * Enable/disable speed throttling to match original NES timing.
     * When enabled, emulator runs at ~60 FPS (NTSC).
     * When disabled, emulator runs as fast as possible.
     * Default: true (throttling enabled)
     */
    var speedThrottlingEnabled: Boolean = true,

    /**
     * Target frame rate in frames per second.
     * NTSC (US/Japan): 60.0988 FPS
     * PAL (Europe): 50.007 FPS
     * Default: 60.0 (NTSC approximation)
     */
    var targetFps: Double = 60.0
) {
    /**
     * Target time per frame in nanoseconds.
     * Calculated from targetFps for precise timing.
     */
    val targetFrameTimeNanos: Long
        get() = (1_000_000_000.0 / targetFps).toLong()
}
