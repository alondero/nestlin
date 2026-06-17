package com.github.alondero.nestlin.ui

/**
 * The Memory Editor's click-to-edit state machine (issue #170).
 *
 * Pure logic, no JavaFX: the window wires mouse clicks, hex keystrokes, and
 * arrow keys to the methods here, and renders from the read-only state. Keeping
 * it toolkit-free means the editing rules (how two nibbles combine, what arrow
 * keys do, when a partial edit is abandoned) are unit-testable without booting
 * the FX thread — the same split the project uses for [MemorySnapshot].
 *
 * The machine deliberately owns no memory. A completed two-nibble edit is
 * returned as a [Poke] for the caller to apply via `Memory.poke`; the editor
 * stays decoupled from the bus and the blacklist lives in one place.
 *
 * Editing model (PRD user stories 9–11):
 *  - **Select** a cell by clicking ([select]); a selected cell can be edited.
 *  - **Type** a hex digit ([typeHexDigit]). The first digit is buffered as
 *    [pendingNibble] (shown as a partial edit like `A_`); the second completes
 *    the byte and yields a [Poke].
 *  - **Navigate** with arrow keys ([move]); 16 columns per row. Moving abandons
 *    any half-typed nibble — pressing an arrow is a navigation action, not a
 *    commit.
 */
class HexEditState {

    /** The CPU address of the currently selected cell, or null if none. */
    var selectedAddress: Int? = null
        private set

    /**
     * The high nibble of a half-typed byte (0..15), or null when no edit is in
     * progress. The renderer shows this as a partial edit (`A_`) so the user
     * gets feedback that the first keystroke registered (PRD user story 10).
     */
    var pendingNibble: Int? = null
        private set

    /** Select [address] for editing, abandoning any in-progress partial edit. */
    fun select(address: Int) {
        require(address in 0..0xFFFF) { "address must be a 16-bit CPU address, got $address" }
        selectedAddress = address
        pendingNibble = null
    }

    /** Clear the selection entirely (e.g. when the editor loses focus). */
    fun clearSelection() {
        selectedAddress = null
        pendingNibble = null
    }

    /**
     * Move the selection by [dCol] columns and [dRow] rows (16 bytes per row),
     * clamping to the `$0000-$FFFF` bus rather than wrapping. No-op when nothing
     * is selected. Always abandons a pending nibble — arrow keys navigate, they
     * don't commit a half-typed byte.
     */
    fun move(dCol: Int, dRow: Int) {
        val current = selectedAddress ?: return
        pendingNibble = null
        val target = current + dCol + dRow * 16
        if (target in 0..0xFFFF) {
            selectedAddress = target
        }
        // Out-of-range target: keep the current selection (clamp, no wrap).
    }

    /**
     * Feed one hex digit ([nibble], 0..15) to the selected cell.
     *
     * Returns null and buffers [nibble] as [pendingNibble] when it's the first
     * digit of a byte; returns the completed [Poke] (and clears the buffer) when
     * it's the second. No-op returning null when nothing is selected.
     */
    fun typeHexDigit(nibble: Int): Poke? {
        require(nibble in 0..15) { "nibble must be 0..15, got $nibble" }
        val address = selectedAddress ?: return null
        val high = pendingNibble
        return if (high == null) {
            pendingNibble = nibble
            null
        } else {
            pendingNibble = null
            Poke(address, (high shl 4) or nibble)
        }
    }

    /**
     * A completed two-nibble edit: write [value] (0..255) to [address]. The
     * caller applies it through `Memory.poke`, which owns the I/O blacklist.
     */
    data class Poke(val address: Int, val value: Int)
}
