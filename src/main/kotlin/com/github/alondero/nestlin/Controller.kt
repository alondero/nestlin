package com.github.alondero.nestlin

import com.github.alondero.nestlin.input.InputSource
import com.github.alondero.nestlin.movie.PendingInputBuffer
import java.io.DataInput
import java.io.DataOutput

/**
 * Thin orchestrator combining a [StrobeRegister] (the $4016/$4017 hardware
 * emulation) and an [InputSource] (the single named answer to "who feeds the
 * controller right now") (issue #191).
 *
 * History: this class previously bundled three concerns — the shift register,
 * a pending-button buffer for movie recording, and the live button bitmap —
 * into one 167-line file. The deepening in #191 split those out so the
 * hardware can be unit-tested without a keyboard attached (see
 * [StrobeRegister]), the movie recording logic lives next to its peers in
 * `movie/PendingInputBuffer.kt`, and the input-pipeline question has one
 * named answer ([inputSource]).
 *
 * Public API preserved for backward compatibility with all existing callers,
 * with two intentional refinements:
 *  - `var buttons` / `var pendingButtons` — mutable Int as before
 *  - `setButton` / `setButtonBitmap` / `commitPendingToButtons` — same semantics
 *  - `read` / `peek` / `write` — same byte-level behaviour
 *  - `saveState` / `loadState` — same 9-byte wire format (4 buttons + 5 strobe)
 *  - **`var buttons` setter now mirrors into the shift register when strobe is
 *    high** (the "transparent latch" rule). The pre-#191 implementation had
 *    this side effect inside `setButton` only; direct writes were no-ops. The
 *    new behaviour is hardware-accurate and no test regresses, but a future
 *    caller that writes to `controller.buttons` while strobe is HIGH should
 *    know the shift register gets reloaded as a side effect.
 */
class Controller {

    /**
     * Backing store for the live pad bitmap. Every read of [buttons] flows
     * through [inputSource]; every write (via [setButton], [setButtonBitmap],
     * [commitPendingToButtons], or the public setter) updates it through
     * [updateButtons] (the single point that also calls the strobe-register
     * mirror). Exposed as a lambda-captured field of [InputSource.Live] so
     * every `$4016` read sees the current value.
     */
    private var liveButtons: Int = 0

    /**
     * The single named answer to "who feeds this controller right now".
     *
     * Defaults to a [InputSource.Live] over the internal [liveButtons] cell so
     * every existing caller (which writes to [buttons] or [setButton]) sees the
     * same `$4016` read behaviour as before this refactor. [read], [peek], and
     * [write] route through this — the abstraction is load-bearing on the
     * hardware path, not a parallel observation.
     *
     * Tests can introspect this to assert which source is active without
     * reaching into privates. Future work (e.g. movie playback) can replace
     * the default with [InputSource.FixedBitmap] or [InputSource.FromPendingBuffer]
     * to live-swap the active pad.
     */
    val inputSource: InputSource = InputSource.Live { liveButtons }

    /**
     * Live button bitmap the game polls via $4016. Bits mirror [Button.mask]:
     * 0=A, 1=B, 2=SELECT, 3=START, 4=UP, 5=DOWN, 6=LEFT, 7=RIGHT. The setter
     * also pushes the new value through [StrobeRegister.onButtonsChanged] so a
     * strobe-high latch stays in sync (the "transparent latch" rule — see
     * class kdoc for the pre-#191 compatibility note).
     *
     * Mutating this directly is supported for backward compatibility (see
     * `MemoryPokeTest`) but new code should prefer [setButton] / [setButtonBitmap].
     */
    var buttons: Int
        get() = liveButtons
        set(value) {
            updateButtons(value)
        }

    /**
     * Transient keyboard buffer used during movie recording. While a
     * [com.github.alondero.nestlin.movie.MovieLiveRecorder] session is active,
     * the keyboard handler writes here and the recorder's frame-end latch
     * commits this buffer → [buttons]. Outside a recording session, this is a
     * dead cell that no one reads — but it's kept on the Controller for
     * backward compat with the original public API.
     *
     * NOT persisted in save state: a restored controller reseeds [pendingButtons]
     * from the loaded [buttons], so a power-cycle / load-state mid-recording
     * never carries stale keyboard state into a fresh session.
     */
    var pendingButtons: Int
        get() = pending.value
        set(value) {
            pending.value = value
        }

