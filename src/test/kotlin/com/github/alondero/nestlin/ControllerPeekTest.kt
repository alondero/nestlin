package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [Controller.peek] — side-effect-free read of $4016/$4017 for the Memory Editor
 * (issue #168). The real [Controller.read] advances the serial shift register;
 * peek must return the same next bit without shifting, so a debug viewer polling
 * the controller port can never desync the game's controller reads.
 */
class ControllerPeekTest {

    @Test
    fun `peek returns the next data bit without advancing the shift register`() {
        val controller = Controller()
        // Press A only, then strobe high->low to latch the button state.
        controller.setButton(Controller.Button.A, true)
        controller.write(1) // strobe high (reloads shift register from buttons)
        controller.write(0) // strobe low (latches)

        // peek repeatedly must keep returning bit 0 = A pressed = 1 (with open-bus 0x40).
        assertThat(controller.peek(), equalTo(0x41.toByte()))
        assertThat(controller.peek(), equalTo(0x41.toByte()))

        // The real read consumes that bit; the NEXT bit (B, not pressed) is 0.
        assertThat(controller.read(), equalTo(0x41.toByte()))
        assertThat(controller.read(), equalTo(0x40.toByte()))
    }

    @Test
    fun `peek does not disturb an in-progress read sequence`() {
        val controller = Controller()
        controller.setButton(Controller.Button.B, true) // bit 1 set
        controller.write(1)
        controller.write(0)

        // Consume bit 0 (A = 0).
        assertThat(controller.read(), equalTo(0x40.toByte()))
        // Peeking now must report the upcoming bit 1 (B = 1) without consuming it.
        assertThat(controller.peek(), equalTo(0x41.toByte()))
        // The real read still sees B = 1, proving peek left the shift register alone.
        assertThat(controller.read(), equalTo(0x41.toByte()))
    }
}
