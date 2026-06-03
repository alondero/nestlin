package com.github.alondero.nestlin.gamepak

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class GamePakTest {

    @Test
    fun MapperAsHighNybblesOf6ByteAsLowAnd7ByteAsHigh() {
        val header = Header(ByteArray(16, {when (it) {
            6 -> 0b00010000
            7 -> 0b01000000
            else -> 0
        }}))

        assertThat(header.mapper, equalTo(0b01000001)) // nybble(0100) b7, nybble(0001) b6
    }

    /**
     * Regression: the in-ROM title field (bytes 0x10-0x8F, when NES 2.0 byte 7 bit 4
     * is set) used to be decoded via `String(bytes)`, which falls back to the JVM's
     * platform default charset. On Western Windows that maps 0x80 to U+20AC (euro)
     * and 0x99 to U+2122 (trademark) -- corrupting any non-ASCII title (Famicom dumps
     * routinely store Shift-JIS or Latin-1 bytes here). The titlebar would then show
     * this mojibake via `currentGameName()`.
     *
     * The fix decodes with Charsets.ISO_8859_1 -- a 1:1 byte->codepoint mapping that
     * never substitutes, so the original bytes survive (and a downstream caller who
     * knows the source encoding can re-decode). For these two test bytes that means
     * U+0080 and U+0099 (Latin-1 control chars), NOT the Windows-1252 reinterpretation.
     */
    @Test
    fun inRomTitleWithNes20HeaderIsDecodedWithoutCharsetSubstitution() {
        // 0x80 and 0x99 are the most visible Latin-1 vs Windows-1252 deltas:
        //   Latin-1 0x80 -> U+0080 (control); Windows-1252 0x80 -> U+20AC (euro)
        //   Latin-1 0x99 -> U+0099 (control); Windows-1252 0x99 -> U+2122 (trademark)
        // After trim() (which strips <= U+0020), both interpretations survive -- making
        // the charset difference directly observable in the resulting `name`.
        val titleBytes = byteArrayOf(0x80.toByte(), 0x99.toByte())
        // PRG=1 (16KB) + CHR=1 (8KB) + 16-byte header = 24592 bytes minimum.
        val data = ByteArray(24592)
        // NES magic.
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53
        // PRG=1 bank, CHR=1 bank, mapper 0, vertical mirroring.
        data[4] = 0x01
        data[5] = 0x01
        // NES 2.0 marker in bits 2-3 of byte 7 (0b0000_1000), bit 4 set = title present.
        data[7] = 0x18.toByte()
        // Fill the 128-byte title field at 0x10..0x90 with our non-ASCII bytes,
        // padded with zeros (which get replaced with spaces then trimmed).
        titleBytes.copyInto(data, destinationOffset = 0x10)
        // Ensure the rest of the 128-byte field is null-terminated.
        for (i in 0x10 + titleBytes.size until 0x10 + 128) data[i] = 0

        val gamePak = GamePak(data)

        // Latin-1 decode preserves byte values: 0x80 -> U+0080, 0x99 -> U+0099.
        // Windows-1252 (the old behaviour) would have produced U+20AC and U+2122.
        // Build the expected value from codepoints to keep the source pure ASCII
        // (the U+0080 control char would otherwise get mangled by the editor).
        val expected = String(CharArray(2) { i -> if (i == 0) 0x80.toChar() else 0x99.toChar() })
        assertThat(gamePak.name, equalTo(expected))
    }

    /**
     * Regression: Header.mapper used to decode as `(byte6 >> 4) | (byte7 & 0xF0)`
     * unconditionally, which in NES 2.0 -- where bit 4 of byte 7 is the title flag
     * (not a mapper bit) -- wrongly added 16 to every mapper number for any dump
     * with a title (basically every modern Famicom dump). So mapper 0 + NES 2.0 +
     * title got misread as mapper 16, crashing on `Mapper 16 not implemented`.
     *
     * The fix splits the decode by `isNes20`: in NES 2.0, mapper bits 4-6 come from
     * byte 7 bits 5-7, and bits 8-15 come from byte 8. Without title (bit 4 clear),
     * the old plain-iNES decode is preserved.
     */
    @Test
    fun nes20MapperWithTitleBitIsNotInflatedBySixteen() {
        // NES 2.0 + title + mapper 0. The old decoder returned 16; the fix returns 0.
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53
        header[4] = 0x01; header[5] = 0x01
        // byte 6 high nibble = 0 (mapper low). byte 7 = 0b0001_1000:
        //   bits 5-7 = 000 (mapper high); bit 4 = 1 (title); bits 2-3 = 10 (NES 2.0)
        header[7] = 0x18.toByte()
        assertThat(Header(header).mapper, equalTo(0))
    }

    @Test
    fun nes20MapperFromByte8WorksForExtendedMappers() {
        // NES 2.0 + mapper 256: byte 6 high nibble = 0, byte 7 bits 5-7 = 0,
        // byte 8 = 0x01 (mapper bits 8-15 = 1 -> mapper 256).
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53
        header[7] = 0x08.toByte()  // NES 2.0 marker, no title
        header[8] = 0x01
        assertThat(Header(header).mapper, equalTo(256))
    }

    @Test
    fun plainInesMapperDecodeIsUnchangedByNes20Fix() {
        // Plain iNES (byte 7 bits 2-3 = 00, NOT 10): the old decode is still used.
        // byte 6 = 0x21 (mapper low = 2, vertical mirroring, battery);
        // byte 7 = 0x10 (mapper high = 1) -> mapper 0x12 = 18. The Mapper1Test
        // helper uses this exact pattern; we assert it still parses the same way.
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53
        header[6] = 0x21.toByte()
        header[7] = 0x10.toByte()
        val h = Header(header)
        assertThat(h.isNes20, equalTo(false))
        assertThat(h.mapper, equalTo(0x12))
    }
}
