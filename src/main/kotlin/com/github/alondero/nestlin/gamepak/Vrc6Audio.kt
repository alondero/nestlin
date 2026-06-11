package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.apu.ExpansionAudioChannel
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * VRC6 expansion-audio channels (Issue #58). Three channels: two 4-bit pulse
 * generators with duty-cycle control and one 5-bit sawtooth. They register
 * with [com.github.alondero.nestlin.Apu] via the [ExpansionAudioChannel] API
 * added in Issue #50 and contribute linearly to the final mix.
 *
 * Register and behaviour details are sourced from
 * https://www.nesdev.org/wiki/VRC6_audio (the page that
 * https://www.nesdev.org/wiki/VRC6 defers to for the audio block). Kevin
 * Horton's "VRCVI Chip Info" is the underlying reverse-engineering source.
 */

/**
 * VRC6 pulse oscillator — one of two. Two of these live in a [Vrc6] mapper,
 * routed to registers `$9000-$9002` (pulse 1) and `$A000-$A002` (pulse 2).
 *
 * Hardware shape: a 16-step duty-cycle generator counts down 15→0; on each
 * step the channel outputs the volume V when `step ≤ D` (the duty threshold),
 * otherwise 0. With the "ignore duty" mode bit set, V is always output. The
 * timer divides the CPU clock by `(t+1) × 16` for the step rate (`f = CPU /
 * (16 × (t+1))`). Disabling the channel forces output to 0 and resets the
 * duty step immediately.
 */
class Vrc6Pulse : ExpansionAudioChannel {
    private var volume = 0           // V: 4-bit (0..15)
    private var duty = 0             // D: 3-bit (0..7) — 1/16 through 8/16 step threshold
    private var ignoreDuty = false   // M: when set, output V continuously
    private var period = 0           // t: 12-bit divider reload
    private var enabled = false      // E: gate at $x002 bit 7
    private var step = 0xF           // duty cycle generator step (counts 15..0)
    private var timer = 0            // CPU-cycle countdown to the next step advance

    /**
     * Per-call CPU-cycle shift from the chip-wide [Vrc6FrequencyControl]:
     * 0 = run at written period, 4 = 16× faster, 8 = 256× faster. Vrc6 pokes
     * this into every channel whenever `$9003` changes so the channels can
     * stay oblivious to the shared frequency-control register.
     */
    internal var periodShift = 0

    /** Halt latch from `$9003` bit 0. When set, the channel freezes its state. */
    internal var halt = false

    fun write4000(value: Byte) {
        val v = value.toUnsignedInt()
        ignoreDuty = (v and 0x80) != 0
        duty = (v shr 4) and 0x07
        volume = v and 0x0F
    }

    fun write4001(value: Byte) {
        period = (period and 0xF00) or value.toUnsignedInt()
    }

    fun write4002(value: Byte) {
        val v = value.toUnsignedInt()
        period = (period and 0x0FF) or ((v and 0x0F) shl 8)
        val newEnabled = (v and 0x80) != 0
        if (!newEnabled) {
            // Disabling resets the duty step immediately; phase can be reset
            // by toggling E off then on. Real chip: the step counter snaps
            // back to 0xF the moment E falls.
            step = 0xF
        }
        enabled = newEnabled
    }

    override fun tick(cycles: Int) {
        if (halt || !enabled) return
        val effectivePeriod = (period shr periodShift) + 1   // f = CPU / (16 × effectivePeriod)
        timer -= cycles
        while (timer <= 0) {
            timer += effectivePeriod
            // 16-step generator counts 0xF..0x0 then wraps to 0xF.
            step = if (step == 0) 0xF else step - 1
        }
    }

    /**
     * Hardware DAC value 0..15 — used by the [Vrc6] snapshot for tests + the
     * future debugger. Mixer consumers use [currentSample] instead.
     */
    fun rawOutput(): Int {
        if (!enabled) return 0
        // step is in 0..15; output V when step <= D. With M=1, output is always V.
        return if (ignoreDuty || step <= duty) volume else 0
    }

    override fun currentSample(): Float = rawOutput() / 15.0f

    fun saveState(out: DataOutput) {
        out.writeInt(volume)
        out.writeInt(duty)
        out.writeBoolean(ignoreDuty)
        out.writeInt(period)
        out.writeBoolean(enabled)
        out.writeInt(step)
        out.writeInt(timer)
        out.writeInt(periodShift)
        out.writeBoolean(halt)
    }

    fun loadState(input: DataInput) {
        volume = input.readInt()
        duty = input.readInt()
        ignoreDuty = input.readBoolean()
        period = input.readInt()
        enabled = input.readBoolean()
        step = input.readInt()
        timer = input.readInt()
        periodShift = input.readInt()
        halt = input.readBoolean()
    }
}

/**
 * VRC6 sawtooth oscillator. Routed to registers `$B000-$B002`.
 *
 * Hardware shape: an 8-bit accumulator is clocked every other CPU cycle by a
 * 12-bit divider. On each clock the rate A is added to the accumulator; after
 * six additions, the seventh clock resets the accumulator to 0. The output is
 * the top 5 bits of the accumulator (`0..31`). The divider clocks at half CPU
 * rate, giving `f = CPU / (14 × (t+1))` — `14` and not `12` because the
 * 6-add-then-reset cycle gives 7 steps per period of the divider.
 *
 * Disabling the channel forces the accumulator to 0 until the enable bit is
 * set again; the divider itself keeps running. Real-cart warning: rate values
 * above 42 overflow the accumulator before the reset, distorting the wave.
 */
class Vrc6Saw : ExpansionAudioChannel {
    private var rate = 0             // A: accumulator-increment value, 6-bit (0..63)
    private var period = 0           // t: 12-bit divider reload
    private var enabled = false      // E: gate at $B002 bit 7
    private var accumulator = 0      // 8-bit running sum
    private var steps = 0            // 0..6, counts toward the reset on step 7
    private var dividerPhase = false // every-other-CPU-cycle gate on accumulator clocks
    private var timer = 0            // CPU-cycle countdown to the next divider clock

    internal var periodShift = 0
    internal var halt = false

    fun writeB000(value: Byte) {
        rate = value.toUnsignedInt() and 0x3F
    }

    fun writeB001(value: Byte) {
        period = (period and 0xF00) or value.toUnsignedInt()
    }

    fun writeB002(value: Byte) {
        val v = value.toUnsignedInt()
        period = (period and 0x0FF) or ((v and 0x0F) shl 8)
        enabled = (v and 0x80) != 0
        // Disable forces the accumulator to 0; the divider phase is unaffected.
        if (!enabled) {
            accumulator = 0
            steps = 0
        }
    }

    override fun tick(cycles: Int) {
        if (halt) return
        val effectivePeriod = (period shr periodShift) + 1
        var c = cycles
        while (c > 0) {
            c--
            timer--
            if (timer > 0) continue
            timer = effectivePeriod
            // The divider clocks every CPU cycle, but the accumulator only
            // advances every other divider clock (NESdev: "clocks accumulator
            // every 2 CPU cycles"). The dividerPhase flag implements that.
            dividerPhase = !dividerPhase
            if (!dividerPhase) continue
            if (!enabled) continue
            if (steps == 6) {
                accumulator = 0
                steps = 0
            } else {
                accumulator = (accumulator + rate) and 0xFF
                steps++
            }
        }
    }

    /** Hardware DAC value 0..31 — top 5 bits of the 8-bit accumulator. */
    fun rawOutput(): Int = if (enabled) (accumulator ushr 3) else 0

    override fun currentSample(): Float = rawOutput() / 31.0f

    fun saveState(out: DataOutput) {
        out.writeInt(rate)
        out.writeInt(period)
        out.writeBoolean(enabled)
        out.writeInt(accumulator)
        out.writeInt(steps)
        out.writeBoolean(dividerPhase)
        out.writeInt(timer)
        out.writeInt(periodShift)
        out.writeBoolean(halt)
    }

    fun loadState(input: DataInput) {
        rate = input.readInt()
        period = input.readInt()
        enabled = input.readBoolean()
        accumulator = input.readInt()
        steps = input.readInt()
        dividerPhase = input.readBoolean()
        timer = input.readInt()
        periodShift = input.readInt()
        halt = input.readBoolean()
    }
}

/**
 * Shared `$9003` frequency-control state — a chip-wide register that retunes
 * (or freezes) all three oscillators at once. NESdev bit layout: `....	.ABH`
 * where `H` halts all oscillators, `B` shifts every period right by 4 (16×
 * faster), and `A` shifts every period right by 8 (256× faster). The 256×
 * flag wins over the 16× flag; the halt flag wins over both.
 */
internal class Vrc6FrequencyControl {
    var halt = false
        private set
    var periodShift = 0
        private set

    fun write9003(value: Byte) {
        val v = value.toUnsignedInt()
        halt = v.isBitSet(0)
        // Per NESdev: "The 256x flag overrides the 16x flag." Check 256x first.
        periodShift = when {
            v.isBitSet(2) -> 8
            v.isBitSet(1) -> 4
            else -> 0
        }
    }

    fun saveState(out: DataOutput) {
        // Round-trip through the same decode path used at runtime: pack the
        // live state back into the byte shape `$9003` uses, then write9003
        // it on load. That keeps the load path unable to drift from the write
        // path — there's no second decoder to maintain.
        var b = 0
        if (halt) b = b or 0x01
        when (periodShift) {
            4 -> b = b or 0x02
            8 -> b = b or 0x04
        }
        out.writeByte(b)
    }

    fun loadState(input: DataInput) {
        write9003(input.readByte())
    }
}

