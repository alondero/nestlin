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
     * Regression for GH #118 / the #117 misdiagnosis: in BOTH iNES and NES 2.0,
     * byte 7 bit 4 is mapper number bit D4 (NESdev: byte 7 D7..D4 = mapper D7..D4).
     * It is NOT a "title-present" flag. PR #117 treated it as a title flag and
     * dropped it from the mapper number, which misread every Bandai FCG (mapper 16)
     * and similar title — e.g. Rokudenashi Blues decoded as mapper 0x5000.
     */
    @Test
    fun nes20Byte7Bit4IsMapperBitNotTitleFlag() {
        // Rokudenashi Blues header bytes: byte6=0x02, byte7=0x18, byte8=0x50.
        // NES 2.0 (byte7 bits 2-3 = 10). mapper = 0 | (0x18 & 0xF0)=0x10 | 0 = 16.
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53; header[3] = 0x1A
        header[6] = 0x02
        header[7] = 0x18.toByte()
        header[8] = 0x50.toByte()
        val h = Header(header)
        assertThat(h.isNes20, equalTo(true))
        assertThat(h.mapper, equalTo(16))
        // The byte-8 high nibble is the submapper, and must NOT leak into the mapper.
        assertThat(h.submapper, equalTo(5))
    }

    @Test
    fun plainInesByte7Bit4IsMapperBit() {
        // Crayon Shin-chan - Ora to Poi Poi (Japan): plain iNES (byte7 bits 2-3 = 00),
        // byte6=0x00, byte7=0x10 -> mapper = 0 | 0x10 = 16 (Bandai FCG).
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53; header[3] = 0x1A
        header[6] = 0x00
        header[7] = 0x10.toByte()
        val h = Header(header)
        assertThat(h.isNes20, equalTo(false))
        assertThat(h.mapper, equalTo(16))
    }

    @Test
    fun nes20MapperFromByte8WorksForExtendedMappers() {
        // NES 2.0 + mapper 256: byte 6 high nibble = 0, byte 7 bits 4-7 = 0,
        // byte 8 low nibble = 0x01 (mapper bits 8-11 = 1 -> mapper 256).
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53; header[3] = 0x1A
        header[7] = 0x08.toByte()  // NES 2.0 marker
        header[8] = 0x01
        assertThat(Header(header).mapper, equalTo(256))
    }

    @Test
    fun nes20SubmapperNibbleDoesNotLeakIntoMapper() {
        // byte 8 = 0x51: low nibble 1 -> mapper bits 8-11; high nibble 5 -> submapper.
        // mapper must be 0x100 (256), NOT 0x5100 — i.e. the submapper stays out of it.
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53; header[3] = 0x1A
        header[7] = 0x08.toByte()  // NES 2.0 marker
        header[8] = 0x51.toByte()
        val h = Header(header)
        assertThat(h.mapper, equalTo(256))
        assertThat(h.submapper, equalTo(5))
    }

    @Test
    fun plainInesMapperDecodeIsUnchanged() {
        // Plain iNES (byte 7 bits 2-3 = 00): byte 6 = 0x21 (mapper low = 2),
        // byte 7 = 0x10 (mapper high = 1) -> mapper 0x12 = 18. The Mapper1Test helper
        // uses this exact pattern; we assert it still parses the same way.
        val header = ByteArray(16)
        header[0] = 0x4E; header[1] = 0x45; header[2] = 0x53; header[3] = 0x1A
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
     * Regression for GH #212: a malformed 0-PRG dump (iNES byte 4 = 0) used
     * to slip past header validation, leaving `programRom` empty. The very
     * next thing the mapper layer did was `x % programRom.size`, which
     * ArithmeticExceptions divides by zero — Mapper1, Mapper10, Mapper11,
     * Mapper16, Mapper33, Mapper34, Mapper64, Mapper65, Mapper68, Mapper69,
     * Mapper71, Mapper113, Mapper153, Mapper206, plus others, all had the
     * same unguarded pattern. The fix validates PRG-bank count >= 1 here so
     * the bad input never reaches the mapper layer in the first place.
     *
     * The 0-PRG case is rejected before the size-overflow check (which also
     * reads byte 4) because the size check would silently pass: 0 declared
     * bytes trivially fits inside any file, including the 16-byte header-
     * only stub used here. The message must name iNES byte 4 so a user
     * hand-editing a header knows exactly which byte to fix.
     */
    @Test
    fun gamePakWithZeroPrgBanksThrowsBadHeaderException() {
        // 16-byte iNES header only — no PRG, no CHR. Byte 4 = 0 is the
        // malformed 0-PRG signature from the issue.
        val data = ByteArray(16)
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A.toByte()
        data[4] = 0   // declares ZERO 16KB PRG banks — every NES cartridge must have >= 1
        data[5] = 0   // no CHR (valid; CHR-RAM cart or test stub)
        val ex = assertBadHeader { GamePak(data) }
        // The message must name iNES byte 4 — hand-headers are a common case.
        assertThat(ex.message ?: "", containsSubstring("byte 4"))
        assertThat(ex.message ?: "", containsSubstring("PRG"))
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

    /**
     * Regression for the Crayon Shin-chan "question mark titlebar": there is no
     * in-ROM title field at offset 0x10 (that is the start of PRG ROM). A ROM with
     * byte 7 bit 4 set (a mapper-16 game) must NOT have its PRG bytes rendered as a
     * name — the display name (filename) is used instead.
     */
    @Test
    fun nameFallsBackToDisplayNameAndNeverReadsPrgAsTitle() {
        // PRG=1 (16KB) + CHR=1 (8KB) + 16-byte header.
        val data = ByteArray(16 + 16384 + 8192)
        data[0] = 0x4E; data[1] = 0x45; data[2] = 0x53; data[3] = 0x1A
        data[4] = 0x01   // PRG banks
        data[5] = 0x01   // CHR banks
        data[7] = 0x10.toByte()  // mapper bit D4 set (mapper 16); used to be misread as "title present"
        // Put non-ASCII bytes where the old code read a "title" — these are PRG ROM.
        data[0x10] = 0x80.toByte(); data[0x11] = 0x99.toByte()

        val gamePak = GamePak(data, "Crayon Shin-chan - Ora to Poi Poi (Japan)")
        assertThat(gamePak.name, equalTo("Crayon Shin-chan - Ora to Poi Poi (Japan)"))
    }
}
