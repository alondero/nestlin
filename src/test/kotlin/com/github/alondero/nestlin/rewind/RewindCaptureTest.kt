package com.github.alondero.nestlin.rewind

import com.github.alondero.nestlin.Nestlin
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Integration tests for the rewind capture path (issue #52) wired through the real
 * frame-completion listener and the real savestate serializer. The pure ring mechanics
 * live in [RewindBufferTest]; this proves Nestlin captures one valid, restartable snapshot
 * per frame and clears the ring on the per-ROM lifecycle events.
 */
class RewindCaptureTest {

    private fun newNestlinAtReset(): Nestlin {
        val nes = Nestlin()
        nes.load(Paths.get("testroms/nestest.nes"))
        nes.powerReset()
        return nes
    }

    /** Advance the emulator by exactly [n] full PPU frames, firing the capture listener each. */
    private fun runFrames(nes: Nestlin, n: Int) {
        repeat(n) {
            while (true) {
                nes.stepCpuCycle()
                if (nes.ppu.frameJustCompleted()) break
            }
        }
    }

    private fun snapshot(nes: Nestlin): ByteArray =
        ByteArrayOutputStream().also { nes.saveState(it) }.toByteArray()

    @Test
    fun `one snapshot is captured per frame`() {
        val nes = newNestlinAtReset()
        assertThat("buffer starts empty after reset", nes.rewindBufferSize(), equalTo(0))
        runFrames(nes, 25)
        assertThat("each completed frame captures exactly one snapshot",
            nes.rewindBufferSize(), equalTo(25))
    }

    @Test
    fun `disabling rewind suppresses capture entirely`() {
        val nes = newNestlinAtReset()
        nes.config.rewindEnabled = false
        runFrames(nes, 25)
        assertThat("no snapshots captured while rewind is disabled",
            nes.rewindBufferSize(), equalTo(0))
    }

    @Test
    fun `power reset clears the rewind buffer`() {
        val nes = newNestlinAtReset()
        runFrames(nes, 25)
        assertTrue(nes.rewindBufferSize() > 0)
        nes.powerReset()
        assertThat("a power-cycle drops the prior boot's history",
            nes.rewindBufferSize(), equalTo(0))
    }

    @Test
    fun `loading a ROM clears the rewind buffer`() {
        val nes = newNestlinAtReset()
        runFrames(nes, 25)
        assertTrue(nes.rewindBufferSize() > 0)
        nes.load(Paths.get("testroms/nestest.nes"))
        assertThat("swapping ROMs drops the previous ROM's history",
            nes.rewindBufferSize(), equalTo(0))
    }

    @Test
    fun `a snapshot pulled from the buffer is a valid restartable state`() {
        // The strong property: a buffered snapshot, when loaded, deterministically continues
        // the same as the original timeline did from that point. If the capture path missed any
        // state, the two forward runs would diverge.
        val nes = newNestlinAtReset()
        runFrames(nes, 20)

        // Scrub back ~5 frames and load that snapshot through the real loader.
        val rewound = nes.rewindBuffer.rewind(5)!!
        nes.loadState(ByteArrayInputStream(rewound))

        // Path A: run 7 frames forward from the rewound state.
        runFrames(nes, 7)
        val pathA = snapshot(nes)

        // Path B: reload the exact same snapshot, run the same 7 frames.
        nes.loadState(ByteArrayInputStream(rewound))
        runFrames(nes, 7)
        val pathB = snapshot(nes)

        assertArrayEquals(pathA, pathB,
            "Running forward from a buffered rewind snapshot must be deterministic")
    }
}
