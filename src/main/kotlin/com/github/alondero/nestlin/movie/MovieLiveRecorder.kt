package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin

/**
 * Real-time movie recorder. Installs a frame-end latch hook on the PPU so that
 *
 *  1. The current `controller.buttons` (frozen for the frame that just ended) is captured as
 *     the next row of the movie.
 *  2. `controller.pendingButtons` is committed to `controller.buttons` so the game sees the
 *     user's latest keyboard state for the upcoming frame.
 *
 * Headless recording is a special case of this: when nothing is writing to `pendingButtons`
 * mid-frame, the captured row equals the latched value, so the recorder degenerates to the
 * existing `MovieRecorder.captureFrame` behaviour. The split is intentional — the headless
 * recorder stays in the movie package as a thin convenience over the file format, while the
 * live recorder owns the latch hook.
 *
 * Usage:
 *
 *     val recorder = MovieLiveRecorder(nestlin, romFilename, romChecksum)
 *     recorder.start()
 *     // ... run some frames ...
 *     val movie = recorder.stopAndSnapshot()  // detach hook + return the captured Movie
 *
 * The hook is detached by [stopAndSnapshot] (or [cancel]); leaving it attached after the
 * recorder is "done" would leak captures into any later activity.
 */
class MovieLiveRecorder(
    private val nestlin: Nestlin,
    private val romFilename: String,
    private val romChecksum: String,
    private val palFlag: Boolean = false,
) {
    private val inputs = mutableListOf<MovieInput>()

    val frameCount: Int get() = inputs.size

    private val latchHook: () -> Unit = {
        // 1. Capture the just-finished frame's latched pad. This is what the game polled
        //    during the frame; the value is held constant by the latch model, so it matches
        //    the recorded row.
        inputs.add(
            MovieInput(
                controller1 = nestlin.getController1().buttons,
                controller2 = nestlin.getController2().buttons,
            )
        )
        // 2. Set up the next frame's input by committing the keyboard buffer. Done LAST
        //    so the capture above sees the old (frozen) buttons value, not the new one.
        nestlin.getController1().commitPendingToButtons()
        nestlin.getController2().commitPendingToButtons()
    }

    /**
     * Attach the frame-end latch hook. The first row is captured for the frame that is
     * CURRENTLY running (whose end will fire the hook on the next frame boundary), so
     * `start()` does NOT itself capture a row.
     */
    fun start() {
        nestlin.ppu.addFrameCompletionListener(latchHook)
    }

    /**
     * Detach the frame-end latch hook and return the captured [Movie]. After this returns
     * the recorder holds no resources; calling it twice on the same instance is safe (the
     * second call returns a snapshot of the same `inputs` list).
     */
    fun stopAndSnapshot(): Movie {
        nestlin.ppu.removeFrameCompletionListener(latchHook)
        return Movie(
            romFilename = romFilename,
            romChecksum = romChecksum,
            palFlag = palFlag,
            inputs = inputs.toList(),
        )
    }

    /**
     * Detach the frame-end latch hook and DISCARD the captured rows. Use this when the
     * user cancels a recording (Esc on the dialog, ROM reload, etc.) and doesn't want
     * the partial movie saved.
     */
    fun cancel() {
        nestlin.ppu.removeFrameCompletionListener(latchHook)
        inputs.clear()
    }
}
