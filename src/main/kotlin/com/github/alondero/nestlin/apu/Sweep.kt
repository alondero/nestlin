package com.github.alondero.nestlin.apu

import java.io.DataInput
import java.io.DataOutput

class Sweep(val channelId: Int) {
    var enabled: Boolean = false
    var period: Int = 0
    var negate: Boolean = false
    var shift: Int = 0
    var reloadFlag: Boolean = false

    private var divider: Int = 0

    fun configure(enabled: Boolean, period: Int, negate: Boolean, shift: Int) {
        this.enabled = enabled
        this.period = period
        this.negate = negate
        this.shift = shift
    }

    fun clock(currentPeriod: Int, updatePeriod: (Int) -> Unit) {
        if (divider == 0 && enabled && shift > 0) {
            val changeAmount = currentPeriod shr shift
            val targetPeriod = if (negate) {
                // Pulse 1 uses one's complement, Pulse 2 uses two's complement
                if (channelId == 1) {
                    currentPeriod - changeAmount - 1
                } else {
                    currentPeriod - changeAmount
                }
            } else {
                currentPeriod + changeAmount
            }

            // Hardware writes the target whenever the divider has elapsed;
            // the only clock-layer muting condition is the upper-bound
            // overflow (target > 0x7FF). The lower bound (`target < 8`) is
            // masked by `PulseChannel.output()`'s separate ultrasonic
            // silence gate, so writing sub-8 targets here is correct.
            if (targetPeriod in 0..0x7FF) {
                updatePeriod(targetPeriod)
            }
        }

        if (divider == 0 || reloadFlag) {
            divider = period
            reloadFlag = false
        } else {
            divider--
        }
    }

    fun isMuting(currentPeriod: Int): Boolean {
        // Mutes if current period < 8 or the target period overflows the
        // 11-bit field. Hardware computes the target continuously even when
        // shift == 0 — `currentPeriod shr 0 == currentPeriod`, so add mode
        // gives `target = 2 * currentPeriod` (mute if > 0x3FF) and subtract
        // mode gives 0/-1 (also a mute condition). The pre-fix `shift == 0 →
        // return false` short-circuit silently kept the channel audible
        // through the overflow.
        if (currentPeriod < 8) return true

        val changeAmount = currentPeriod shr shift
        val targetPeriod = if (negate) {
            if (channelId == 1) {
                currentPeriod - changeAmount - 1
            } else {
                currentPeriod - changeAmount
            }
        } else {
            currentPeriod + changeAmount
        }

        return targetPeriod < 0 || targetPeriod > 0x7FF
    }

    fun saveState(out: DataOutput) {
        out.writeBoolean(enabled)
        out.writeInt(period)
        out.writeBoolean(negate)
        out.writeInt(shift)
        out.writeBoolean(reloadFlag)
        out.writeInt(divider)
    }

    fun loadState(input: DataInput) {
        enabled = input.readBoolean()
        period = input.readInt()
        negate = input.readBoolean()
        shift = input.readInt()
        reloadFlag = input.readBoolean()
        divider = input.readInt()
    }
}
