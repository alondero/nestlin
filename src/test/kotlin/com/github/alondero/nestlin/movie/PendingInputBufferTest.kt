package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller.Button
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [PendingInputBuffer] unit tests (issue #191). The buffer is the data structure
 * the movie recorder uses to defer keyboard writes across the frame-end latch.
 * Tests cover the four primitives (press / release / update / clear) plus the
 * normalisation rule (writes are masked to 8 bits — protects against stray
 * high-byte assignments from the keyboard handler).
 */
class PendingInputBufferTest {

    @Test
    fun `press ORs the mask into the value`() {
        val buf = PendingInputBuffer()
        buf.press(Button.A.mask)
        assertThat(buf.value, equalTo(Button.A.mask))

        buf.press(Button.B.mask)
        assertThat(buf.value, equalTo(Button.A.mask or Button.B.mask))

        // Re-pressing is idempotent (OR with a set bit leaves it set).
        buf.press(Button.A.mask)
        assertThat(buf.value, equalTo(Button.A.mask or Button.B.mask))
    }

    @Test
    fun `release clears the mask from the value`() {
        val buf = PendingInputBuffer()
        buf.value = Button.A.mask or Button.B.mask or Button.START.mask

        buf.release(Button.B.mask)
        assertThat(buf.value, equalTo(Button.A.mask or Button.START.mask))

        // Releasing an already-clear bit is a no-op.
        buf.release(Button.B.mask)
        assertThat(buf.value, equalTo(Button.A.mask or Button.START.mask))
    }

    @Test
    fun `update with pressed true is equivalent to press`() {
        val buf = PendingInputBuffer()
        buf.update(Button.A.mask, pressed = true)
        assertThat(buf.value, equalTo(Button.A.mask))

        buf.update(Button.B.mask, pressed = true)
        assertThat(buf.value, equalTo(Button.A.mask or Button.B.mask))
    }

    @Test
    fun `update with pressed false is equivalent to release`() {
        val buf = PendingInputBuffer()
        buf.value = Button.A.mask or Button.B.mask or Button.START.mask

        buf.update(Button.A.mask, pressed = false)
        assertThat(buf.value, equalTo(Button.B.mask or Button.START.mask))
    }

    @Test
    fun `update on a clear bit is a no-op when pressed false`() {
        val buf = PendingInputBuffer()
        buf.value = Button.A.mask

        buf.update(Button.B.mask, pressed = false)
        assertThat(buf.value, equalTo(Button.A.mask))
    }

    @Test
    fun `clear resets the buffer to 0`() {
        val buf = PendingInputBuffer()
        buf.value = 0xFF

        buf.clear()
        assertThat(buf.value, equalTo(0))
    }

    @Test
    fun `value setter masks writes to 8 bits`() {
        // Guards against the keyboard handler accidentally writing a wider Int
        // (e.g., a JFX KeyCode cast that produced more than 8 bits).
        val buf = PendingInputBuffer()
        buf.value = 0xFFFF
        assertThat(buf.value, equalTo(0xFF))

        buf.value = -1  // all 1s in two's-complement
        assertThat(buf.value, equalTo(0xFF))
    }

    @Test
    fun `a fresh buffer reads as 0`() {
        val buf = PendingInputBuffer()
        assertThat(buf.value, equalTo(0))
    }

    @Test
    fun `eight simultaneous presses accumulate to 0xFF`() {
        val buf = PendingInputBuffer()
        Button.entries.forEach { buf.press(it.mask) }
        assertThat(buf.value, equalTo(0xFF))
    }

    @Test
    fun `eight simultaneous releases after 0xFF return to 0`() {
        val buf = PendingInputBuffer()
        buf.value = 0xFF
        Button.entries.forEach { buf.release(it.mask) }
        assertThat(buf.value, equalTo(0))
    }
}
