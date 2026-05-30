package com.github.alondero.nestlin

/**
 * Console region / TV-system timing profile.
 *
 * The NES shipped as two incompatible machines. Everything that differs between
 * them in a way the emulation core cares about lives here, so no other file needs
 * to hard-code a region-specific number:
 *
 * - **NTSC** (US/Japan): 262 scanlines/frame, ~60.0988 Hz, 1.789773 MHz CPU,
 *   PPU runs exactly 3× the CPU clock.
 * - **PAL** (most of Europe/Australia): 312 scanlines/frame, ~50.007 Hz,
 *   1.662607 MHz CPU, PPU runs 3.2× the CPU clock.
 *
 * The PPU:CPU ratio is the awkward one — PAL's 3.2 isn't an integer, so the main
 * loop spreads it as 3,3,3,3,4 dots per CPU cycle (see [ppuDotsPerCpuTimes10]).
 *
 * NTSC values below are copied verbatim from the constants they replace, so an
 * NTSC machine is bit-identical to the pre-region code.
 */
enum class Region(
    /** Total scanlines per frame, including post-render and pre-render lines. */
    val totalScanlines: Int,
    /** Index of the pre-render ("dummy") scanline — the last line of the frame. */
    val preRenderScanline: Int,
    /** CPU (and therefore APU) master clock in Hz. */
    val cpuFrequencyHz: Double,
    /** Display refresh rate in Hz, used for wall-clock throttling. */
    val refreshRateHz: Double,
    /**
     * PPU dots per CPU cycle, scaled ×10 to stay integer (NTSC 30 = 3.0,
     * PAL 32 = 3.2). The main loop accumulates this and emits one PPU tick per
     * 10 credits, so PAL naturally alternates 3 and 4 dots without float drift.
     */
    val ppuDotsPerCpuTimes10: Int,
    /** Whole CPU cycles per frame, used only to derive nanoseconds-per-tick for throttling. */
    val cpuCyclesPerFrame: Long,
    /** APU frame-counter step boundaries (CPU cycles) in 4-step mode. */
    val apuFourStepSequence: IntArray,
    /** APU frame-counter step boundaries (CPU cycles) in 5-step mode. */
    val apuFiveStepSequence: IntArray,
    /** CPU cycle at which the 4-step frame-counter sequence wraps. */
    val apuFourStepMaxCycles: Int,
    /** CPU cycle at which the 5-step frame-counter sequence wraps. */
    val apuFiveStepMaxCycles: Int,
    /** Noise channel period lookup table (CPU cycles), indexed by the 4-bit period field. */
    val noisePeriods: IntArray,
    /** DMC channel rate lookup table (CPU cycles), indexed by the 4-bit rate field. */
    val dmcRates: IntArray
) {
    NTSC(
        totalScanlines = 262,
        preRenderScanline = 261,
        cpuFrequencyHz = 1_789_773.0,
        refreshRateHz = 60.0988,
        ppuDotsPerCpuTimes10 = 30,
        cpuCyclesPerFrame = 29830L,
        apuFourStepSequence = intArrayOf(7457, 14913, 22371, 29829),
        apuFiveStepSequence = intArrayOf(7457, 14913, 22371, 29829, 37281),
        apuFourStepMaxCycles = 29830,
        apuFiveStepMaxCycles = 37282,
        noisePeriods = intArrayOf(4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068),
        dmcRates = intArrayOf(428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54)
    ),
    PAL(
        totalScanlines = 312,
        preRenderScanline = 311,
        cpuFrequencyHz = 1_662_607.0,
        refreshRateHz = 50.007,
        ppuDotsPerCpuTimes10 = 32,
        cpuCyclesPerFrame = 33248L,
        apuFourStepSequence = intArrayOf(8313, 16627, 24939, 33253),
        apuFiveStepSequence = intArrayOf(8313, 16627, 24939, 33253, 41565),
        apuFourStepMaxCycles = 33254,
        apuFiveStepMaxCycles = 41566,
        noisePeriods = intArrayOf(4, 8, 14, 30, 60, 88, 118, 148, 188, 236, 354, 472, 708, 944, 1890, 3778),
        dmcRates = intArrayOf(398, 354, 316, 298, 276, 236, 210, 198, 176, 148, 132, 118, 98, 78, 66, 50)
    )
}
