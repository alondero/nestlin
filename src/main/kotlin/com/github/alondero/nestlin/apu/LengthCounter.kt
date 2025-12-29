package com.github.alondero.nestlin.apu

class LengthCounter {
    var value: Int = 0
    var halt: Boolean = false

    // Length counter lookup table (NESdev)
    private val lengthTable = intArrayOf(
        10, 254, 20,  2, 40,  4, 80,  6, 160,  8, 60, 10, 14, 12, 26, 14,
        12,  16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    )

    fun loadCounter(index: Int) {
        value = lengthTable[index and 0x1F]
    }

    fun clock() {
        if (!halt && value > 0) {
            value--
        }
    }
}
