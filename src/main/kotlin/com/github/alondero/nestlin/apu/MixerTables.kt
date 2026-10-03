package com.github.alondero.nestlin.apu

/** Exact DAC expressions from Apu.mixAndBuffer, shared by all APU instances. */
internal object MixerTables {
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

    fun pulse(sum: Int): Double = pulse[sum]
    fun tnd(triangle: Int, noise: Int, dmc: Int): Double = tnd[(triangle shl 11) or (noise shl 7) or dmc]
}
