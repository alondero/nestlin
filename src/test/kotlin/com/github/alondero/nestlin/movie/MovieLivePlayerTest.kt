package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.Nestlin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Tests for [MovieLivePlayer] — the real-time variant of the movie replay engine.
 *
 * Headless [MoviePlayer] applies row N *before* stepping frame N; the live variant uses
 * a PPU frame-end latch so the natural game loop drives playback. The two should produce
 * the same per-frame controller state (modulo where in the frame the latch fires), and
 * the captured final save-state should match the headless replayer byte-for-byte.
 */
class MovieLivePlayerTest {

    private val romPath = Paths.get("testroms/nestest.nes")

    private fun freshNestlin(): Nestlin =
        Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }.also { it.powerReset() }

    @Test
    fun `player latches row N before frame N starts`() {
        val nes = freshNestlin()
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(
                MovieInput(controller1 = Button.A.mask),
                MovieInput(controller1 = Button.B.mask),
                MovieInput(controller1 = Button.A.mask or Button.RIGHT.mask),
            )
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()

        // The player's start() primes row 0 immediately (and bumps nextRow to 1), so
        // by the time the first frame runs, buttons should already be row 0's value.
        assertEquals(Button.A.mask, nes.getController1().buttons, "row 0 primed at start()")
        assertEquals(0, player.currentRow, "currentRow is 0 after priming row 0")

        nes.runOneFrame()  // end-of-frame: latch writes row 1 to buttons
        assertEquals(Button.B.mask, nes.getController1().buttons, "row 1 latched after first frame end")
        assertEquals(1, player.currentRow)

        nes.runOneFrame()  // end-of-frame: latch writes row 2 to buttons
        assertEquals(Button.A.mask or Button.RIGHT.mask, nes.getController1().buttons)
        assertEquals(2, player.currentRow)

        nes.runOneFrame()  // end-of-frame: latch fires but nextRow >= length → no-op
        // Player reports isFinished when the next fetch would be out-of-bounds.
        assertTrue(player.isFinished, "player should be finished after the last row")
    }

    @Test
    fun `isFinished is true once the last row has been latched`() {
        val nes = freshNestlin()
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = listOf(MovieInput(controller1 = Button.A.mask))
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()
        assertEquals(false, player.isFinished, "after start() with 1 row, no frame has run yet → not finished")
        nes.runOneFrame()
        // The single row was primed at start, then frame 0 ran. The latch saw out-of-bounds
        // and did nothing more, but the frame count advanced, so isFinished flips to true.
        assertTrue(player.isFinished, "isFinished should be true after frame 0 ends")
    }

    @Test
    fun `stop detaches the latch hook so further frames do not advance the row index`() {
        val nes = freshNestlin()
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:x",
            inputs = (0 until 10).map { MovieInput(controller1 = it) }
        )
        val player = MovieLivePlayer(nes, movie)
        player.start()
        nes.runOneFrame()
        player.stop()
        // After stop, additional frames should not advance the row index.
        val rowBefore = player.currentRow
        nes.runOneFrame()
        nes.runOneFrame()
        assertEquals(rowBefore, player.currentRow, "row index must not change after stop()")
    }

    /**
     * Headless replay (`MoviePlayer.replayInto`) and live replay (`MovieLivePlayer` driven
     * by `runOneFrame`) should land the machine in the same final state, because the row
     * N ↔ frame N association is the same in both modes. This is the cross-validating
     * property the "real-time recording can be replayed headlessly" workflow depends on.
     */
    @Test
    fun `live player and headless player produce the same final save state`() {
        val movie = Movie(
            romFilename = "nestest",
            romChecksum = "base64:any",
            inputs = (0 until 60).map { frame ->
                // Deterministic frame-varying input — no A-presses, just cycling
                // through mask combinations to exercise real latches.
                val c1 = when (frame % 4) {
                    0 -> 0
                    1 -> Button.A.mask
                    2 -> Button.A.mask or Button.RIGHT.mask
                    else -> Button.START.mask
                }
                MovieInput(controller1 = c1)
            }
        )

        // --- Live replay path ---
        val liveNes = freshNestlin()
        val livePlayer = MovieLivePlayer(liveNes, movie)
        livePlayer.start()
        repeat(movie.length) { liveNes.runOneFrame() }
        val liveBytes = java.io.ByteArrayOutputStream().also { liveNes.saveState(it) }.toByteArray()

        // --- Headless replay path ---
        val headlessNes = freshNestlin()
        MoviePlayer().replayInto(headlessNes, movie)
        val headlessBytes = java.io.ByteArrayOutputStream().also { headlessNes.saveState(it) }.toByteArray()

        // The two paths use the same per-frame ordering (row N applied before frame N
        // runs), so the final CPU/PPU/APU/mapper state should be byte-identical.
        assertTrue(
            liveBytes.contentEquals(headlessBytes),
            "live and headless replay must converge to the same final state",
        )
    }
}
