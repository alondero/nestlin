package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Issue #50 — ExpansionAudioChannel + APU mixer extension.
 *
 * Most of the surface (registration / clear / count) is easy to assert. The
 * interesting one is the "1 kHz fake-square expansion channel verifies output
 * spectrum" criterion: we inject a square wave through the mixer, pull the
 * sampled output back out, and use a Goertzel filter (one bin per frequency
 * of interest — cheaper than an FFT and uses no external library) to confirm
 * the spectrum has a 1 kHz fundamental and weak energy elsewhere.
 */
class ExpansionAudioChannelTest {

    /** NTSC CPU frequency from [com.github.alondero.nestlin.Region.NTSC]. */
    private val ntscCpuHz = 1_789_773.0

    /** 44.1 kHz, matching [Apu.outputSampleRateHz]. */
    private val sampleRateHz = 44_100.0

    @Test
    fun `register and clear track the expansion channel list`() {
        val apu = Apu(Memory())
        val ch1 = NullExpansionChannel()
        val ch2 = NullExpansionChannel()

        assertThat(apu.expansionChannelCount(), equalTo(0))
        apu.registerExpansionChannel(ch1)
        assertThat(apu.expansionChannelCount(), equalTo(1))
        apu.registerExpansionChannel(ch2)
        assertThat(apu.expansionChannelCount(), equalTo(2))

        // Same instance twice → no duplicate; mappers re-registering their own
        // channels on power-cycle must not stack copies.
        apu.registerExpansionChannel(ch1)
        assertThat(apu.expansionChannelCount(), equalTo(2))

        apu.clearExpansionChannels()
        assertThat(apu.expansionChannelCount(), equalTo(0))
    }

    @Test
    fun `silent expansion channel does not change mixer output`() {
        // Sanity: a registered channel that always returns 0.0 must produce
        // bit-identical samples to no channel at all. Otherwise the
        // expansion-mix path is leaking DC offset into the 2A03 path.
        val withChannel = renderToSamples(0.05) { apu ->
            apu.registerExpansionChannel(NullExpansionChannel())
        }
        val withoutChannel = renderToSamples(0.05) { /* nothing */ }

        assertThat(withChannel.size, equalTo(withoutChannel.size))
        val firstDiff = withChannel.indices.firstOrNull { withChannel[it] != withoutChannel[it] }
        assertThat(firstDiff, equalTo<Int?>(null))
    }

    @Test
    fun `1 kHz square expansion channel produces a 1 kHz peak in the mixer output`() {
        // Drive ~0.25 seconds of audio with a 1 kHz fake square wave on the
        // expansion bus and confirm the output spectrum has a fundamental at
        // 1 kHz dominating the off-band neighbours.
        val samples = renderToSamples(0.25) { apu ->
            apu.registerExpansionChannel(FakeSquareChannel(frequencyHz = 1000.0, cpuHz = ntscCpuHz))
        }

        assertThat(samples.size, greaterThan(8000))   // ~11k expected (0.25s × 44.1kHz)

        // Goertzel at the fundamental and at off-band frequencies. A square
        // wave has odd-harmonic content, so 1 kHz should also clobber the
        // even-harmonic 2 kHz and the off-grid 500 Hz / 1500 Hz neighbours.
        val mag1000 = goertzelMagnitude(samples, 1000.0, sampleRateHz)
        val mag500  = goertzelMagnitude(samples,  500.0, sampleRateHz)
        val mag1500 = goertzelMagnitude(samples, 1500.0, sampleRateHz)
        val mag2000 = goertzelMagnitude(samples, 2000.0, sampleRateHz)

        // A perfect 1 kHz square has zero energy at 500/1500/2000 Hz; we
        // require at least a 5× margin to leave headroom for the windowing
        // artefacts of analysing a finite ~250 ms segment without a window
        // function. In practice the ratios come out much higher (>50×).
        assertThat(
            "1 kHz magnitude $mag1000 should dominate 500 Hz magnitude $mag500",
            mag1000 > mag500 * 5.0,
            equalTo(true)
        )
        assertThat(
            "1 kHz magnitude $mag1000 should dominate 1500 Hz magnitude $mag1500",
            mag1000 > mag1500 * 5.0,
            equalTo(true)
        )
        assertThat(
            "1 kHz magnitude $mag1000 should dominate 2000 Hz magnitude $mag2000",
            mag1000 > mag2000 * 5.0,
            equalTo(true)
        )
        assertThat(
            "1 kHz magnitude $mag1000 should be measurably above noise (got $mag1000)",
            mag1000 > 100.0,
            equalTo(true)
        )
    }

