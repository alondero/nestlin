package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Apu
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio health probe — tracks APU underruns observed at the audio
 * output layer.
 *
 * The 2A03 APU's inner sample-poll path ([Apu.getAudioSamples]) is
 * a hot, allocation-free function called every audio frame; the
 * benchmark CLI wants to observe "how often did the APU produce no
 * samples" without coupling the APU domain model to benchmark
 * metrics. This observer wraps the APU poll: the benchmark owns the
 * observer and reads its count after a window of frames.
 *
 * Why a wrapper, not an atomic counter on Apu.kt: a counter baked
 * into the Apu class would (a) violate the layering — Apu models
 * NES hardware, not benchmark telemetry — and (b) be impossible to
 * opt out of for production audio paths that don't care.
 *
 * Thread-safe; the APU is polled from one thread (typically the FX
 * audio thread or a benchmark driver) and the observer is read from
 * the same thread.
 */
class AudioHealthObserver {
    private val silentReads = AtomicLong(0L)

    /**
     * Wrap the [Apu] with this observer. Subsequent calls to
     * [Apu.getAudioSamples] increment the silent-reads counter on
     * empty returns and are returned unchanged. Replaces the
     * production `getAudioSamples` reference; the APU instance is
     * not mutated (the wrap is by composition at the consumer
     * layer).
     */
    fun wrap(apu: Apu): AudioSampleSource = Wrapped(apu, this)

    /** Reset the counter — benchmark entry point for per-window measurement. */
    fun reset() { silentReads.set(0L) }

    /** Total empty-buffer polls since the last [reset]. */
    fun silentReads(): Long = silentReads.get()

    private fun recordSilentRead() { silentReads.incrementAndGet() }

    /** Drop-in replacement for `Apu.getAudioSamples` that records underruns. */
    interface AudioSampleSource {
        fun getAudioSamples(): ShortArray
    }

    private class Wrapped(
        private val apu: Apu,
        private val observer: AudioHealthObserver,
    ) : AudioSampleSource {
        override fun getAudioSamples(): ShortArray {
            val samples = apu.getAudioSamples()
            if (samples.isEmpty()) observer.recordSilentRead()
            return samples
        }
    }
}
