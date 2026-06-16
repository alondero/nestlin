package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import javafx.scene.input.KeyCode
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [MemoryEditorWindow] (issue #168). Exercises the row
 * formatting and grid dimensions without booting the JavaFX toolkit — the live
 * Stage/ListView wiring is verified by hand and the side-effect-free read path it
 * relies on has its own dedicated tests.
 */
class MemoryEditorWindowTest {

    @Test
    fun `grid covers the full 64 KB CPU bus at 16 bytes per row`() {
        assertThat(MemoryEditorWindow.ROWS, equalTo(4096))
        assertThat(MemoryEditorWindow.ROWS * 16, equalTo(0x10000))
    }

    @Test
    fun `formatRow renders the address column and 16 hex bytes`() {
        // Peek provider returns the low byte of each address, so the row content is
        // predictable: row 0x10 covers $0100..$010F.
        val peek: (Int) -> Byte = { addr -> (addr and 0xFF).toByte() }

        val row = MemoryEditorWindow.formatRow(0x10, peek)

        assertThat(row, equalTo("0100  00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F"))
    }

    @Test
    fun `formatRow peeks exactly the 16 addresses of its row`() {
        val peeked = mutableListOf<Int>()
        val peek: (Int) -> Byte = { addr -> peeked.add(addr); 0 }

        MemoryEditorWindow.formatRow(rowIndex = 0xFFF, peek = peek) // last row: $FFF0..$FFFF

        assertThat(peeked, equalTo((0xFFF0..0xFFFF).toList()))
    }

    // ---- hexDigit key mapping (issue #170) ----------------------------------

    @Test
    fun `hexDigit maps number-row and numpad digits to their value`() {
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.DIGIT0), equalTo(0))
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.DIGIT9), equalTo(9))
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.NUMPAD0), equalTo(0))
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.NUMPAD9), equalTo(9))
    }

    @Test
    fun `hexDigit maps A-F to 10-15`() {
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.A), equalTo(0xA))
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.F), equalTo(0xF))
    }

    @Test
    fun `hexDigit returns null for non-hex keys`() {
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.G), absent())
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.LEFT), absent())
        assertThat(MemoryEditorWindow.hexDigit(KeyCode.ENTER), absent())
    }
}