    @Test
    fun `clearing the expansion channel silences subsequent output`() {
        val apu = Apu(Memory())
        val ch = FakeSquareChannel(frequencyHz = 1000.0, cpuHz = ntscCpuHz)
        apu.registerExpansionChannel(ch)
        runCycles(apu, seconds = 0.05)
        apu.getAudioSamples()           // drain pre-clear samples
        apu.clearExpansionChannels()
        runCycles(apu, seconds = 0.05)
        val postClear = apu.getAudioSamples()
        val mag = goertzelMagnitude(postClear, 1000.0, sampleRateHz)
        assertThat("After clear, 1 kHz magnitude should collapse (got $mag)",
            mag < 50.0, equalTo(true))
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Render `seconds` seconds of NTSC audio through a fresh APU, returning
     * the captured 16-bit samples. The configurator hook lets each test wire
     * its own expansion channels before clocking begins.
     */
    private fun renderToSamples(seconds: Double, configure: (Apu) -> Unit): ShortArray {
        val apu = Apu(Memory())
        configure(apu)
        runCycles(apu, seconds)
        return apu.getAudioSamples()
    }

    private fun runCycles(apu: Apu, seconds: Double) {
        val cycles = (ntscCpuHz * seconds).toInt()
        repeat(cycles) { apu.tick() }
    }

    /**
     * Magnitude of one frequency bin via Goertzel's algorithm — an O(N) IIR
     * that beats a full FFT when only a handful of bins are needed. Reference:
     * https://en.wikipedia.org/wiki/Goertzel_algorithm
     */
    private fun goertzelMagnitude(samples: ShortArray, freqHz: Double, sampleRateHz: Double): Double {
        if (samples.isEmpty()) return 0.0
        val k = (samples.size * freqHz / sampleRateHz).toInt().coerceAtLeast(1)
        val w = 2.0 * PI * k / samples.size
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in samples.indices) {
            val s = samples[i].toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        // |X[k]|^2 = s1^2 + s2^2 - coeff * s1 * s2
        val powerSq = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return if (powerSq > 0) sqrt(powerSq) else 0.0
    }
}

/** Zero-output channel — confirms the mixer's "silent" path stays bit-identical. */
private class NullExpansionChannel : ExpansionAudioChannel {
    override fun tick(cycles: Int) { /* no state */ }
    override fun currentSample(): Float = 0.0f
}

/**
 * 50%-duty square wave at [frequencyHz], generated by counting CPU cycles
 * through `cpuHz / (2 × frequencyHz)` per half-period.
 */
private class FakeSquareChannel(frequencyHz: Double, cpuHz: Double) : ExpansionAudioChannel {
    private val halfPeriodCycles = (cpuHz / (2.0 * frequencyHz)).toInt().coerceAtLeast(1)
    private var counter = 0
    private var high = false

    override fun tick(cycles: Int) {
        counter += cycles
        while (counter >= halfPeriodCycles) {
            counter -= halfPeriodCycles
            high = !high
        }
    }

    // Full-scale 1.0 when high, 0.0 when low — the expansion-mix path will
    // scale by EXPANSION_GAIN inside the APU.
    override fun currentSample(): Float = if (high) 1.0f else 0.0f
}
