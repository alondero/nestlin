package com.github.alondero.nestlin.apu

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

            if (targetPeriod in 8..0x7FF) {
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
        // Mutes if current period < 8 or target period would be > $7FF
        if (currentPeriod < 8) return true
        if (shift == 0) return false

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

        return targetPeriod > 0x7FF
    }
}
