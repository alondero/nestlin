package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.movie.PendingInputBuffer
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [InputSource] unit tests (issue #191). The sealed interface is the single named
 * answer to "who feeds the controller", so its variants are individually tiny —
 * but their composition rules (Composite OR-aggregation, Live delegation,
 * FromPendingBuffer's read-through-the-buffer) are the contract the orchestrating
 * [com.github.alondero.nestlin.Controller.inputSource] depends on.
 */
class InputSourceTest {

    @Test
    fun `None returns 0 regardless of how many times snapshot is called`() {
        val source = InputSource.None
        repeat(5) {
            assertThat(source.snapshot(), equalTo(0))
        }
    }

    @Test
    fun `Live delegates to its provider on every snapshot`() {
        var backingValue = 0
        val source = InputSource.Live { backingValue }

        // Initial snapshot reads the current value.
        assertThat(source.snapshot(), equalTo(0))

        // Mutating the backing field is visible on the next snapshot — no caching.
        backingValue = Button.A.mask
        assertThat(source.snapshot(), equalTo(Button.A.mask))

        backingValue = Button.A.mask or Button.B.mask
        assertThat(source.snapshot(), equalTo(Button.A.mask or Button.B.mask))
    }

    @Test
    fun `FromPendingBuffer reflects every buffer mutation immediately`() {
        val buffer = PendingInputBuffer()
        val source = InputSource.FromPendingBuffer(buffer)

        assertThat(source.snapshot(), equalTo(0))

        buffer.press(Button.A.mask)
        assertThat(source.snapshot(), equalTo(Button.A.mask))

        buffer.update(Button.SELECT.mask, pressed = true)
        assertThat(source.snapshot(), equalTo(Button.A.mask or Button.SELECT.mask))

        buffer.release(Button.A.mask)
        assertThat(source.snapshot(), equalTo(Button.SELECT.mask))

        buffer.clear()
        assertThat(source.snapshot(), equalTo(0))
    }

    @Test
    fun `FixedBitmap yields the current provider value without caching`() {
        var row = Button.A.mask
        val source = InputSource.FixedBitmap { row }

        assertThat(source.snapshot(), equalTo(Button.A.mask))

        row = Button.RIGHT.mask or Button.START.mask
        assertThat(source.snapshot(), equalTo(Button.RIGHT.mask or Button.START.mask))
    }

    @Test
    fun `Composite OR-aggregates all sources`() {
        val source = InputSource.Composite(listOf(
            InputSource.FixedBitmap { Button.A.mask },
            InputSource.FixedBitmap { Button.B.mask },
            InputSource.FixedBitmap { Button.RIGHT.mask },
        ))

        // A | B | RIGHT — every pressed bit set, no other bits.
        assertThat(source.snapshot(),
            equalTo(Button.A.mask or Button.B.mask or Button.RIGHT.mask))
    }

    @Test
    fun `Composite with overlapping bits yields the OR (commutative and associative)`() {
        val source1 = InputSource.Composite(listOf(
            InputSource.FixedBitmap { Button.A.mask or Button.START.mask },
            InputSource.FixedBitmap { Button.B.mask },
        ))
        val source2 = InputSource.Composite(listOf(
            InputSource.FixedBitmap { Button.A.mask or Button.START.mask },
            InputSource.FixedBitmap { Button.B.mask },
        ))
        // Two identical composites must produce identical snapshots.
        assertThat(source1.snapshot(), equalTo(source2.snapshot()))
    }

    @Test
    fun `Composite with an empty source list returns 0`() {
        val source = InputSource.Composite(emptyList())
        assertThat(source.snapshot(), equalTo(0))
    }

    @Test
    fun `Composite mixed with None treats None as a transparent zero`() {
        val source = InputSource.Composite(listOf(
            InputSource.None,
            InputSource.FixedBitmap { Button.A.mask },
            InputSource.None,
        ))
        assertThat(source.snapshot(), equalTo(Button.A.mask))
    }

    @Test
    fun `Composite nesting flattens through OR`() {
        val inner = InputSource.Composite(listOf(
            InputSource.FixedBitmap { Button.A.mask },
            InputSource.FixedBitmap { Button.B.mask },
        ))
        val outer = InputSource.Composite(listOf(
            inner,
            InputSource.FixedBitmap { Button.START.mask },
        ))
        assertThat(outer.snapshot(),
            equalTo(Button.A.mask or Button.B.mask or Button.START.mask))
    }

    @Test
    fun `Live provider can capture mutable state via a lambda closure`() {
        // Pattern used by the Controller's `InputSource.Live { liveButtons }`
        // indirection. Asserts the lambda-capture pattern works.
        var backing = 0
        val source = InputSource.Live { backing }

        val setButton = { mask: Int, pressed: Boolean ->
            backing = if (pressed) backing or mask else backing and mask.inv()
        }

        setButton(Button.A.mask, true)
        assertThat(source.snapshot(), equalTo(Button.A.mask))

        setButton(Button.A.mask, false)
        setButton(Button.RIGHT.mask, true)
        assertThat(source.snapshot(), equalTo(Button.RIGHT.mask))
    }
}
