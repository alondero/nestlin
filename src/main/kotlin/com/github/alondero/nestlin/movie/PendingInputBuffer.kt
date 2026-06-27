package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller

/**
 * Keyboard buffer used during real-time movie recording (issue #191). The movie
 * package owns this concern because the buffer only has meaning while a
 * [MovieLiveRecorder] session is active — without that, it's just a redundant
 * shadow of [Controller.buttons].
 *
 * Why a buffer at all? The recorder captures `controller.buttons` at every frame
 * boundary and wants the value to be FROZEN for the duration of the frame the
 * game is currently polling. If the keyboard wrote straight to `buttons`, a
 * mid-frame key release would corrupt the captured row for the frame still in
 * flight. The latch model fixes this: keyboard writes go to this buffer; once
 * per frame, the [MovieLiveRecorder] captures `buttons` (the frame's polled
 * value) and then commits the buffer → `buttons` so the NEXT frame sees the
 * latest keyboard state.
 *
 * NOT a [java.io.Serializable] — the buffer is transient by design. A savestate
 * restore deliberately drops it and reseeds from the just-loaded `buttons`, so a
 * power-cycle or load-state mid-recording never carries stale keyboard state into
 * a fresh session.
 */
class PendingInputBuffer {

    /**
     * The accumulated keyboard state. Bits mirror [Controller.Button.mask]:
     * 0=A, 1=B, 2=SELECT, 3=START, 4=UP, 5=DOWN, 6=LEFT, 7=RIGHT. Always
     * normalised to 8 bits on write.
     */
    var value: Int = 0
        set(newValue) {
            field = newValue and 0xFF
        }

    /** Press [mask] (OR it into the buffer). */
    fun press(mask: Int) {
        value = value or mask
    }

    /** Release [mask] (clear the corresponding bit). */
    fun release(mask: Int) {
        value = value and mask.inv()
    }

    /**
     * Apply [pressed] for [mask] in one shot. Equivalent to
     * `if (pressed) press(mask) else release(mask)`. The keyboard handler calls
     * this on every key event with the current pressed/released state.
     */
    fun update(mask: Int, pressed: Boolean) {
        value = if (pressed) value or mask else value and mask.inv()
    }

    /** Clear the buffer to 0. Used by the recorder's `cancel()` to wipe a partial run. */
    fun clear() {
        value = 0
    }
}
