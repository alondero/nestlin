package com.github.alondero.nestlin

import java.io.DataInput
import java.io.DataOutput

/**
 * Pure $4016/$4017 shift-register + strobe hardware emulation (issue #191).
 *
 * Knows NOTHING about keyboards, movies, gamepads, or even "what the current button
 * state is" — every read takes the current bitmap as a parameter, and the
 * [onButtonsChanged] hook lets the caller push live updates through. This keeps
 * the hardware unit-testable without instantiating an input pipeline.
 *
 * Lifecycle:
 *  - [writeStrobe] handles the $4016 strobe bit. When the bit goes (or stays)
 *    high, the shift register is reloaded from [currentButtons] — modelling the
 *    real hardware's "transparent latch" while strobe is high.
 *  - [read] consumes one bit: while strobe is high, repeatedly returns bit 0
 *    (the A button, via [currentButtons]); while strobe is low, returns the LSB
 *    of the shift register and shifts it right with 1s shifted in for subsequent
 *    reads.
 *  - [peek] is the side-effect-free read used by the Memory Editor (issue #168):
 *    it computes the same next bit [read] would return but leaves the shift
 *    register untouched.
 *  - [onButtonsChanged] is called by the Controller after every button change;
 *    if strobe is currently high, the shift register mirrors the new value.
 *
 * Open-bus convention: bits 5-7 of the returned byte are masked to `0x40` (the
 * Nestopia convention; the 6502 data bus floats high on un-driven lines).
 */
class StrobeRegister {
    private var shiftRegister: Int = 0
    private var strobe: Boolean = false

    /**
     * Write to $4016 (the strobe register). The strobe bit is the LSB of [value].
     *
     * When the strobe bit goes high (or is held high), the shift register is
     * reloaded from [currentButtons]. This matches the real hardware's
     * "transparent latch" behaviour: while strobe is high, the wire between the
     * button latches and the shift register is directly connected, so every
     * CPU cycle refreshes the register. The caller passes the current bitmap
     * so this module stays decoupled from the input pipeline.
     *
     * Defense in depth: [currentButtons] is masked to 8 bits before mirroring
     * into the shift register so a stray wider Int (e.g. a future test or
     * feature that doesn't pre-mask) cannot leak bits 8+ through subsequent
     * reads. The Controller's `liveButtons` is already 8-bit, so this is a
     * belt-and-braces guard for direct callers of [StrobeRegister].
     */
    fun writeStrobe(value: Byte, currentButtons: Int) {
        val newStrobe = (value.toUnsignedInt() and 1) == 1
        if (newStrobe) shiftRegister = currentButtons and 0xFF
        strobe = newStrobe
    }

    /**
     * Read the next bit from $4016 (or $4017). Returns the data bit OR'd with the
     * open-bus 0x40 mask. Side effect: while strobe is low, advances the shift
     * register by one (LSB out, 1 in at MSB) so subsequent reads see the next bit.
     */
    fun read(currentButtons: Int): Byte {
        val data = if (strobe) {
            // While strobe is high, the shift register is continuously reloaded
            // from the current button state — so bit 0 (A) is always returned.
            currentButtons and Controller.Button.A.mask
        } else {
            val bit = shiftRegister and 1
            shiftRegister = (shiftRegister shr 1) or 0x80
            bit
        }
        return (OPEN_BUS_MASK or data).toByte()
    }

    /**
     * Side-effect-free read: returns the same byte [read] would return NOW without
     * advancing the shift register. Used by the Memory Editor (issue #168) so a
     * debug viewer polling $4016 cannot desync the game's controller reads.
     */
    fun peek(currentButtons: Int): Byte {
        val data = if (strobe) {
            currentButtons and Controller.Button.A.mask
        } else {
            shiftRegister and 1
        }
        return (OPEN_BUS_MASK or data).toByte()
    }

    /**
     * Notify the shift register that [currentButtons] has changed. If strobe is
     * currently HIGH (the wire is "transparent"), mirror the change into the
     * shift register so the very next $4016 read returns the just-updated bit.
     * If strobe is LOW, the shift register is latched and this call is a no-op.
     */
    fun onButtonsChanged(currentButtons: Int) {
        if (strobe) shiftRegister = currentButtons
    }

    fun saveState(out: DataOutput) {
        out.writeInt(shiftRegister)
        out.writeBoolean(strobe)
    }

    fun loadState(input: DataInput) {
        shiftRegister = input.readInt()
        strobe = input.readBoolean()
    }

    internal companion object {
        /**
         * Open-bus mask returned in bits 5-7 of every $4016/$4017 read. Internal
         * (not private) so [com.github.alondero.nestlin.input.InputDevice] stubs
         * that return the open-bus-only byte can reuse the same value rather than
         * duplicating the literal.
         */
        const val OPEN_BUS_MASK = 0x40
    }
}
