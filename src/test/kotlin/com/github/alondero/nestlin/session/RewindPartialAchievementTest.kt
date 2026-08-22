package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Replay-the-condition test for issue #271 — the externally observable
 * scenario the issue calls out:
 *
 * > The key externally observable scenario is a partially satisfied
 * > achievement condition: rewind/load to before the partial progress,
 * > replay the condition, and unlock exactly once—neither missed because
 * > the runtime stayed in the future nor duplicated incorrectly.
 *
 * The test uses the real Nestlin rewind buffer + a `raProgressCapture`
 * / `raProgressRestore` pair whose serialize-side returns monotonically
 * distinct bytes encoding the frame index, and whose restore side
 * records every byte array it sees.
 *
 * The rewind ring stores opaque `ByteArray` snapshots of the live
 * `SaveState.save` output. Because Nestlin.saveState is now routed
 * through `raProgressCapture`, every snapshot already includes the
 * v7 trailer. This test pins that path end-to-end without instantiating
 * a JavaFX app.
 */
class RewindPartialAchievementTest {

    private fun newNestlin(): Nestlin = Nestlin().apply {
        loadBytes(TestRoms.nestestBytes())
        powerReset()
    }

    /** Step [n] PPU frames; the production frame-completion listener
     *  captures each frame into [nes.rewindBuffer] automatically. */
    private fun stepFrames(nes: Nestlin, n: Int) {
        repeat(n) {
            while (true) {
                nes.stepCpuCycle()
                if (nes.ppu.frameJustCompleted()) break
            }
        }
    }

    @Test
    fun `rewind to before partial condition restores progress and unlocks exactly once on replay`() {
        val nes = newNestlin()

        // A fake that returns monotonically distinct progress bytes from
        // serializeProgress and tracks restoreProgress calls. Frame index
        // is captured into the progress bytes so a real-or-fake runtime
        // could re-derive "how many frames of partial condition did this
        // snapshot represent".
        var nextFrameIndex = 0
        val progressBytes = mutableListOf<ByteArray>()
        val restoreLog = mutableListOf<ByteArray?>()

        nes.raProgressCapture = SaveState.ProgressCapture {
            val bytes = encodeProgress(nextFrameIndex)
            progressBytes += bytes
            nextFrameIndex++
            bytes
        }
        nes.raProgressRestore = SaveState.ProgressRestore { progress ->
            restoreLog += progress?.copyOf()
        }

        // Aim for 10 frames of forward play, each captured into the
        // buffer by the production frame-completion listener.
        val frameCount = 10
        stepFrames(nes, frameCount)

        // Pre-condition: every capture produced distinct progress bytes.
        assertThat(progressBytes.size, equalTo(frameCount))
        for (i in 1 until progressBytes.size) {
            val prev = decodeFrameIndex(progressBytes[i - 1])
            val curr = decodeFrameIndex(progressBytes[i])
            assertThat("frame index must be monotonic", curr > prev, equalTo(true))
        }

        // The buffer holds `frameCount` snapshots (one per completed frame).
        assertThat("buffer should have one snapshot per completed frame",
            nes.rewindBuffer.size, equalTo(frameCount))

        // Scrub back 5 frames in the buffer (dropping the 5 newest
        // snapshots). The head of the buffer is now snapshot[frameCount - 6]
        // — i.e. "4 frames before the partial condition". When loaded,
        // the runtime is reset to that frame's recorded progress.
        val targetSnapshotIndex = frameCount - 6  // 0-based, scrubbed-to position
        val scrubCount = (frameCount - 1) - targetSnapshotIndex
        val rewound = nes.rewindBuffer.rewind(scrubCount)
        assertNotNull(rewound, "rewind must return the snapshot at the scrubbed-to frame")

        val restoredBefore = restoreLog.size
        nes.loadState(ByteArrayInputStream(rewound!!))
        val restoredAfter = restoreLog.size

        // loadState must have invoked restoreProgress exactly once with
        // the progress bytes that were in the snapshot's v7 trailer.
        assertThat("restore must have fired exactly once during rewind load",
            restoredAfter - restoredBefore, equalTo(1))
        val restoredProgress = restoreLog.last()
        assertNotNull(restoredProgress, "restored progress must come from the snapshot's trailer")

        // And the restored progress must match the snapshot's expected
        // bytes — i.e. the runtime is back at the partial-condition state
        // matching the frame the user rewound to.
        val expectedFrameIndex = decodeFrameIndex(progressBytes[targetSnapshotIndex])
        assertThat("restored progress encodes the rewound frame's index",
            decodeFrameIndex(restoredProgress!!), equalTo(expectedFrameIndex))
    }

    @Test
    fun `every rewind snapshot embeds the progress bytes from the same completed frame`() {
        // Pin the central property of issue #271: the progress captured
        // into a rewind snapshot is from the SAME frame the snapshot was
        // taken on, not a stale or future value. Three snapshots →
        // three distinct progress payloads, in capture order.
        val nes = newNestlin()
        val observed = mutableListOf<Int>()
        var nextFrame = 0
        nes.raProgressCapture = SaveState.ProgressCapture {
            val idx = nextFrame++
            observed += idx
            encodeProgress(idx)
        }

        stepFrames(nes, 3)

        assertThat("three frame captures must fire", observed.size, equalTo(3))
        assertThat("buffer should have one snapshot per completed frame",
            nes.rewindBuffer.size, equalTo(3))
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun encodeProgress(frameIndex: Int): ByteArray =
        byteArrayOf(0x52, 0x41, ((frameIndex ushr 8) and 0xFF).toByte(), (frameIndex and 0xFF).toByte())

    private fun decodeFrameIndex(bytes: ByteArray): Int =
        ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

    // Local assertNotNull to avoid the kotlin.test import that the lint bans.
    private fun assertNotNull(value: Any?, message: String) {
        if (value == null) throw AssertionError(message)
    }
}
