package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.present
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [HexEditState] (issue #170) — the Memory Editor's
 * click-to-select / type-two-nibbles / arrow-navigate state machine, extracted
 * from the JavaFX window so it can be tested without the toolkit.
 *
 * The machine owns no memory: a completed two-nibble edit is returned as a
 * [HexEditState.Poke] for the caller to apply via `Memory.poke`. Everything the
 * UI needs to render (which cell is selected, whether a half-typed nibble is
 * pending) is exposed as read-only state.
 */
class HexEditStateTest {

    @Test
    fun `starts with nothing selected and no pending nibble`() {
        val state = HexEditState()
        assertThat(state.selectedAddress, absent())
        assertThat(state.pendingNibble, absent())
    }

    @Test
    fun `select sets the address and clears any pending nibble`() {
        val state = HexEditState()
        state.select(0x0076)
        state.typeHexDigit(0xA) // half-typed
        assertThat(state.pendingNibble, equalTo(0xA))

        state.select(0x0100)
        assertThat(state.selectedAddress, equalTo(0x0100))
        assertThat(state.pendingNibble, absent()) // re-selecting abandons the partial edit
    }

    @Test
    fun `typing two hex digits returns a poke combining high then low nibble`() {
        val state = HexEditState()
        state.select(0x0076)

        val firstResult = state.typeHexDigit(0xA)
        // First nibble is buffered, not committed — no poke yet, partial shown.
        assertThat(firstResult, absent())
        assertThat(state.pendingNibble, equalTo(0xA))

        val secondResult = state.typeHexDigit(0x5)
        // Second nibble completes the byte 0xA5 at the selected address.
        assertThat(secondResult, present(equalTo(HexEditState.Poke(0x0076, 0xA5))))
        // Pending clears so the next two digits start a fresh byte.
        assertThat(state.pendingNibble, absent())
    }

    @Test
    fun `a third digit starts a new byte's high nibble`() {
        val state = HexEditState()
        state.select(0x0076)
        state.typeHexDigit(0x1)
        assertThat(state.typeHexDigit(0x2), present(equalTo(HexEditState.Poke(0x0076, 0x12))))

        // Third keystroke begins a new pending nibble, no poke.
        assertThat(state.typeHexDigit(0xF), absent())
        assertThat(state.pendingNibble, equalTo(0xF))
    }

    @Test
    fun `typing with nothing selected is a no-op`() {
        val state = HexEditState()
        assertThat(state.typeHexDigit(0xA), absent())
        assertThat(state.pendingNibble, absent())
    }

    @Test
    fun `arrow navigation moves by columns and rows and clamps to the bus`() {
        val state = HexEditState()
        state.select(0x0010)

        state.move(dCol = 1, dRow = 0)
        assertThat(state.selectedAddress, equalTo(0x0011)) // right

        state.move(dCol = -1, dRow = 0)
        assertThat(state.selectedAddress, equalTo(0x0010)) // left

        state.move(dCol = 0, dRow = 1)
        assertThat(state.selectedAddress, equalTo(0x0020)) // down one row = +16

        state.move(dCol = 0, dRow = -1)
        assertThat(state.selectedAddress, equalTo(0x0010)) // up one row = -16
    }

    @Test
    fun `move clamps at the bus boundaries instead of wrapping`() {
        val state = HexEditState()

        state.select(0x0000)
        state.move(dCol = -1, dRow = 0) // would go to -1
        assertThat(state.selectedAddress, equalTo(0x0000)) // clamped, no wrap

        state.select(0xFFFF)
        state.move(dCol = 1, dRow = 0) // would go to 0x10000
        assertThat(state.selectedAddress, equalTo(0xFFFF)) // clamped, no wrap
    }

    @Test
    fun `move abandons a pending nibble`() {
        val state = HexEditState()
        state.select(0x0010)
        state.typeHexDigit(0xA)
        assertThat(state.pendingNibble, equalTo(0xA))

        state.move(dCol = 1, dRow = 0)
        assertThat(state.pendingNibble, absent()) // navigating away discards the half-typed byte
    }

    @Test
    fun `move with nothing selected is a no-op`() {
        val state = HexEditState()
        state.move(dCol = 1, dRow = 0)
        assertThat(state.selectedAddress, absent())
    }

    @Test
    fun `clearSelection resets selection and pending`() {
        val state = HexEditState()
        state.select(0x0010)
        state.typeHexDigit(0xA)

        state.clearSelection()
        assertThat(state.selectedAddress, absent())
        assertThat(state.pendingNibble, absent())
    }
}
