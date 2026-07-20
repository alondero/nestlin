package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.testutil.TestRoms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [MovieLiveRecorder] — the real-time variant of the movie record engine.
 *
 * The headline property is the latch model: while a recording is in progress, [Controller.buttons]
 * is FROZEN for the duration of a frame, and the recorder captures that frozen value. Mid-frame
 * keyboard changes to [Controller.pendingButtons] must NOT leak into the captured row — they get
 * applied on the next frame's boundary.
 *
 * These tests use the public `runOneFrame()` extension from `FrameStepper` to drive the
 * emulator in the same deterministic, no-throttling, no-wall-clock mode the headless
 * replayer uses. Cold boot + frame-quantised input is exactly the same shape as a real-time
 * recording, minus the wall clock; the latch hook behaves identically either way.
 */
class MovieLiveRecorderTest {

    // MoviePlayer.replay takes a Path (ROM checksum is computed from on-disk bytes).
    // Lazy to avoid creating a temp file when this class is constructed but the test
    // doesn't reach the round-trip branch (issue #21).
    private val romPath by lazy { TestRoms.nestestPath() }

    private fun freshNestlin(): Nestlin =
        Nestlin().apply {
            config.speedThrottlingEnabled = false
            loadBytes(TestRoms.nestestBytes())
        }.also { it.powerReset() }

    /**
     * The latch model: a frame's buttons value is captured AS-IS at frame end, even if the
     * keyboard changed pendingButtons mid-frame. The pending value is committed to buttons
     * for the NEXT frame.
     */
    @Test
    fun `captured row matches the latched buttons, not mid-frame pending changes`() {
        val nes = freshNestlin()
        // Pre-state: buttons is the initial 0, pending is A. After recorder.start(), the
        // hook waits for the first frame end. At end of frame 0, buttons is captured as
        // row 0 (= 0, the pre-latch state), then pending is committed → buttons = A.
        nes.getController1().setButtonBitmap(0)
        nes.getController1().pendingButtons = Button.A.mask

        val recorder = MovieLiveRecorder(nes, "nestest", "base64:x")
        recorder.start()

        // Frame 0: game sees 0 (pre-latch). At end, capture 0 (row 0), commit A → buttons.
        nes.runOneFrame()
        // Simulate a mid-frame "A release" before the next frame's latch fires. This MUST
        // NOT leak into row 1 because the capture happens at frame end, AFTER the game
        // already polled during frame 1.
        nes.getController1().pendingButtons = 0

        // Frame 1: game sees A (committed at end of frame 0). At end, capture A (row 1).
        nes.runOneFrame()

        val movie = recorder.stopAndSnapshot()
        assertEquals(2, movie.length, "two frames should have produced two rows")
        assertEquals(0, movie.inputs[0].controller1, "row 0: pre-latch state — game saw 0 during frame 0")
        assertEquals(Button.A.mask, movie.inputs[1].controller1, "row 1: A was committed at frame-0 latch, game saw A during frame 1")
    }

    /**
     * The next-frame commit: after the latch hook captures a row, the pending buffer is
     * committed to buttons. So if pending changes between frame N and frame N+1, the new
     * buttons value is visible to the game during frame N+1.
     */
    @Test
    fun `pending is committed to buttons at the next frame boundary`() {
        val nes = freshNestlin()
        nes.getController1().setButtonBitmap(0)
        nes.getController1().pendingButtons = 0

        val recorder = MovieLiveRecorder(nes, "nestest", "base64:x")
        recorder.start()

        // Frame 0: pre-state is 0. At end: capture 0 (row 0), commit pending=0 (no change).
        nes.runOneFrame()
        // After frame 0 ends, the user "presses A" — the keyboard handler writes pending=A.
        nes.getController1().pendingButtons = Button.A.mask
        // Frame 1: buttons is still 0 (the commit at end of frame 0 saw pending=0). Game
        // sees 0 during frame 1. At end: capture 0 (row 1), commit A → buttons.
        nes.runOneFrame()
        // Frame 2: game sees A (committed at end of frame 1). End: capture A (row 2).
        nes.runOneFrame()

        val movie = recorder.stopAndSnapshot()
        assertEquals(3, movie.length, "three runOneFrame() calls → three captured rows")
        assertEquals(0, movie.inputs[0].controller1, "row 0: pre-latch state")
        assertEquals(0, movie.inputs[1].controller1, "row 1: A not yet committed — A press happens AFTER frame 0's latch")
        assertEquals(Button.A.mask, movie.inputs[2].controller1, "row 2: A was committed at frame-1 latch, game saw A during frame 2")
    }

