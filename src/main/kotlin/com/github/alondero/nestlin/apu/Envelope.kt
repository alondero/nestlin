package com.github.alondero.nestlin.apu

class Envelope {
    var startFlag: Boolean = false
    var divider: Int = 0
    var decayLevel: Int = 0

    var loop: Boolean = false
    var constantVolume: Boolean = false
    var volume: Int = 0

    val value: Int
        get() = if (constantVolume) volume else decayLevel

    fun clock() {
        if (startFlag) {
            startFlag = false
            decayLevel = 15
            divider = volume
        } else {
            if (divider > 0) {
                divider--
            } else {
                divider = volume
                if (decayLevel > 0) {
                    decayLevel--
                } else if (loop) {
                    decayLevel = 15
                }
            }
        }
    }

    fun reset() {
        decayLevel = 0
        divider = 0
    }
}
