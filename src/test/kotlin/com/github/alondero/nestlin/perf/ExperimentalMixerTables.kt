package com.github.alondero.nestlin.perf

/** Benchmark-only exact DAC candidate. Production retains the original total mixer formulas. */
internal object ExperimentalMixerTables {
    private val pulse = DoubleArray(31) { sum ->
        if (sum > 0) 95.88 / ((8128.0 / sum) + 100.0) else 0.0
    }
    private val tnd = DoubleArray(16 * 16 * 128) { index ->
        val triangle = index ushr 11
        val noise = (index ushr 7) and 15
        val dmc = index and 127
        if (triangle > 0 || noise > 0 || dmc > 0) {
            val sum = (triangle / 8227.0) + (noise / 12241.0) + (dmc / 22638.0)
            if (sum > 0.0) 159.79 / ((1.0 / sum) + 100.0) else 0.0
        } else 0.0
    }

    /** @throws IllegalArgumentException if [sum] is outside the two-pulse DAC domain 0..30. */
    fun pulse(sum: Int): Double {
        require(sum in 0..30) { "pulse sum must be in 0..30, got $sum" }
        return pulse[sum]
    }
    /**
     * Requires triangle/noise in 0..15 and DMC in 0..127 before packing the tuple.
     * @throws IllegalArgumentException for any component outside its DAC domain.
     */
    fun tnd(triangle: Int, noise: Int, dmc: Int): Double {
        require(triangle in 0..15) { "triangle must be in 0..15, got $triangle" }
        require(noise in 0..15) { "noise must be in 0..15, got $noise" }
        require(dmc in 0..127) { "dmc must be in 0..127, got $dmc" }
        return tnd[(triangle shl 11) or (noise shl 7) or dmc]
    }
}
