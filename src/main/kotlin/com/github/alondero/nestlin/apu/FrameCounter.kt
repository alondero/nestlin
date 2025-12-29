package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.isBitSet

class FrameCounter {
    enum class Mode {
        FOUR_STEP,
        FIVE_STEP
    }

    var mode: Mode = Mode.FOUR_STEP
    var irqInhibit: Boolean = false
    var step: Int = 0
    var cyclesSinceReset: Int = 0

    // Cycle counts for frame counter steps (NTSC)
    private val fourStepSequence = intArrayOf(7457, 14913, 22371, 29829)
    private val fiveStepSequence = intArrayOf(7457, 14913, 22371, 29829, 37281)

    fun tick(cpuCycles: Int): Pair<Boolean, Boolean> {
        var quarterFrameClock = false
        var halfFrameClock = false

        val sequence = if (mode == Mode.FOUR_STEP) fourStepSequence else fiveStepSequence

        // Check each step in the sequence and fire if we've reached that cycle
        while (step < sequence.size && cpuCycles >= sequence[step]) {
            when (step) {
                0 -> {
                    // Step 0: Quarter frame (envelope & linear counter)
                    quarterFrameClock = true
                }
                1 -> {
                    // Step 1: Half frame (length counter & sweep)
                    quarterFrameClock = true
                    halfFrameClock = true
                }
                2 -> {
                    // Step 2: Quarter frame
                    quarterFrameClock = true
                }
                3 -> {
                    // Step 3: End of frame (quarter & half frame)
                    quarterFrameClock = true
                    halfFrameClock = true
                }
                4 -> {
                    // Step 4: 5-step mode only - clock everything
                    quarterFrameClock = true
                    halfFrameClock = true
                }
            }
            step++
        }

        // Reset at end of sequence
        val maxCycles = if (mode == Mode.FOUR_STEP) 29830 else 37282
        if (cpuCycles >= maxCycles) {
            step = 0
        }

        return Pair(quarterFrameClock, halfFrameClock)
    }

    fun write4017(value: Byte) {
        mode = if (value.isBitSet(7)) Mode.FIVE_STEP else Mode.FOUR_STEP
        irqInhibit = value.isBitSet(6)
        step = 0
        cyclesSinceReset = 0

        // If 5-step mode, immediately clock quarter and half frame
        // (This is simplified; actual hardware has more complex timing)
    }

    fun reset() {
        step = 0
        cyclesSinceReset = 0
    }
}
