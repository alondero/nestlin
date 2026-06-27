package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * [StrobeRegister] unit tests (issue #191). The whole point of the deepening is
 * that the $4016/$4017 hardware can be exercised WITHOUT instantiating an input
 * pipeline — every read takes the current button bitmap as a parameter, and
 * [StrobeRegister.onButtonsChanged] is the only write side effect that needs to
 * be driven explicitly. No keyboard handler, no MovieLiveRecorder, no Nestlin.
 *
 * The tests pin the observable contract that the existing [Controller] tests
 * (ControllerPeekTest, MovieLiveRecorderTest) depend on.
 */
class StrobeRegisterTest {

    @Test
    fun `strobe high reloads the shift register from current buttons on write`() {
        val reg = StrobeRegister()

        // Press B only, then strobe high. The shift register must reflect B.
        reg.writeStrobe(1, currentButtons = Controller.Button.B.mask)

        // While strobe is held high, reads keep returning A (bit 0 = 0 because no A pressed).
        // Then drop strobe: reads return the latched B then shift in 1s.
        assertThat(reg.read(currentButtons = Controller.Button.B.mask),
            equalTo(0x40.toByte()))  // A bit = 0, open-bus OR'd
        reg.writeStrobe(0, currentButtons = Controller.Button.B.mask)

        // First post-strobe read returns LSB of latched = bit 0 of B = 0.
        assertThat(reg.read(0), equalTo(0x40.toByte()))
        // Second read returns bit 1 (B) = 1.
        assertThat(reg.read(0), equalTo(0x41.toByte()))
    }

    @Test
    fun `strobe low does not reload the shift register`() {
        val reg = StrobeRegister()
        // Initial state: nothing pressed. Latch a zero shift register.
        reg.writeStrobe(1, currentButtons = 0)
        reg.writeStrobe(0, currentButtons = 0)

        // Strobe is now low; writeStrobe(0) again with a non-zero buttons should NOT
        // touch the shift register. Reads must continue to see the originally-latched 0.
        reg.writeStrobe(0, currentButtons = 0xFF)
        // All eight bits should be 0 (open-bus 0x40 only).
        repeat(8) {
            assertThat(reg.read(0), equalTo(0x40.toByte()))
        }
    }

    @Test
    fun `holding strobe high reloads the shift register on every write`() {
        // Per the original Controller.write(): if newStrobe is true, reload EVERY
        // write — not just on the rising edge. Models the "transparent latch"
        // behaviour where the wire stays connected while strobe is high.
        val reg = StrobeRegister()

        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(1, currentButtons = Controller.Button.B.mask)
        reg.writeStrobe(1, currentButtons = Controller.Button.START.mask)

        reg.writeStrobe(0, currentButtons = 0)  // latch

        // Reads must reflect the LAST high-strobe write (START), not the first (A).
        // Bit layout: A=0, B=1, SEL=2, START=3, UP=4, DOWN=5, LEFT=6, RIGHT=7.
        assertThat(reg.read(0), equalTo(0x40.toByte()))  // bit 0 = A = 0
        assertThat(reg.read(0), equalTo(0x40.toByte()))  // bit 1 = B = 0
        assertThat(reg.read(0), equalTo(0x40.toByte()))  // bit 2 = SELECT = 0
        assertThat(reg.read(0), equalTo(0x41.toByte()))  // bit 3 = START = 1
    }

    @Test
    fun `read returns A bit while strobe is high even if other buttons are pressed`() {
        val reg = StrobeRegister()
        // Press EVERY button except A.
        val allButtonsExceptA = (0xFF and Controller.Button.A.mask.inv())
        reg.writeStrobe(1, currentButtons = allButtonsExceptA)

        // Every read while strobe is high returns the A bit (bit 0 = 0).
        repeat(20) {
            assertThat(reg.read(currentButtons = allButtonsExceptA),
                equalTo(0x40.toByte()))
        }
    }

    @Test
    fun `read shifts the register right with 1s after the eight bits are consumed`() {
        val reg = StrobeRegister()
        // Latch A=1 only.
        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(0, currentButtons = 0)

        // Bit 0 (A) = 1.
        assertThat(reg.read(0), equalTo(0x41.toByte()))
        // Bits 1-7 = 0 (no other buttons pressed).
        repeat(7) {
            assertThat(reg.read(0), equalTo(0x40.toByte()))
        }
        // After 8 reads, the shift register is all 1s (shifted in). All reads = 1.
        repeat(5) {
            assertThat(reg.read(0), equalTo(0x41.toByte()))
        }
    }

    @Test
    fun `open-bus mask 0x40 is OR'd into every read`() {
        val reg = StrobeRegister()
        // Latch all 1s into the shift register.
        reg.writeStrobe(1, currentButtons = 0xFF)
        reg.writeStrobe(0, currentButtons = 0)

        // Reads 1-8 must all be 0x41: data bit = 1 (every button pressed) AND'd
        // with 0x40 open-bus mask. Critically, the result must NOT include bit 6
        // (= 0x40) plus other high bits — i.e. no upper bits leak from currentButtons
        // while strobe is low (only the shift register is in play).
        repeat(8) {
            assertThat(reg.read(currentButtons = 0xFF), equalTo(0x41.toByte()))
        }
    }

    @Test
    fun `peek returns the next data bit without shifting`() {
        val reg = StrobeRegister()
        // Latch A only. Holding strobe high twice with different values would
        // overwrite (per the transparent-latch rule), so strobe goes high ONCE.
        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(0, currentButtons = 0)

        // Peek at bit 0 = A = 1, repeatedly. The register must not advance.
        repeat(3) {
            assertThat(reg.peek(currentButtons = 0), equalTo(0x41.toByte()))
        }
        // A real read consumes bit 0 (A = 1) — returns 0x41, NOT 0x40.
        assertThat(reg.read(0), equalTo(0x41.toByte()))
        // After the read the shift register is 0x80 (one shift, 1s filled in from MSB).
        // Peek at the new LSB (originally bit 1 = B) = 0 → 0x40. Real read agrees.
        assertThat(reg.peek(0), equalTo(0x40.toByte()))
        assertThat(reg.read(0), equalTo(0x40.toByte()))
    }

    @Test
    fun `peek while strobe is high reads currentButtons without shifting`() {
        val reg = StrobeRegister()
        reg.writeStrobe(1, currentButtons = 0)

        // Peek must keep reporting the current A bit (0) regardless of how many
        // times it's called — strobe-high mode is "always read live".
        repeat(5) {
            assertThat(reg.peek(currentButtons = 0), equalTo(0x40.toByte()))
        }
        // Now press A; peek (without a real read) must reflect the change.
        assertThat(reg.peek(currentButtons = Controller.Button.A.mask),
            equalTo(0x41.toByte()))
    }

    @Test
    fun `onButtonsChanged while strobe high mirrors into the shift register`() {
        val reg = StrobeRegister()
        reg.writeStrobe(1, currentButtons = 0)

        // Press A through the callback — shift register must mirror immediately.
        reg.onButtonsChanged(currentButtons = Controller.Button.A.mask)

        reg.writeStrobe(0, currentButtons = 0)

        // First read post-strobe = bit 0 (A) = 1.
        assertThat(reg.read(0), equalTo(0x41.toByte()))
    }

    @Test
    fun `onButtonsChanged while strobe low is a no-op`() {
        val reg = StrobeRegister()
        // Latch A.
        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(0, currentButtons = 0)

        // Now press B. Strobe is low — shift register must NOT mirror.
        reg.onButtonsChanged(currentButtons = Controller.Button.A.mask or Controller.Button.B.mask)

        // First read = A = 1, second = SELECT = 0, NOT B.
        assertThat(reg.read(0), equalTo(0x41.toByte()))
        assertThat(reg.read(0), equalTo(0x40.toByte()))
        assertThat(reg.read(0), equalTo(0x40.toByte()))
        // Fourth = START = 0 still; B (bit 1) was never latched.
        assertThat(reg.read(0), equalTo(0x40.toByte()))
    }

    @Test
    fun `saveState and loadState round-trip the shift register and strobe`() {
        val original = StrobeRegister()
        original.writeStrobe(1, currentButtons = 0xA5.toInt())
        original.writeStrobe(0, currentButtons = 0xA5.toInt())
        // Strobe low, shift register = 0xA5.

        val out = ByteArrayOutputStream()
        original.saveState(DataOutputStream(out))
        val bytes = out.toByteArray()

        // 4 bytes (shiftRegister Int) + 1 byte (strobe Boolean) = 5 bytes.
        assertThat(bytes.size, equalTo(5))

        val restored = StrobeRegister()
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        // Strobe low, reads return the latched 0xA5 bit by bit.
        assertThat(restored.read(0), equalTo(0x41.toByte()))  // bit 0 of 0xA5 = 1
        assertThat(restored.read(0), equalTo(0x40.toByte()))  // bit 1 = 0
        assertThat(restored.read(0), equalTo(0x41.toByte()))  // bit 2 = 1
    }

    @Test
    fun `strobe state survives the round trip independently`() {
        val reg = StrobeRegister()
        // Press A then latch while strobe is low. Then write strobe high so the
        // round-trip must preserve BOTH the shift register AND the strobe flag.
        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(0, currentButtons = Controller.Button.A.mask)
        reg.writeStrobe(1, currentButtons = Controller.Button.A.mask)  // strobe high again

        val out = ByteArrayOutputStream()
        reg.saveState(DataOutputStream(out))

        val restored = StrobeRegister()
        restored.loadState(DataInputStream(ByteArrayInputStream(out.toByteArray())))

        // Strobe must still be high: reads return the live A bit.
        assertThat(restored.read(currentButtons = Controller.Button.A.mask),
            equalTo(0x41.toByte()))
    }
}
