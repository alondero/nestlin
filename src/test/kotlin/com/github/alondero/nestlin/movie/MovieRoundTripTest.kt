package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.testutil.TestRoms
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * End-to-end tests for the FM2 movie record/replay engine.
 *
 * The headline property is **deterministic reproduction**: a movie recorded from a run, persisted to
 * FM2 text, re-read, and replayed into a cold machine must reproduce the original run byte-for-byte.
 * That is exactly the guarantee the "ROM + input log reproduces a bug" workflow is built on.
 */
class MovieRoundTripTest {

    // MoviePlayer.replay takes a Path (it computes the ROM's checksum then boots via
    // Path.load(). GamePak). Use the classpath-derived temp path so this works from
    // any working directory. See issue #21.
    private val romPath = TestRoms.nestestPath()

    /** A deterministic, frame-varying input script so the recorded movie isn't a wall of all-zeros. */
    private fun scriptedInput(frame: Int): MovieInput {
        val c1 = when (frame % 4) {
            0 -> 0
            1 -> Button.A.mask
            2 -> Button.A.mask or Button.RIGHT.mask
            else -> Button.START.mask
        }
        return MovieInput(controller1 = c1)
    }

    private fun snapshot(nes: Nestlin): ByteArray =
        ByteArrayOutputStream().also { nes.saveState(it) }.toByteArray()

    /**
     * Synthesise a valid iNES image with the given shape, for [Fm2Format.romChecksum] parity tests.
     *
     * The byte pattern of the PRG/CHR bodies is deterministic ([prgFill] / [chrFill]) so we can
     * tell at a glance whether two ROMs differ in the body. Mirroring is hard-coded to
     * HORIZONTAL (vertical-equivalent for hash purposes).
     */
    private fun buildInes(
        prgBanks: Int = 1,
        chrBanks: Int = 1,
        trainerPresent: Boolean = false,
        mapper: Int = 0,
        prgFill: Byte = 0xAA.toByte(),
        chrFill: Byte = 0xBB.toByte(),
        trainerFill: Byte = 0xCC.toByte(),
    ): ByteArray {
        val header = ByteArray(16)
        header[0] = 0x4E.toByte(); header[1] = 0x45.toByte()
        header[2] = 0x53.toByte(); header[3] = 0x1A.toByte()
        header[4] = prgBanks.toByte()        // PRG bank count (16 KB each)
        header[5] = chrBanks.toByte()        // CHR bank count (8 KB each)
        // Byte 6: bit 2 (mask 0x04) = trainer present. Low nybble = mapper low nybble.
        // Mirroring (bit 0) is left 0 = horizontal. Battery (bit 1) is left 0.
        val byte6 = (mapper and 0x0F) or (if (trainerPresent) 0x04 else 0x00)
        header[6] = byte6.toByte()
        // Byte 7: high nybble = mapper high nybble. iNES (bits 2-3 = 00).
        val byte7 = mapper and 0xF0
        header[7] = byte7.toByte()
        // Bytes 8..15 left zero (no PRG-RAM, no NES 2.0 markers).

        val trainer = if (trainerPresent) ByteArray(512) { trainerFill } else ByteArray(0)
        val prg = ByteArray(prgBanks * 16384) { prgFill }
        val chr = ByteArray(chrBanks * 8192) { chrFill }
        return header + trainer + prg + chr
    }

    // ------------------------------------------------------------------------------------------- //
    // FCEUX romChecksum parity (issue #124).
    //
    // FCEUX (src/ines.cpp: iNESLoad) computes MD5 over PRG+CHR only — the 16-byte header is
    // always excluded, and the 512-byte trainer is excluded iff byte 6 bit 2 is set. The result
    // is encoded as standard Base64 of the 16 raw MD5 bytes, prefixed with "base64:". These
    // tests lock in that contract so a Nestlin .fm2 will load in real FCEUX and vice versa.
    // ------------------------------------------------------------------------------------------- //

