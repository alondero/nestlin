package com.github.alondero.nestlin.apu

import kotlin.math.roundToInt

class AudioResampler(
    inputRate: Double,
    outputRate: Double,
    bufferCapacity: Int = 16384
) {
    private val ratio = inputRate / outputRate
    private val buffer = ShortArray(bufferCapacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private var position = 0.0

    fun push(samples: ShortArray) {
        for (sample in samples) {
            if (size < buffer.size) {
                buffer[tail] = sample
                tail = ((tail + 1) % buffer.size + buffer.size) % buffer.size
                size++
            } else {
                // Drop oldest sample to avoid unbounded growth.
                buffer[tail] = sample
                tail = ((tail + 1) % buffer.size + buffer.size) % buffer.size
                head = ((head + 1) % buffer.size + buffer.size) % buffer.size
                // Decrement position to account for dropped sample.
                // Position can go negative when position < 1, which is OK -
                // the next resample() call will properly discard samples based on floor(position).
                position -= 1.0
            }
        }
    }

    fun resample(output: ShortArray, maxSamples: Int): Int {
        if (maxSamples <= 0) return 0

        var produced = 0
        while (produced < maxSamples) {
            val idx = position.toInt()
            if (idx + 1 >= size) break

            val s0 = sampleAt(idx).toInt()
            val s1 = sampleAt(idx + 1).toInt()
            val frac = position - idx
            val mixed = s0 + ((s1 - s0) * frac)
            output[produced] = mixed.roundToInt().coerceIn(-32768, 32767).toShort()
            produced++
            position += ratio
        }

        // Discard consumed samples from buffer
        // Note: position can be negative if we dropped samples when position < 1
        // In that case, clamp to 0 since we've consumed beyond the buffer start
        if (position < 0) {
            head = 0
            position = 0.0
        } else {
            // The CORRECT formula: we consumed `produced` input samples (each output consumes `ratio` samples).
            // So we should discard `produced` samples, not floor(position).
            // Note: `produced` is the count of outputs we just generated.
            if (produced > 0) {
                discard(produced)
                position -= produced
            }
        }

        return produced
    }

    fun clear() {
        head = 0
        tail = 0
        size = 0
        position = 0.0
    }

    private fun sampleAt(offset: Int): Short {
        // Ensure positive index by using proper modulo for potentially negative values
        val index = ((head + offset) % buffer.size + buffer.size) % buffer.size
        return buffer[index]
    }

    private fun discard(count: Int) {
        val toDrop = minOf(count, size)
        if (toDrop <= 0) return
        head = ((head + toDrop) % buffer.size + buffer.size) % buffer.size
        size -= toDrop
    }
}
