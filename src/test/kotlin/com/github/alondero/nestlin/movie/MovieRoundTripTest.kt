package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * End-to-end tests for the FM2 movie record/replay engine.
 *
 * The headline property is **deterministic reproduction**: a movie recorded from a run, persisted to
 * FM2 text, re-read, and replayed into a cold machine must reproduce the original run byte-for-byte.
 * That is exactly the guarantee the "ROM + input log reproduces a bug" workflow is built on.
 */
class MovieRoundTripTest {

    private val romPath = Paths.get("testroms/nestest.nes")

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