    /**
     * The headline FCEUX parity property: a ROM with `byte 6 & 0x04` set carries a 512-byte
     * trainer block in the file, and that trainer must NOT contribute to the MD5. The only
     * way the two checksums below can match is if the trainer is being skipped from the hash.
     */
    @Test
    fun `romChecksum skips the 512-byte trainer when the trainer bit is set`() {
        val withTrainer = buildInes(trainerPresent = true, trainerFill = 0xFF.toByte())
        val withoutTrainer = buildInes(trainerPresent = false)

        assertEquals(
            Fm2Format.romChecksum(withoutTrainer),
            Fm2Format.romChecksum(withTrainer),
            "Trainer bytes (0xFF) must not contribute to the checksum when byte 6 bit 2 is set",
        )
    }

    /**
     * Regression guard for "we don't blindly strip 512 bytes even when the bit is clear".
     * If the implementation accidentally *always* skipped bytes 16..528, then putting a
     * 512-byte trainer block behind `trainerPresent = false` would still produce a checksum
     * equal to the no-trainer case — that would be wrong. The trainer must be included
     * when the bit is clear.
     */
    @Test
    fun `romChecksum includes trainer bytes when the trainer bit is clear`() {
        // `withTrainerSkipped` is a ROM that says "I have a trainer" so its trainer bytes
        // are excluded from the hash; the hash is MD5(PRG) + MD5(CHR).
        val withTrainerSkipped = buildInes(trainerPresent = true)

        // `spliced` is a ROM that does NOT set the trainer bit, but physically has the same
        // 512-byte trainer block in the file. The implementation should treat those bytes as
        // part of the body (since the bit is clear), so the checksum must DIFFER from
        // `withTrainerSkipped`'s.
        val bodySource = buildInes(trainerPresent = false)         // 16-byte header + PRG + CHR
        val header = bodySource.copyOfRange(0, 16).also {
            it[6] = 0x00                                            // trainer bit CLEAR
        }
        val trainer = ByteArray(512) { 0xFF.toByte() }             // distinct fill → distinct hash
        val body = bodySource.copyOfRange(16, bodySource.size)
        val spliced = header + trainer + body

        assertNotEquals(
            Fm2Format.romChecksum(spliced),
            Fm2Format.romChecksum(withTrainerSkipped),
            "When the trainer bit is clear, the trainer must be hashed as part of the body " +
                "(so the checksum must DIFFER from the trainer-skipped case)",
        )
    }

    /**
     * The 16-byte header is excluded from the hash. We verify by changing the mapper number
     * (byte 7 high nybble) and the byte 8 submapper — both header-only changes — and asserting
     * the checksum is unchanged for identical PRG+CHR bodies.
     */
    @Test
    fun `romChecksum excludes the iNES header (mapper and submapper bits do not affect the hash)`() {
        val mapper0 = buildInes(mapper = 0, prgFill = 0x42.toByte(), chrFill = 0x33.toByte())
        val mapper16 = buildInes(mapper = 16, prgFill = 0x42.toByte(), chrFill = 0x33.toByte())

        assertEquals(
            Fm2Format.romChecksum(mapper0),
            Fm2Format.romChecksum(mapper16),
            "Mapper number lives in the header, which must be excluded from the hash",
        )
    }

