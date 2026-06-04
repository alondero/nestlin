package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.BadHeaderException
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Run [block] and return the [BadHeaderException] it throws (or fail the test
 * if it throws something else / doesn't throw). JUnit 4 has no built-in
 * equivalent of `kotlin.test.assertFailsWith` and we don't pull in
 * `kotlin-test`, so this 4-line helper is the idiomatic stand-in.
 */
private inline fun assertBadHeader(block: () -> Unit): BadHeaderException {
    try {
        block()
    } catch (e: BadHeaderException) {
        return e
    } catch (e: Throwable) {
        throw AssertionError("Expected BadHeaderException, got ${e.javaClass.simpleName}: ${e.message}", e)
    }
    throw AssertionError("Expected BadHeaderException, but no exception was thrown")
}

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
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A.toByte()
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

    // --- GitHub issue #16: GamePak must validate iNES header magic + size. ---
    // Each test below uses `assertBadHeader { ... }` (defined at top of file)
    // so the assertion fails cleanly with the message we asked for, instead of
    // letting ArrayIndexOutOfBoundsException leak out of the constructor.

    @Test
    fun gamePakWithWrongMagicBytesThrowsBadHeaderException() {
        // Bytes 0..3 are "FOO\x00" instead of the required "NES\x1A". The
        // constructor must reject this BEFORE slicing the array (otherwise
        // `data.copyOfRange(16, ...)` would throw ArrayIndexOutOfBoundsException
        // with no message about the actual problem).
        val data = ByteArray(24592) { i ->
            when (i) { 0 -> 'F'.code.toByte(); 1 -> 'O'.code.toByte(); 2 -> 'O'.code.toByte(); else -> 0 }
        }
        val ex = assertBadHeader { GamePak(data) }
        assertThat(ex.message ?: "", containsSubstring("NES"))
    }

    @Test
    fun gamePakWithMissing0x1AByteInHeaderThrowsBadHeaderException() {
        // Bytes 0..2 are "NES" but byte 3 is 0 (common when someone stubs a
        // header by writing the ASCII letters but forgets the MS-DOS EOF
        // marker). The spec requires the full 4-byte magic.
        val data = ByteArray(24592)
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53
        // data[3] deliberately left as 0.
        val ex = assertBadHeader { GamePak(data) }
        assertThat(ex.message ?: "", containsSubstring("NES"))
    }

    @Test
    fun gamePakShorterThanHeaderThrowsBadHeaderException() {
        // 5 bytes is too short for the 16-byte iNES header; the old constructor
        // would throw an unhelpful ArrayIndexOutOfBoundsException. The fix
        // must check length first and throw BadHeaderException with the size
        // in the message.
        val data = ByteArray(5)
        val ex = assertBadHeader { GamePak(data) }
        assertThat(ex.message ?: "", containsSubstring("16"))
    }

    @Test
    fun gamePakWithDeclaredPrgLargerThanFileThrowsBadHeaderException() {
        // Header is a valid 16-byte iNES header, but programRomSize (byte 4) =
        // 32 16KB banks = 512KB. The file is only 16 header + 16 PRG = 32 bytes,
        // so the declared PRG is grossly out of range. The constructor must
        // refuse rather than letting `copyOfRange(16, 524304)` blow up.
        val data = ByteArray(32)
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A.toByte()
        data[4] = 32  // declares 32 banks of PRG (512KB)
        data[5] = 0   // no CHR
        val ex = assertBadHeader { GamePak(data) }
        // Message should mention the declared size (in 16KB banks) so the user
        // can identify the corrupt header byte.
        assertThat(ex.message ?: "", containsSubstring("32"))
    }

    @Test
    fun gamePakWithDeclaredChrLargerThanFileThrowsBadHeaderException() {
        // Valid magic + valid PRG size, but chrRomSize (byte 5) = 16 8KB banks
        // = 128KB. The file has no CHR data at all. Old constructor would
        // throw ArrayIndexOutOfBoundsException on the chrRom slice.
        val data = ByteArray(16 + 16384)  // header + 1 PRG bank
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A.toByte()
        data[4] = 1   // 1 PRG bank (matches file)
        data[5] = 16  // declares 16 CHR banks (128KB) but file has none
        val ex = assertBadHeader { GamePak(data) }
        assertThat(ex.message ?: "", containsSubstring("16"))
    }

    /**
     * Regression: for a 4MB+ file with `programRomSize = 0xFF` (255 banks, the
     * spec-allowed max), the validator passes (it uses `toUnsignedInt()`), but
     * `header.programRomSize` is the signed `Byte` -1. If the second init
     * block naively does `Int * Byte` arithmetic, the slice bound goes
     * negative and `copyOfRange` throws `IllegalArgumentException` instead of
     * `BadHeaderException` -- a wrong exception class for exactly the input
     * that ought to be the *easiest* to handle (the maximum legal value).
     *
     * The fix: use `toUnsignedInt()` at the slice site so the consumer and
     * the validator agree on the type of "PRG bank count".
     */
    @Test
    fun gamePakWithMaxUnsignedPrgBankCountDoesNotThrowIllegalArgument() {
        val data = ByteArray(16 + 16384 * 255)  // exactly 4,177,936 bytes
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A.toByte()
        data[4] = 0xFF.toByte()  // 255 PRG banks (max legal value, signed -1)
        data[5] = 0              // no CHR
        // Should construct cleanly: validator passes, slice uses unsigned.
        val gp = GamePak(data)
        assertThat(gp.programRom.size, equalTo(16384 * 255))
    }
}