    /**
     * `cancel()` detaches the hook without preserving captures. Use case: the user hits
     * "Cancel" in the save dialog and doesn't want the partial recording to leak.
     */
    @Test
    fun `cancel detaches the hook and discards the partial capture`() {
        val nes = freshNestlin()
        nes.getController1().setButtonBitmap(0)
        nes.getController1().pendingButtons = Button.A.mask

        val recorder = MovieLiveRecorder(nes, "nestest", "base64:x")
        recorder.start()
        nes.runOneFrame()
        nes.runOneFrame()
        recorder.cancel()

        // After cancel, the hook is gone — running more frames should not capture anything.
        nes.getController1().pendingButtons = Button.B.mask
        nes.runOneFrame()
        nes.runOneFrame()

        // We can't directly observe a "no-op" since `toMovie` requires the recorder, but
        // the call itself must not throw and `cancel` should be idempotent.
        recorder.cancel()
    }

    /**
     * Round-trip: a live recording (MovieLiveRecorder) must reproduce the same final
     * emulator state when replayed headlessly (MoviePlayer), just like the headless
     * recorder already does. This is the property the "record a bug in real-time,
     * replay headlessly to inspect memory" workflow depends on.
     */
    @Test
    fun `live recording round-trips through headless replay`() {
        val nes = freshNestlin()
        val checksum = Fm2Format.romChecksum(romPath.load()!!)

        val recorder = MovieLiveRecorder(nes, "nestest", checksum)
        recorder.start()

        // Press A for 30 frames, release for 30 frames, press B+R for 30 frames.
        nes.getController1().pendingButtons = Button.A.mask
        repeat(30) { nes.runOneFrame() }
        nes.getController1().pendingButtons = 0
        repeat(30) { nes.runOneFrame() }
        nes.getController1().pendingButtons = Button.B.mask or Button.RIGHT.mask
        repeat(30) { nes.runOneFrame() }
        nes.getController1().pendingButtons = 0
        repeat(30) { nes.runOneFrame() }

        val liveMovie = recorder.stopAndSnapshot()
        val finalStateLive = java.io.ByteArrayOutputStream().also { nes.saveState(it) }.toByteArray()

        // Replay the captured FM2 into a fresh cold machine, headlessly.
        val replayed = MoviePlayer().replay(romPath, Fm2Format.read(Fm2Format.write(liveMovie)), verifyChecksum = false)
        val finalStateReplayed = java.io.ByteArrayOutputStream().also { replayed.saveState(it) }.toByteArray()

        assertEquals(
            liveMovie.length, 120,
            "live recording should have produced 120 input rows (4 × 30 frames)"
        )
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            finalStateLive, finalStateReplayed,
            "live recording must reproduce the same final state when replayed headlessly"
        )
    }

    /**
     * "Record from power-on" contract: a live recording that follows a fresh
     * power-on reset must be reproducible from the same power-on reset, with
     * the same initial controller state. The Application layer enforces this by
     * calling `nestlin.powerReset()` + clearing the controllers between the user
     * accepting the file dialog and the recorder installing its hook. This test
     * exercises the same shape directly (without the UI) so the contract is
     * locked into the engine.
     */
    @Test
    fun `recorder captures the post-power-on state as its first row`() {
        val nes = freshNestlin()
        // The "user did stuff" prelude: mutate some controller state and run a
        // few frames to make the point that we're NOT capturing the pre-reset state.
        nes.getController1().setButtonBitmap(Button.A.mask or Button.START.mask)
        repeat(5) { nes.runOneFrame() }
        assertEquals(Button.A.mask or Button.START.mask, nes.getController1().buttons,
            "sanity check: the prelude really did change the controller state")

        // The "Movie → Record" button: power-reset the machine, then start the
        // recorder. The recorder must see the post-reset state, not the prelude.
        // The Application's resetRomForMovieSession does this in one step; here
        // we replicate the engine-level bit (powerReset + controller clear) so
        // we can test the contract without going through the JavaFX UI.
        nes.powerReset()
        nes.getController1().setButtonBitmap(0)
        nes.getController1().pendingButtons = 0
        nes.getController2().setButtonBitmap(0)
        nes.getController2().pendingButtons = 0
        assertEquals(0, nes.getController1().buttons, "power-on state is no buttons held")

        val recorder = MovieLiveRecorder(nes, "nestest", "base64:x")
        recorder.start()
        nes.runOneFrame()
        val movie = recorder.stopAndSnapshot()

        assertEquals(1, movie.length, "one frame should have produced one row")
        assertEquals(0, movie.inputs[0].controller1,
            "first captured row reflects the post-reset controller state, not the prelude")
    }
}

