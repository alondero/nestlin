package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin

/**
 * Accumulates a [Movie] by snapshotting controller state one row per frame.
 *
 * Intended driving loop (headless / scripted):
 *
 *     for (frame in 0 until frameCount) {
 *         applyInputForFrame(frame)        // set the controllers however the run dictates
 *         nestlin.runOneFrame()
 *         recorder.captureFrame(nestlin)
 *     }
 *
 * [captureFrame] reads the pad bitmaps *after* the frame ran. Headlessly — where nothing mutates the
 * pad mid-frame — that equals the input in effect during the frame, so row N == frame N. That
 * symmetry is exactly what [MoviePlayer] relies on (it applies row N *before* frame N) to reproduce
 * the run byte-for-byte.
 *
 * Real-time UI recording, where the player can change input mid-frame, is a later slice: it must
 * freeze the pad to a per-frame snapshot so the captured row matches what the game actually polled.
 */
class MovieRecorder(
    private val romFilename: String,
    private val romChecksum: String,
    private val palFlag: Boolean = false,
) {
    private val inputs = mutableListOf<MovieInput>()

    val frameCount: Int get() = inputs.size

    fun captureFrame(nestlin: Nestlin) {
        inputs.add(
            MovieInput(
                controller1 = nestlin.getController1().buttons,
                controller2 = nestlin.getController2().buttons,
            )
        )
    }

    fun toMovie(): Movie =
        Movie(romFilename = romFilename, romChecksum = romChecksum, palFlag = palFlag, inputs = inputs.toList())
}
