package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin

/**
 * Advance this emulator by exactly one PPU frame.
 *
 * Drives [Nestlin.stepCpuCycle] and watches the existing one-shot
 * [com.github.alondero.nestlin.ppu.Ppu.frameJustCompleted] flag as the boundary signal. This is the
 * deterministic seam the movie record/replay engine runs on: no wall clock, no threads, and no
 * contention with the single-slot render listener.
 */
fun Nestlin.runOneFrame() {
    while (true) {
        stepCpuCycle()
        if (ppu.frameJustCompleted()) return
    }
}
