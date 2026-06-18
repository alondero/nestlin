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
    fun `formatRow renders the address column, 16 hex bytes, and the ASCII column`() {
        // Peek provider returns the low byte of each address, so the row content is
        // predictable: row 0x10 covers $0100..$010F. All bytes are < 0x20 (control
        // chars), so the trailing ASCII column is 16 dots. The ASCII column is
        // separated from the hex bytes by a single space — see the helper doc for
        // why one space is enough (the hex column is 47 chars wide and the ASCII
        // column is fixed-width at 16 chars, so a wider separator would push the
        // cell past the scene width).
        val peek: (Int) -> Byte = { addr -> (addr and 0xFF).toByte() }

        val row = MemoryEditorWindow.formatRow(0x10, peek)

        assertThat(row, equalTo("0100  00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F ................"))
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

    // ---- Region colour bands (issue #171) -----------------------------------

    @Test
    fun `regionForAddress classifies the NES CPU bus into four regions`() {
        // RAM ($0000-$1FFF, mirrored every 2KB), PPU ($2000-$3FFF mirrored),
        // APU/IO ($4000-$401F), and Cart ($4020-$FFFF). The boundaries
        // deliberately include the mirrors so a $0800 read (mirror of $0000)
        // is still classified as RAM.
        assertThat(MemoryEditorWindow.regionForAddress(0x0000), equalTo(MemoryRegion.RAM))
        assertThat(MemoryEditorWindow.regionForAddress(0x07FF), equalTo(MemoryRegion.RAM))
        assertThat(MemoryEditorWindow.regionForAddress(0x0800), equalTo(MemoryRegion.RAM)) // mirror
        assertThat(MemoryEditorWindow.regionForAddress(0x1FFF), equalTo(MemoryRegion.RAM))
        assertThat(MemoryEditorWindow.regionForAddress(0x2000), equalTo(MemoryRegion.PPU))
        assertThat(MemoryEditorWindow.regionForAddress(0x2007), equalTo(MemoryRegion.PPU))
        assertThat(MemoryEditorWindow.regionForAddress(0x3FFF), equalTo(MemoryRegion.PPU)) // mirror
        assertThat(MemoryEditorWindow.regionForAddress(0x4000), equalTo(MemoryRegion.APU_IO))
        assertThat(MemoryEditorWindow.regionForAddress(0x4017), equalTo(MemoryRegion.APU_IO))
        assertThat(MemoryEditorWindow.regionForAddress(0x401F), equalTo(MemoryRegion.APU_IO))
        assertThat(MemoryEditorWindow.regionForAddress(0x4020), equalTo(MemoryRegion.CART))
        assertThat(MemoryEditorWindow.regionForAddress(0xFFFF), equalTo(MemoryRegion.CART))
    }

    @Test
    fun `regionForAddress handles negative addresses defensively as CART`() {
        // Out-of-bounds is not expected from the editor (it only passes 0..0xFFFF
        // row addresses) but the helper should not crash if it ever does. We
        // pick CART (the largest region) for negatives so a typo or a future
        // regression in row math cannot silently fall into a smaller region.
        assertThat(MemoryEditorWindow.regionForAddress(-1), equalTo(MemoryRegion.CART))
    }

    @Test
    fun `regionColor returns distinct muted colours for all four regions`() {
        // Two properties are pinned here, matching the doc on [regionColor]:
        //
        //  1. All four regions have distinct tints. A future "let me dedupe
        //     similar colours" refactor must not collapse RAM and PPU into
        //     the same pastel.
        //
        //  2. Every region is in the pastel band — all three RGB components
        //     are > 200. The change-highlight and selection colours used by
        //     the cell factory (UP green ~(46, 204, 64), DOWN blue ~(52,
        //     152, 219), SELECT amber ~(255, 193, 7)) are SATURATED — at
        //     least one channel is well below 200. If a future "let me make
        //     the RAM colour pop" tweak drops a channel below 200, the
        //     region gutter starts competing with the fade tints and the
        //     selection ring, which the doc says it must not do.
        val regions = listOf(
            MemoryEditorWindow.regionColor(MemoryRegion.RAM),
            MemoryEditorWindow.regionColor(MemoryRegion.PPU),
            MemoryEditorWindow.regionColor(MemoryRegion.APU_IO),
            MemoryEditorWindow.regionColor(MemoryRegion.CART),
        )
        // 1. All distinct.
        assertThat(regions.toSet().size, equalTo(regions.size))
        // 2. All pastel (every channel > 200).
        for ((r, g, b) in regions) {
            assertThat(r > 200 && g > 200 && b > 200, equalTo(true))
        }
    }

    // ---- ASCII decode column (issue #171) -----------------------------------

    @Test
    fun `formatAsciiColumn renders printable bytes as chars and others as dots`() {
        // 0x20 (space) through 0x7E (~) are printable; everything else is '.'.
        // Verifies the full printable range and the common non-printable cases
        // (control chars, high-bit set). Eight input bytes → eight output chars.
        val bytes = byteArrayOf(
            0x20, 0x41, 0x7E, // ' ', 'A', '~'
            0x00, 0x1F,       // control characters
            0x7F,             // DEL
            0x80.toByte(), 0xFF.toByte(), // high-bit set
        )

        val ascii = MemoryEditorWindow.formatAsciiColumn(bytes)

        assertThat(ascii, equalTo(" A~....."))
    }

    @Test
    fun `formatAsciiColumn on empty input returns an empty string`() {
        assertThat(MemoryEditorWindow.formatAsciiColumn(byteArrayOf()), equalTo(""))
    }

    @Test
    fun `formatRow includes the 16-char ASCII column on the right`() {
        // Issue #171 user story 6: every row shows a 16-character ASCII decode
        // to the right of the hex bytes. formatRow is the pure entry point
        // that the cell factory's tests already exercise; extending it to
        // include the ASCII column keeps the public contract simple (one
        // helper per row, contains everything the user reads).
        val peek: (Int) -> Byte = { addr ->
            // Spell "HELLO, WORLD!!!" (15 chars) across the first 15 bytes of
            // row 0x10 ($0100..$010E). The remaining byte ($010F) peeks to
            // zero, so the ASCII column shows a single trailing dot.
            val ch = "HELLO, WORLD!!!".toByteArray(Charsets.US_ASCII)
            val offset = addr - 0x100
            if (offset in ch.indices) ch[offset] else 0
        }

        val row = MemoryEditorWindow.formatRow(0x10, peek)

        assertThat(row, equalTo("0100  48 45 4C 4C 4F 2C 20 57 4F 52 4C 44 21 21 21 00 HELLO, WORLD!!!."))
    }

    // ---- Go-to-address parser (issue #171) -----------------------------------

    @Test
    fun `parseAddress accepts plain hex strings`() {
        assertThat(MemoryEditorWindow.parseAddress("0000"), equalTo(0x0000))
        assertThat(MemoryEditorWindow.parseAddress("00A5"), equalTo(0x00A5))
        assertThat(MemoryEditorWindow.parseAddress("2000"), equalTo(0x2000))
        assertThat(MemoryEditorWindow.parseAddress("FFFF"), equalTo(0xFFFF))
    }

    @Test
    fun `parseAddress strips a leading dollar sign`() {
        // Issue #171 example: "$00A5" must parse to 0x00A5. The dollar is
        // informal hex notation — FCEUX / Mesen style — and is the most
        // common form users will type.
        assertThat(MemoryEditorWindow.parseAddress("\$00A5"), equalTo(0x00A5))
        assertThat(MemoryEditorWindow.parseAddress("\$2000"), equalTo(0x2000))
        assertThat(MemoryEditorWindow.parseAddress("\$FFFF"), equalTo(0xFFFF))
    }

    @Test
    fun `parseAddress is case-insensitive and tolerates surrounding whitespace`() {
        // Address fields often accumulate stray spaces from focus events; the
        // parser should accept them rather than silently failing.
        assertThat(MemoryEditorWindow.parseAddress("  2000  "), equalTo(0x2000))
        assertThat(MemoryEditorWindow.parseAddress("  \$00a5  "), equalTo(0x00A5))
    }

    @Test
    fun `parseAddress returns null for empty or whitespace-only input`() {
        // Empty input is a common transient state (the user has cleared the
        // field and not yet typed) — not an error, just "not yet". Returning
        // null lets the caller treat it as a no-op rather than flag it red.
        assertThat(MemoryEditorWindow.parseAddress(""), absent())
        assertThat(MemoryEditorWindow.parseAddress("   "), absent())
    }

    @Test
    fun `parseAddress returns null for non-hex characters`() {
        // Anything that isn't 0-9 / a-f / the optional $ prefix is a typo.
        // We surface it as null so the UI can flag the field red rather than
        // throw — the editor must never crash from user input.
        assertThat(MemoryEditorWindow.parseAddress("ZZZZ"), absent())
        assertThat(MemoryEditorWindow.parseAddress("0x2000"), absent()) // 0x prefix not supported
        assertThat(MemoryEditorWindow.parseAddress("2000h"), absent())  // trailing h not supported
        assertThat(MemoryEditorWindow.parseAddress("20 00"), absent())   // internal space not supported
    }

    @Test
    fun `parseAddress returns null for out-of-range values`() {
        // The CPU bus is 16 bits. Anything > 0xFFFF doesn't fit — return null
        // so the UI flags it. The parser doesn't silently truncate.
        assertThat(MemoryEditorWindow.parseAddress("10000"), absent())
        assertThat(MemoryEditorWindow.parseAddress("FFFFF"), absent())
    }
}
