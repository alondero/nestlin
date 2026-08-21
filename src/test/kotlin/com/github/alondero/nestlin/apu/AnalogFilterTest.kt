package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.closeTo
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import com.natpryce.hamkrest.lessThan
import com.natpryce.hamkrest.lessThanOrEqualTo
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Issue #229 — analog filter chain (90 Hz HP → 440 Hz HP → 14 kHz LP) emulating
 * the NES's post-DAC RC filter network before decimation into the audio buffer.
 *
 * The filter represents the analog domain where the 2A03's mixed output
 * physically passes through three first-order RC stages before reaching the TV.
 * Each stage is implemented as a single-pole IIR via the bilinear-transform
 * coefficients at 44.1 kHz. We verify the filter against:
 *
 *   1. DC rejection — the 90 Hz HP must drain a constant input toward zero
 *      (this is the "DC blocking coupling cap" that distinguishes hardware
 *      output from a digital-only model).
 *   2. Low-frequency attenuation — the 440 Hz HP must attenuate a 60 Hz sine
 *      heavily while leaving a 1 kHz sine alone (the "NES bass roll-off").
 *   3. High-frequency attenuation — the 14 kHz LP must attenuate content at
 *      15 kHz and beyond (the TV RF-interference filter).
 *   4. State hold / reset — the filter survives repeated calls without
 *      drifting and `reset()` returns the state to zero.
 *
 * If any of these regress (e.g. someone deletes the filter from
 * `Apu.mixAndBuffer`), the filter test still passes because it tests the
 * class directly — but the integration-level APU audio spectrum would shift.
 */
class AnalogFilterTest {

    private val sampleRate = 44100.0

    /**
     * Number of samples to discard before reading steady-state magnitudes.
     * The 90 Hz HP has a time constant of ~1.8 ms, so 5 ms is comfortable headroom
     * (≈3 τ) — beyond this the filter's transient on a tone is ≪1%.
     */
    private val settlingSamples = (sampleRate * 0.005).toInt()   // 220 samples

    /** Render `nSamples` samples of the filter fed by [inputFn] starting from a cold filter. */
    private fun render(inputFn: (Int) -> Double, nSamples: Int): DoubleArray {
        val filter = AnalogFilter(sampleRate)
        val out = DoubleArray(nSamples)
        for (i in 0 until nSamples) {
            out[i] = filter.process(inputFn(i))
        }
        return out
    }

    /** RMS magnitude of samples in [from, to) of a rendered buffer. */
    private fun rms(samples: DoubleArray, from: Int, to: Int): Double {
        var sumSq = 0.0
        for (i in from until to) sumSq += samples[i] * samples[i]
        return kotlin.math.sqrt(sumSq / (to - from))
    }

    @Test
    fun `90 Hz high pass drains DC to near zero`() {
        // Drive a constant +1.0 input. After the 90 Hz HP settles, the output
        // must approach zero (this is the DC-blocking coupling cap).
        val samples = render({ 1.0 }, 1024)
        val last = samples[samples.size - 1]
        assertThat(
            "DC input should be drained to near zero by the 90 Hz HP (got $last)",
            abs(last), lessThan(0.05)
        )
    }

    @Test
    fun `cascaded HPs strongly attenuate a 60 Hz tone`() {
        // Real-world check: NES audio is famous for lacking the deep bass that
        // a digital-only model would emit. The full filter chain has TWO high-
        // pass stages in series (90 Hz + 440 Hz) — a 60 Hz sine is well below
        // both cutoffs and gets hit by the product of their attenuations.
        val samples = render({ i -> sin(2.0 * PI * 60.0 * i / sampleRate) }, 4096)
        val inRms = 1.0 / kotlin.math.sqrt(2.0)
        val outRms = rms(samples, settlingSamples, samples.size)
        // |H_90(60)|  = 1 / sqrt(1 + (90/60)^2) ≈ 0.555
        // |H_440(60)| = 1 / sqrt(1 + (440/60)^2) ≈ 0.136
        // Combined:    0.555 × 0.136 ≈ 0.0756
        val hp90  = 1.0 / kotlin.math.sqrt(1.0 + (90.0 / 60.0).let { it * it })
        val hp440 = 1.0 / kotlin.math.sqrt(1.0 + (440.0 / 60.0).let { it * it })
        val expectedAttenuation = hp90 * hp440
        val ratio = outRms / inRms
        assertThat(
            "60 Hz should be reduced to ~$expectedAttenuation× of input RMS by the cascaded HPs (got $ratio)",
            ratio, closeTo(expectedAttenuation, 0.015)
        )
    }

