package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin

/**
 * Real-time movie player. Installs a frame-end latch hook on the PPU that writes the NEXT
 * movie row to `controller.buttons` (via [com.github.alondero.nestlin.Controller.setButtonsLatched])
 * so the game sees the latched value for the upcoming frame.
 *
 * Critically different from the headless [MoviePlayer]: real-time playback keeps throttling
 * ON, so the game runs at 60 fps wall-clock. The headless player advances one row per
 * `runOneFrame()` call (no wall clock), but the live player relies on the natural frame-end
 * signal from the PPU to advance.
 *
 * End-of-movie behaviour: when the player has written the LAST row's input, the NEXT frame
 * boundary triggers [isFinished] → true. The application is responsible for calling
 * [stop] and clearing the on-screen PLAY indicator. We do NOT auto-detach the hook because
 * the application owns the visual state.
 *
 * Usage:
 *
 *     val player = MovieLivePlayer(nestlin, movie)
 *     player.start()
 *     // ... poll isFinished in the render loop ...
 *     player.stop()
 */
class MovieLivePlayer(
    private val nestlin: Nestlin,
    private val movie: Movie,
) {
    /** The row index that the NEXT latch will use. Bumped to 1 immediately by [start] (after
     *  priming row 0), then advances by 1 each time the latch hook fires and writes a row. */
    private var nextRow: Int = 0

    /** Number of frames the player has driven (= number of times the latch hook has fired).
     *  Used by [isFinished]: the player is "done" only AFTER the last frame has run, not as
     *  soon as we've exhausted the row list (which would fire at start() for a 1-row movie
     *  because priming bumps nextRow to 1 = length). */
    private var framesDriven: Int = 0

    val isFinished: Boolean get() = framesDriven >= movie.length
    val currentRow: Int get() = (nextRow - 1).coerceAtLeast(0)
    /** Frames the player has driven (= number of latch fires). Exposed for the on-screen
     *  log: the "X / Y frames" message uses the same unit (frames) as [totalFrames]. */
    val framesDrivenCount: Int get() = framesDriven
    val totalFrames: Int get() = movie.length

    private val latchHook: () -> Unit = {
        // Count every fire — even the no-op one when the last frame ends — so [isFinished]
        // flips to true at the end of the last frame, not at start() time.
        framesDriven++
        if (nextRow < movie.length) {
            val row = movie.inputs[nextRow]
            nestlin.getController1().setButtonBitmap(row.controller1)
            nestlin.getController2().setButtonBitmap(row.controller2)
            // TODO: honour row.commands (soft/hard reset) once a real movie needs mid-run resets.
            nextRow++
        }
    }

    /**
     * Attach the frame-end latch hook. Row 0 of the movie is written to the controllers as
     * the latch BEFORE the first frame after start() returns — so by the time the game polls
     * during the very next frame, it sees row 0. This matches FM2 semantics: row N is the
     * input visible during frame N.
     */
    fun start() {
        nextRow = 0
        nestlin.ppu.addFrameCompletionListener(latchHook)
        // Prime the controllers with row 0 NOW so a game that polls before the first
        // frame-end still sees a defined value (mirrors what the first hook iteration
        // would do, but in the same thread as start()).
        val row0 = movie.inputs.firstOrNull()
        if (row0 != null) {
            nestlin.getController1().setButtonBitmap(row0.controller1)
            nestlin.getController2().setButtonBitmap(row0.controller2)
            nextRow = 1
        }
    }

    /**
     * Detach the frame-end latch hook. After this returns, the controllers keep whatever
     * value the last latch wrote — the application's keyboard handler is responsible for
     * restoring live input (typically by setting `controller.buttons` from the keyboard on
     * the next key event).
     */
    fun stop() {
        nestlin.ppu.removeFrameCompletionListener(latchHook)
    }
}