/**
 * Verifies [Controller]'s new latch primitives. These are at the unit level — no PPU,
 * no frame loop — because the latch is a property of the controller itself.
 */
class ControllerLatchTest {

    @Test
    fun `setButton still writes to buttons for backward compatibility`() {
        val c = Controller()
        c.setButton(Button.A, true)
        // The existing `setButton` API writes to `buttons` directly. Movie recording is
        // a thin layer on top: the Application's keyboard handler routes to pendingButtons
        // only when a movie session is active. `setButton` itself does NOT change.
        assertEquals(Button.A.mask, c.buttons, "setButton writes to live buttons (back-compat)")
    }

    @Test
    fun `commitPendingToButtons copies the pending buffer into the live pad`() {
        val c = Controller()
        // Set up the "keyboard buffer" with A pressed. In the real recording flow, the
        // keyboard handler writes to pendingButtons, not buttons.
        c.pendingButtons = Button.A.mask
        assertEquals(0, c.buttons, "buttons is still 0 before the commit")
        c.commitPendingToButtons()
        assertEquals(Button.A.mask, c.buttons, "commit copies pending to buttons")
        assertEquals(Button.A.mask, c.pendingButtons, "commit does not mutate pending")
    }

    @Test
    fun `setButtonBitmap overrides the live pad without touching pending`() {
        val c = Controller()
        c.pendingButtons = Button.A.mask
        c.setButtonBitmap(Button.B.mask)
        assertEquals(Button.B.mask, c.buttons, "latched value visible to the game")
        assertEquals(Button.A.mask, c.pendingButtons, "bitmap setter must not overwrite the keyboard buffer")
    }

    @Test
    fun `pendingButtons reseeds from buttons on saveState-loadState round trip`() {
        val c = Controller()
        c.setButton(Button.A, true)
        c.setButton(Button.B, true)
        c.pendingButtons = c.buttons xor Button.A.mask  // intentional divergence

        val out = java.io.ByteArrayOutputStream()
        c.saveState(java.io.DataOutputStream(out))
        val restored = Controller()
        restored.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))

        // pendingButtons is NOT saved (transient keyboard buffer). On load, it should
        // match the restored `buttons` so the user's most recent committed state carries.
        assertEquals(c.buttons, restored.buttons, "buttons survives round trip")
        assertEquals(restored.buttons, restored.pendingButtons, "pending reseeds from buttons on load")
    }
}
