package com.github.alondero.nestlin.movie

/**
 * One frame's worth of input in a movie's input log.
 *
 * [controller1]/[controller2] are button bitmaps in Nestlin's *internal* Controller bit order
 * (bit 0 = A … bit 7 = Right — see [com.github.alondero.nestlin.Controller.Button]), NOT the FM2
 * on-disk order. [Fm2Format] translates to/from FM2's reversed `RLDUTSBA` column layout.
 *
 * [commands] is the FM2 command field: bit 0 = soft reset, bit 1 = hard reset (power cycle).
 * Defaults to 0 (no console command this frame).
 */
data class MovieInput(
    val controller1: Int,
    val controller2: Int = 0,
    val commands: Int = 0,
)