    @Test
    fun `100 Hz tone is attenuated by both high-pass stages`() {
        // 100 Hz is above the 90 Hz cutoff (less HP attenuation) but still below
        // the 440 Hz cutoff (heavy HP attenuation). Verifying another data
        // point confirms the cascaded formula isn't an artefact of the
        // single 60 Hz test above.
        // 100 Hz:  |H_90(100)|  ≈ 0.668,  |H_440(100)| ≈ 0.222  →  0.148
        val samples = render({ i -> sin(2.0 * PI * 100.0 * i / sampleRate) }, 4096)
        val outRms = rms(samples, settlingSamples, samples.size)
        val inRms = 1.0 / kotlin.math.sqrt(2.0)
        val hp90  = 1.0 / kotlin.math.sqrt(1.0 + (90.0 / 100.0).let { it * it })
        val hp440 = 1.0 / kotlin.math.sqrt(1.0 + (440.0 / 100.0).let { it * it })
        val expected = hp90 * hp440
        val ratio = outRms / inRms
        assertThat(
            "100 Hz should be reduced to ~$expected× of input RMS (got $ratio)",
            ratio, closeTo(expected, 0.02)
        )
    }

    @Test
    fun `1 kHz tone passes through the filter chain with minimal attenuation`() {
        // 1 kHz is well above the two HP cutoffs and well below the LP cutoff,
        // so it should pass through almost unchanged.
        val samples = render({ i -> sin(2.0 * PI * 1000.0 * i / sampleRate) }, 4096)
        val inRms = 1.0 / kotlin.math.sqrt(2.0)
        val outRms = rms(samples, settlingSamples, samples.size)
        val ratio = outRms / inRms
        assertThat(
            "1 kHz should pass through the filter chain (got ${"%.3f".format(ratio)})",
            ratio, greaterThan(0.85)
        )
        assertThat(ratio, lessThanOrEqualTo(1.0))
    }

    @Test
    fun `14 kHz low pass attenuates a 15 kHz tone`() {
        // The LP cutoff is at 14 kHz; a 15 kHz sine should be visibly attenuated.
        val samples = render({ i -> sin(2.0 * PI * 15000.0 * i / sampleRate) }, 8192)
        val inRms = 1.0 / kotlin.math.sqrt(2.0)
        val outRms = rms(samples, settlingSamples, samples.size)
        val ratio = outRms / inRms
        // 14 kHz LP @ 15 kHz: |H| = |1 / sqrt(1 + (f/fc)^2)| = 1/sqrt(1 + (15/14)^2)
        //                                              = 1/sqrt(2.148) ≈ 0.682
        // Allow ±15% for the filter chain not being a single stage + settling.
        assertThat(
            "15 kHz should be attenuated by the 14 kHz LP (got ${"%.3f".format(ratio)})",
            ratio, lessThan(0.85)
        )
    }

    @Test
    fun `silence passes through unchanged`() {
        // Zero in → zero out. Otherwise there's a DC offset bug.
        val filter = AnalogFilter(sampleRate)
        repeat(1024) {
            assertThat(filter.process(0.0), equalTo(0.0))
        }
    }

    @Test
    fun `reset clears filter state`() {
        // Drive the filter for a while to populate its internal capacitors,
        // reset, and verify the next sample is exactly the input — proving
        // the IIR state has been wiped.
        val filter = AnalogFilter(sampleRate)
        repeat(1024) { filter.process(1.0) }
        filter.reset()
        // Immediately after reset, process(0) must return 0 (no leaked state).
        assertThat(filter.process(0.0), equalTo(0.0))
    }

    @Test
    fun `sustained tone output is stable across calls`() {
        // Regression guard for the IIR coefficients: a steady sine should
        // produce a stable RMS magnitude (no slow drift up or down) once
        // the filter has settled.
        val samples = render({ i -> sin(2.0 * PI * 1000.0 * i / sampleRate) }, 8192)
        val mid = rms(samples, 2048, 4096)
        val end = rms(samples, 6144, 8192)
        val drift = abs(mid - end) / mid
        assertThat(
            "1 kHz tone RMS should not drift (mid=$mid, end=$end, drift=$drift)",
            drift, lessThan(0.05)
        )
    }
}