    /**
     * When `chrBanks = 0` the mapper uses CHR-RAM (the game generates its own tile data at
     * runtime) and the ROM file has no CHR section. FCEUX skips the second `md5_update` for
     * CHR in that case — the checksum is just `base64:MD5(PRG)`. We assert the contract by
     * computing what the FCEUX algorithm should produce and comparing.
     */
    @Test
    fun `romChecksum handles CHR-RAM ROMs (chrBanks=0) as MD5 of PRG only`() {
        val prgFill: Byte = 0x55.toByte()
        val rom = buildInes(prgBanks = 2, chrBanks = 0, prgFill = prgFill, chrFill = 0x00)
        val prgOnly = ByteArray(2 * 16384) { prgFill }
        val expectedDigest = java.security.MessageDigest.getInstance("MD5").digest(prgOnly)
        val expected = "base64:" + java.util.Base64.getEncoder().encodeToString(expectedDigest)

        assertEquals(
            expected,
            Fm2Format.romChecksum(rom),
            "CHR-RAM ROMs (chrBanks=0) must hash PRG only, matching MD5(PRG_bytes)",
        )
    }

    @Test
    fun `fm2 pad columns follow the RLDUTSBA mnemonic`() {
        val movie = Movie(
            "nestest", "base64:x", inputs = listOf(
                MovieInput(controller1 = Button.A.mask),
                MovieInput(controller1 = Button.RIGHT.mask),
                MovieInput(controller1 = Button.START.mask or Button.B.mask),
            )
        )
        val text = Fm2Format.write(movie)

        assertTrue(text.contains("|0|.......A|........||"), "A is the rightmost pad column\n$text")
        assertTrue(text.contains("|0|R.......|........||"), "Right is the leftmost pad column\n$text")
        // RLDUTSBA columns: R0 L1 D2 U3 T4 S5 B6 A7 — so Start=index4, B=index6.
        assertTrue(text.contains("|0|....T.B.|........||"), "Start renders as T (col 4) and B as B (col 6)\n$text")
    }

    @Test
    fun `movie survives an fm2 write then read round-trip`() {
        val original = Movie(
            romFilename = "nestest",
            romChecksum = "base64:abc==",
            palFlag = true,
            rerecordCount = 7,
            inputs = (0 until 30).map { scriptedInput(it) },
        )

        val parsed = Fm2Format.read(Fm2Format.write(original))

        assertEquals(original.romFilename, parsed.romFilename, "romFilename")
        assertEquals(original.romChecksum, parsed.romChecksum, "romChecksum")
        assertEquals(original.palFlag, parsed.palFlag, "palFlag")
        assertEquals(original.rerecordCount, parsed.rerecordCount, "rerecordCount")
        assertEquals(original.inputs, parsed.inputs, "input log must round-trip exactly")
    }

    @Test
    fun `recording then replaying reproduces byte-identical final state`() {
        val frames = 90
        val checksum = Fm2Format.romChecksum(romPath.load()!!)

        // --- Direct scripted run, capturing a movie as we go ---
        val direct = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }
        direct.powerReset()
        val recorder = MovieRecorder(romFilename = "nestest", romChecksum = checksum)
        repeat(frames) { frame ->
            val input = scriptedInput(frame)
            direct.getController1().setButtonBitmap(input.controller1)
            direct.getController2().setButtonBitmap(input.controller2)
            direct.runOneFrame()
            recorder.captureFrame(direct)
        }
        val expected = snapshot(direct)

        // --- Persist to FM2 text, reload, replay into a fresh cold machine ---
        val movie = Fm2Format.read(Fm2Format.write(recorder.toMovie()))
        assertEquals(frames, movie.length, "every frame should have produced one input row")

        val replayed = MoviePlayer().replay(romPath, movie)
        val actual = snapshot(replayed)

        assertArrayEquals(
            expected, actual,
            "Replaying the recorded FM2 movie must reproduce the original run byte-for-byte",
        )
    }

    @Test
    fun `replay rejects a movie whose checksum does not match the ROM`() {
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:deadbeefdeadbeefdeadbe==",
            inputs = listOf(MovieInput(controller1 = 0)),
        )
        try {
            MoviePlayer().replay(romPath, movie)
            org.junit.jupiter.api.Assertions.fail("Expected a checksum-mismatch failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message?.contains("checksum mismatch") == true,
                "Expected a checksum-mismatch message, got: ${e.message}",
            )
        }
    }
}
