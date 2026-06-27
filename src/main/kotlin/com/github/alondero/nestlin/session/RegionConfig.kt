package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Region

/**
 * Resolved timing region for the currently loaded ROM, plus the derived throttle values.
 *
 * Built once at `load()` / `powerReset()` from `EmulatorConfig.regionOverride ?: gamePak.region
 * ?: Region.NTSC`, then pushed into `Ppu.region` / `Apu.region` and the `RunLoop` throttle.
 * Previously the effective region was held in `Nestlin.region` AND `EmulatorConfig.regionOverride`,
 * and `EmulatorConfig.targetFps` was *written back* from `region.refreshRateHz` on every
 * `applyRegion()` — three places that had to stay in sync. `RegionConfig` makes the effective
 * region + fps a single read.
 *
 * Note: this only owns the *effective* values for a loaded session. The user's manual override
 * still lives on `EmulatorConfig.regionOverride` (it's a knob, not a derived value).
 *
 * @property region The active [Region] (NTSC or PAL).
 */
data class RegionConfig(val region: Region) {

    /** Display refresh rate in Hz, used for wall-clock throttling. Mirrors `region.refreshRateHz`. */
    val targetFps: Double get() = region.refreshRateHz

    /** Target time per frame in nanoseconds — derived once from [targetFps]. */
    val targetFrameTimeNanos: Long get() = (1_000_000_000.0 / targetFps).toLong()

    companion object {
        /** Fallback used when no ROM is loaded and no override is set. Mirrors the old `applyRegion()` default. */
        val NtscDefault = RegionConfig(Region.NTSC)
    }
}
