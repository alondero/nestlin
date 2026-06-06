package com.github.alondero.nestlin.movie

/**
 * State of the real-time movie session as seen by the UI. Exactly one of [RECORDING] or
 * [PLAYING] is active at any given time; [NONE] is the default. The state controls how
 * the keyboard handler routes input and which latch hook (if any) is installed on the
 * PPU's frame-completion listener list.
 */
enum class MovieState {
    /** No movie session. Keyboard input goes directly to controller.buttons. */
    NONE,

    /** A movie is being recorded. Keyboard writes go to controller.pendingButtons; the
     *  frame-end latch commits pending -> buttons and captures the previous value. */
    RECORDING,

    /** A movie is being played back. Keyboard input is dropped; the frame-end latch
     *  writes the next movie row to controller.buttons. */
    PLAYING,
}
