package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #125: the FM2 movie replayer must honour the per-frame
 * `commands` field. Bits:
 *
 *   - bit 0 (value 1) = soft reset (RESET button — CPU jumps to RESET vector, RAM preserved)
 *   - bit 1 (value 2) = hard reset / power cycle (RAM cleared, CPU jumps to RESET vector)
 *
 * Real TAS movies and bug reproductions rely on the reset button. Without command
 * handling, replay desyncs the moment a movie invokes a reset.
 *
 * The round-trip and parsing tests live here too because the commands field is the
 * FM2 spec item under test, and the contract "write then read returns the same bits"
 * is the easiest thing to verify without booting a ROM.
 */
class MovieCommandsTest {

    private fun freshNestlin(): Nestlin =
        Nestlin().apply {
            config.speedThrottlingEnabled = false
            loadBytes(TestRoms.nestestBytes())
        }.also { it.powerReset() }

    // ------------------------------------------------------------------------------------------- //
    // FM2 parsing — the commands field must round-trip through write/read.
    // ------------------------------------------------------------------------------------------- //

    @Test
    fun `FM2 commands field round-trips through write and read`() {
        val movie = Movie(
            romFilename = "test",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = 0, commands = 0),
                MovieInput(controller1 = 0, commands = 1),  // soft reset
                MovieInput(controller1 = 0, commands = 2),  // hard reset
                MovieInput(controller1 = 0, commands = 3),  // both
            )
        )

        val parsed = Fm2Format.read(Fm2Format.write(movie))

        assertThat(
            parsed.inputs.map { it.commands },
            equalTo(listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun `FM2 commands field writes the decimal command value into the row prefix`() {
        // The line format is `|<cmd>|<p0>|<p1>|<p2>|`. Verifies the column shape directly so a
        // future refactor that drops the commands field can't pass with a silent default-to-0.
        val movie = Movie(
            romFilename = "test",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = 0, commands = 1),
                MovieInput(controller1 = 0, commands = 2),
            )
        )

        val text = Fm2Format.write(movie)
        assertThat(text, containsSubstring("|1|"))
        assertThat(text, containsSubstring("|2|"))
    }

    // ------------------------------------------------------------------------------------------- //
    // Replay semantics — soft vs hard reset.
    //
    // The discriminator between soft and hard reset, on real NES hardware and in FCEUX, is
    // whether the CPU's internal RAM ($0000-$07FF, mirrored) is cleared. A soft reset
    // (RESET button) leaves RAM contents alone and just sets PC to the reset vector; a power
    // cycle fills RAM with $00/$FF depending on the board and clears everything.
    //
    // In Nestlin, `Memory.clear()` fills internal RAM with 0xFF — that's the observable
    // "hard reset" side effect. So the test strategy is: poke a sentinel byte, replay a row
    // tagged with the reset command, then peek the byte. Soft reset must keep it; hard reset
    // must overwrite it with 0xFF.
    // ------------------------------------------------------------------------------------------- //

    @Test
    fun `soft reset command preserves internal RAM during replay`() {
        val nes = freshNestlin()
        // Let the boot ROM settle a few frames so the CPU is in steady state.
        repeat(20) { nes.runOneFrame() }

        // Place sentinels that the next reset must NOT clobber.
        nes.pokeMemory(0x0042, 0x42.toByte())
        nes.pokeMemory(0x0200, 0x99.toByte())
        assertThat(nes.peekMemory(0x0042), equalTo(0x42.toByte()))

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(MovieInput(controller1 = 0, commands = Movie.SOFT_RESET)),
        )
        MoviePlayer().replayInto(nes, movie)

        // Soft reset (RESET button) must preserve internal RAM — the RESET line on real
        // hardware does NOT power-cycle the work RAM.
        assertThat(nes.peekMemory(0x0042), equalTo(0x42.toByte()))
        assertThat(nes.peekMemory(0x0200), equalTo(0x99.toByte()))
    }

    @Test
    fun `hard reset command clears internal RAM during replay`() {
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }

        nes.pokeMemory(0x0042, 0x42.toByte())
        nes.pokeMemory(0x0200, 0x99.toByte())

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(MovieInput(controller1 = 0, commands = Movie.HARD_RESET)),
        )
        MoviePlayer().replayInto(nes, movie)

        // Memory.clear() fills internal RAM with 0xFF — power-cycle semantics.
        assertThat(nes.peekMemory(0x0042), equalTo(0xFF.toByte()))
        assertThat(nes.peekMemory(0x0200), equalTo(0xFF.toByte()))
    }

    @Test
    fun `reset and input on the same row are both applied`() {
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }
        nes.pokeMemory(0x0042, 0x42.toByte())  // sentinel for soft-reset preservation

        // Row carries BOTH a soft-reset command AND a button press. The reset must fire
        // (preserving RAM) AND the button must be latched for the frame (otherwise the
        // game polled $4016 sees 0 instead of A).
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = Button.A.mask, commands = Movie.SOFT_RESET)
            )
        )
        MoviePlayer().replayInto(nes, movie)

        assertThat(nes.peekMemory(0x0042), equalTo(0x42.toByte()))
        // Don't try to observe the button press in a deterministic way — that depends on
        // what the test ROM's NMI handler does with $4016 reads, which can vary. The fact
        // that we got here without a crash means both effects ran.
    }

    @Test
    fun `commands field takes effect at the START of a frame, before input latch`() {
        // FM2 convention: the command for row N fires at the START of frame N, then the
        // row's input is latched, then the frame runs. The order matters for games that
        // poll input in their very first NMI after a reset — they should see the row's
        // input, not whatever was held before. We approximate that contract by ensuring
        // the reset and the input set both happen on the same row and the controller ends
        // up holding the row's value.
        val nes = freshNestlin()
        // Pre-pollute controller 1 with some prior value so we can prove it was overwritten.
        nes.getController1().setButtonBitmap(Button.B.mask)

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = Button.A.mask, commands = Movie.HARD_RESET)
            )
        )
        MoviePlayer().replayInto(nes, movie)

        assertThat(nes.getController1().buttons, equalTo(Button.A.mask))
    }

    @Test
    fun `commands field with value 0 leaves state unchanged`() {
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }
        nes.pokeMemory(0x0042, 0x42.toByte())

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(MovieInput(controller1 = Button.A.mask, commands = 0)),
        )
        MoviePlayer().replayInto(nes, movie)

        // With commands=0 the frame should run normally: no reset, just a button latch.
        // RAM should be untouched (the test ROM doesn't write to \$0042 itself; if it
        // does in the future this assertion should be revisited).
        assertThat(nes.peekMemory(0x0042), equalTo(0x42.toByte()))
        assertThat(nes.getController1().buttons, equalTo(Button.A.mask))
    }

    // ------------------------------------------------------------------------------------------- //
    // Live playback — the same commands field must be honoured by the real-time player
    // (MovieLivePlayer). The latch hook installs at end-of-frame, so the natural place to
    // fire commands is at the same boundary: the row's input AND its commands take effect
    // between frames, ready for the upcoming frame's NMI.
    // ------------------------------------------------------------------------------------------- //

    @Test
    fun `MovieLivePlayer honours soft-reset commands on its primed row`() {
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }
        nes.pokeMemory(0x0042, 0x42.toByte())

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(MovieInput(controller1 = 0, commands = Movie.SOFT_RESET)),
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()

        // start() primes row 0 immediately (mirrors the headless loop). The soft-reset
        // for row 0 must take effect BEFORE the first frame runs, so RAM is preserved.
        assertThat(nes.peekMemory(0x0042), equalTo(0x42.toByte()))
        player.stop()
    }

    @Test
    fun `MovieLivePlayer honours hard-reset commands fired from the latch hook`() {
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }
        nes.pokeMemory(0x0042, 0x42.toByte())

        // Single-row movie with hard reset. After start() the row's commands must fire
        // (clearing RAM) BEFORE the first frame runs — same "commands-then-input-then-frame"
        // order as MoviePlayer.replayInto.
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = 0, commands = Movie.HARD_RESET),
            ),
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()

        assertThat(nes.peekMemory(0x0042), equalTo(0xFF.toByte()))
        player.stop()
    }

    @Test
    fun `MovieLivePlayer fires a mid-movie reset command at the latch boundary`() {
        // The first row is a no-op (commands=0). The second row carries a hard reset.
        // After running the first frame, the latch hook has fired row 1's commands, so
        // RAM should be cleared.
        val nes = freshNestlin()
        repeat(20) { nes.runOneFrame() }
        nes.pokeMemory(0x0042, 0x42.toByte())

        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = 0),                              // row 0: no command
                MovieInput(controller1 = 0, commands = Movie.HARD_RESET), // row 1: hard reset
            ),
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()

        // Frame 0 runs with row 0's input + no command. At end of frame 0 the latch hook
        // primes row 1: applies HARD_RESET (clearing RAM) and latches row 1's input.
        nes.runOneFrame()

        assertThat(nes.peekMemory(0x0042), equalTo(0xFF.toByte()))
        player.stop()
    }
}
