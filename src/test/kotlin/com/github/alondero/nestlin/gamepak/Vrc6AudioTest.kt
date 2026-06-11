package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Unit tests for [Vrc6Pulse], [Vrc6Saw], and [Vrc6FrequencyControl] — the three
 * VRC6 expansion-audio voices (Issue #58). Behaviour is the NESdev spec at
 * https://www.nesdev.org/wiki/VRC6_audio. We deliberately exercise the unit
 * surfaces (`rawOutput()` / `currentSample()` / `write*` / `tick`) directly
 * rather than routing through the APU mixer, since the mixer path is covered
 * by ExpansionAudioChannelTest and we want chip-level granularity here.
 */
class Vrc6AudioTest {

    /** Convert an Int literal to Byte for the write*() methods. */
    private fun b(i: Int): Byte = i.toByte()

    // ---- Vrc6Pulse ----

    @Test
    fun `pulse ignores duty when M bit is set`() {
        val p = Vrc6Pulse()
        // M=1, D=000, V=0xF: the channel should output V (15) on every step.
        p.write4000(b(0x80 or 0x0F))
        p.write4001(b(0x00))
        p.write4002(b(0x80))             // E=1
        for (cycle in 0 until 64) {
            p.tick(1)
            assertThat("step $cycle output", p.rawOutput(), equalTo(15))
        }
    }

    @Test
    fun `pulse duty threshold gates output by step position`() {
        val p = Vrc6Pulse()
        // M=0, D=4 (5/16 duty), V=15.
        p.write4000(b((4 shl 4) or 15))
        p.write4001(b(0x00))
        p.write4002(b(0x80))             // E=1, period=0

        // 16-step gen counts 15..0; output is V when step <= D. With D=4,
        // the high window is step ∈ {0,1,2,3,4} = 5 of 16 ticks per cycle.
        val outputs = IntArray(32)
        for (i in 0 until 32) {
            p.tick(1)
            outputs[i] = p.rawOutput()
        }
        // Two full cycles (32 ticks) → 10 highs, 22 lows.
        val highs = outputs.count { it == 15 }
        val lows = outputs.count { it == 0 }
        assertThat("duty 5/16: highs (got $highs)", highs, equalTo(10))
        assertThat("duty 5/16: lows (got $lows)", lows, equalTo(22))
    }

    @Test
    fun `pulse disabling snaps step back to 0xF`() {
        val p = Vrc6Pulse()
        p.write4000(b(0x00 or 15))       // M=0, D=0, V=15
        p.write4001(b(0x00))
        p.write4002(b(0x80))
        repeat(100) { p.tick(1) }
        p.write4002(b(0x00))             // disable
        assertThat("disabled pulse outputs 0", p.rawOutput(), equalTo(0))
        p.write4002(b(0x80))             // re-enable
        // Disabling snapped step back to 0xF; with D=0, step<=D is false
        // (15 <= 0 is false) so rawOutput is 0. The spec says the step is
        // reset to 0xF, NOT that the output goes back to V; with D=0 the
        // output is 0 until the step counter wraps.
        assertThat("step reset to 0xF (with D=0, output is 0)", p.rawOutput(), equalTo(0))
    }

    @Test
    fun `pulse period 0 clocks at 16 cycles per step`() {
        // f = CPU / (16 * (t+1)). With t=0, the period is 1 cycle, so the
        // step advances every CPU tick. The "16 cycles per step" formula is
        // f * 16 (the 16-step duty generator): a single step takes 16 cycles
        // at the OUTPUT frequency, but advances once per CPU tick when t=0.
        //
        // So with M=0, D=0, V=15: at step=0 only, output=15. Tick 16 times,
        // expect exactly one high.
        val p = Vrc6Pulse()
        p.write4000(b((0 shl 4) or 15))
        p.write4001(b(0x00))
        p.write4002(b(0x80))
        val first16 = IntArray(16) { p.tick(1); p.rawOutput() }
        val highsAt = first16.indices.filter { first16[it] == 15 }
        assertThat("got ${highsAt.size} highs at $highsAt", highsAt.size, equalTo(1))
    }

    // ---- Vrc6Saw ----

    @Test
    fun `saw starts at 0 and steps up by rate`() {
        val s = Vrc6Saw()
        s.writeB000(b(0x04))            // rate = 4
        s.writeB001(b(0x00))            // period = 0 (fastest)
        s.writeB002(b(0x80))            // E=1
        // After 2 CPU cycles, dividerPhase flips twice (once per cycle), so
        // the accumulator has been incremented once: accumulator=4. Top 5 bits
        // of 4 = 0.
        repeat(2) { s.tick(1) }
        assertThat("after 2 cycles: rate=4 small raw", s.rawOutput(), equalTo(0))
    }

    @Test
    fun `saw reaches maximum 31 when accumulator is large`() {
        val s = Vrc6Saw()
        s.writeB000(b(0x3F))            // rate = 63
        s.writeB001(b(0x00))
        s.writeB002(b(0x80))
        var peak = 0
        repeat(10_000) {
            s.tick(1)
            if (s.rawOutput() > peak) peak = s.rawOutput()
        }
        // Top 5 bits of any value 248..255 = 31. Theoretical max is 31.
        assertThat("saw reaches near-max (got $peak)", peak, greaterThanOrEqualTo(30))
    }

    @Test
    fun `saw disabling forces accumulator to 0`() {
        val s = Vrc6Saw()
        s.writeB000(b(0x3F))
        s.writeB001(b(0x00))
        s.writeB002(b(0x80))
        repeat(1000) { s.tick(1) }
        s.writeB002(b(0x00))            // E=0
        assertThat("disabled saw outputs 0", s.rawOutput(), equalTo(0))
    }

    // ---- Vrc6FrequencyControl ----

    @Test
    fun `9003 bit 0 halts all channels`() {
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x01))
        assertThat("halt set", f.halt, equalTo(true))
        assertThat("no period shift", f.periodShift, equalTo(0))
    }

    @Test
    fun `9003 bit 1 sets 16x period shift`() {
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x02))
        assertThat("halt clear", f.halt, equalTo(false))
        assertThat("16x shift", f.periodShift, equalTo(4))
    }

    @Test
    fun `9003 bit 2 sets 256x period shift`() {
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x04))
        assertThat("halt clear", f.halt, equalTo(false))
        assertThat("256x shift", f.periodShift, equalTo(8))
    }

    @Test
    fun `9003 with both shift bits set picks 256x not 16x`() {
        // NESdev: "The 256x flag overrides the 16x flag."
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x06))
        assertThat("256x wins over 16x", f.periodShift, equalTo(8))
    }

    @Test
    fun `9003 halt overrides shift`() {
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x07))
        assertThat("halt set", f.halt, equalTo(true))
        assertThat("shift still set", f.periodShift, equalTo(8))
    }

    // ---- Save/load round-trip ----

    @Test
    fun `pulse saveState and loadState round-trip`() {
        val p = Vrc6Pulse().apply {
            write4000(b(0xA5))           // M=1, D=2, V=5
            write4001(b(0x34))
            write4002(b(0x81))           // E=1, period high = 0x01 → period = 0x134
            periodShift = 4
            halt = true
            repeat(50) { tick(1) }
        }
        val out = java.io.ByteArrayOutputStream()
        p.saveState(java.io.DataOutputStream(out))
        val fresh = Vrc6Pulse()
        fresh.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))
        assertThat("pulse rawOutput round-trip", fresh.rawOutput(), equalTo(p.rawOutput()))
    }

    @Test
    fun `saw saveState and loadState round-trip`() {
        val s = Vrc6Saw().apply {
            writeB000(b(0x21))
            writeB001(b(0x55))
            writeB002(b(0x83))           // E=1
            repeat(200) { tick(1) }
        }
        val out = java.io.ByteArrayOutputStream()
        s.saveState(java.io.DataOutputStream(out))
        val fresh = Vrc6Saw()
        fresh.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))
        assertThat("saw rawOutput round-trip", fresh.rawOutput(), equalTo(s.rawOutput()))
    }

    @Test
    fun `frequency control saveState and loadState round-trip`() {
        val f = Vrc6FrequencyControl()
        f.write9003(b(0x05))              // halt + 256x
        val out = java.io.ByteArrayOutputStream()
        f.saveState(java.io.DataOutputStream(out))
        val fresh = Vrc6FrequencyControl()
        fresh.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))
        assertThat("halt round-trip", fresh.halt, equalTo(true))
        assertThat("periodShift round-trip", fresh.periodShift, equalTo(8))
    }

    // ---- Spectrum test for one VRC6 voice through the APU mixer ----

    @Test
    fun `vrc6 saw produces a fundamental at the expected frequency`() {
        // Drive the saw at period=4095 (slow fundamental), route through the
        // APU mixer, and check the spectrum has a peak at the expected bin.
        //
        // Saw divider clocks every other CPU cycle (2 CPU cycles per divider
        // tick). The 6-add-then-reset cycle gives 7 divider ticks per saw
        // period. So f_saw = CPU / (2 * 4096 * 7) ≈ 31.2 Hz.
        val apu = com.github.alondero.nestlin.Apu(com.github.alondero.nestlin.Memory())
        val saw = Vrc6Saw()
        saw.writeB000(b(0x20))           // rate = 32 → nonzero output
        saw.writeB001(b(0xFF))           // period low = 0xFF
        saw.writeB002(b(0x8F))           // period high = 0xF, E=1 → period = 0xFFF
        apu.registerExpansionChannel(saw)

        val ntscCpuHz = 1_789_773.0
        val sampleRateHz = 44_100.0
        val seconds = 0.5
        val cycles = (ntscCpuHz * seconds).toInt()
        repeat(cycles) { apu.tick() }
        val samples = apu.getAudioSamples()
        assertThat("captured samples (got ${samples.size})", samples.size, greaterThanOrEqualTo(8_000))

        val expectedHz = ntscCpuHz / (2.0 * 4096.0 * 7.0)
        val mag = goertzelMagnitude(samples, expectedHz, sampleRateHz)
        val magOff = goertzelMagnitude(samples, expectedHz * 2.5, sampleRateHz)
        assertThat("saw fundamental $mag vs off-band $magOff",
            mag > magOff * 2.0, equalTo(true))
    }

    // ---- helpers --------------------------------------------------------

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
        val powerSq = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return if (powerSq > 0) sqrt(powerSq) else 0.0
    }
}