    private val pending = PendingInputBuffer()
    private val strobe = StrobeRegister()

    /**
     * Standard NES Controller Button bitmasks (internal storage matches shift
     * order). Bit 0: A. Bit 7: RIGHT.
     */
    enum class Button(val mask: Int) {
        A(0x01),
        B(0x02),
        SELECT(0x04),
        START(0x08),
        UP(0x10),
        DOWN(0x20),
        LEFT(0x40),
        RIGHT(0x80)
    }

    /**
     * Write to $4016 (the strobe register). The LSB of [value] sets the strobe
     * bit; when high, the shift register is reloaded from the current
     * [inputSource.snapshot] (transparent latch rule).
     */
    fun write(value: Byte) {
        strobe.writeStrobe(value, inputSource.snapshot())
    }

    /**
     * Read from $4016 (or $4017). Returns the next data bit OR'd with the
     * open-bus 0x40 mask. Side effect: while strobe is low, advances the shift
     * register by one (LSB out, 1 in at MSB).
     */
    fun read(): Byte = strobe.read(inputSource.snapshot())

    /**
     * Side-effect-free read of the value a `$4016`/`$4017` read would currently
     * return, WITHOUT advancing the shift register (issue #168, Memory Editor).
     *
     * The real [read] shifts the register right by one and feeds in a 1 — so
     * calling it from a debug viewer would desync the game's controller polling.
     * [peek] computes the same next bit (the A button while strobe is high,
     * else the shift register's LSB) but leaves all state untouched.
     */
    fun peek(): Byte = strobe.peek(inputSource.snapshot())

    /**
     * Serialise the controller to a 9-byte block. The format is unchanged from
     * the pre-#191 implementation so existing `.nstl` save states load cleanly:
     *   - 4 bytes: [buttons] Int
     *   - 5 bytes: [StrobeRegister] (4-byte shiftRegister + 1-byte strobe)
     *
     * [pendingButtons] is intentionally NOT serialised (it's transient — see its
     * kdoc).
     */
    fun saveState(out: DataOutput) {
        out.writeInt(liveButtons)
        strobe.saveState(out)
    }

    /**
     * Restore the controller from a 9-byte block written by [saveState]. After
     * the load, [pendingButtons] is reseeded from the just-loaded [buttons] so
     * the next frame-end latch commits a sensible value (the recorder's commit
     * is no-op when pending == buttons, but is correct for divergent states).
     */
    fun loadState(input: DataInput) {
        liveButtons = input.readInt()
        strobe.loadState(input)
        pending.value = liveButtons
    }

    /**
     * Press or release a single button. While strobe is currently high, the new
     * value is also pushed into the shift register so the next `$4016` read
     * reflects the change immediately.
     */
    fun setButton(button: Button, pressed: Boolean) {
        val mask = button.mask
        updateButtons(if (pressed) liveButtons or mask else liveButtons and mask.inv())
    }

    /**
     * Overwrite the entire button bitmap in one shot (bit order: A=0 … Right=7).
     * Used by the movie replayer to latch a whole frame's input atomically,
     * rather than issuing eight [setButton] calls. Honours the transparent-latch
     * rule like [setButton].
     */
    fun setButtonBitmap(value: Int) {
        updateButtons(value)
    }

    /**
     * Commit the keyboard buffer ([pendingButtons]) to the live game-visible
     * [buttons]. Called by the real-time movie recorder's frame-end latch hook
     * — exactly once per frame, AFTER the recorder has captured the current
     * [buttons] for the row that just finished. The game will see this committed
     * value as the input for the upcoming frame.
     *
     * Honours the transparent-latch rule: if strobe is high, the shift register
     * mirrors the new value so the next `$4016` read returns the just-committed
     * bit.
     */
    fun commitPendingToButtons() {
        updateButtons(pending.value)
    }

    /**
     * Single mutation point: store [newValue] as the live pad bitmap (masked
     * to 8 bits for defense in depth) and push the change to the shift register
     * so strobe-high polling reflects it immediately. Every public write
     * path funnels through here so the strobe mirror can never be forgotten.
     */
    private fun updateButtons(newValue: Int) {
        liveButtons = newValue and 0xFF
        strobe.onButtonsChanged(liveButtons)
    }
}
