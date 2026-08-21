package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression for issue #13: "Frame buffer has no synchronisation between emulation
 * and UI threads."
 *
 * Pre-fix, [Ppu] held a single [Frame] and handed the *same* mutable reference to
 * every listener via [Ppu.frameUpdated]. A listener that outlived the emulation
 * thread — e.g. one that posted pixels to the JavaFX rendering thread, or one that
 * captured a slow `BufferedImage` — could read the buffer while the PPU was already
 * writing the next frame's first scanline into it, producing torn pixels on screen.
 *
 * The contract the fix establishes: the [Frame] reference passed to a listener points
 * to a buffer the PPU will never write to again. The PPU swaps two buffers at
 * end-of-frame, so a listener holding a reference sees a stable snapshot for as
 * long as it holds that reference.
 *
 * Two structural properties fall out of that contract:
 *
 *  1. Across N consecutive frames, listeners see at most **two distinct** [Frame]
 *     objects (the pool the PPU swaps between). If the fix is reverted, listeners
 *     will see exactly one (the single shared buffer).
 *  2. Consecutive frames pass **different** [Frame] references — the swap happens
 *     every frame, not every other frame.
 *
 * Both tests are property-based (no pixel-level assertions): they verify the
 * double-buffering shape rather than racing for an observable torn read, because a
 * forced-blank uniform backdrop cannot distinguish an old pixel from a new one.
 * The structural property is what makes the race impossible in the first place.
 */
class Issue13FrameBufferConcurrencyTest {

    private fun newPpu(): Ppu = Ppu(Memory()).apply { region = Region.NTSC }

    /**
     * Drive the PPU on a background thread until [targetFrames] end-of-frame events
     * have fired on the listener. Returns the latch the test thread awaits on, and
     * routes each [Frame] into [sink] on the producer thread (which is where
     * `frameUpdated` runs). The sink is responsible for moving the reference to a
     * thread-safe container if it intends to read it from another thread.
     */
    private fun driveFrames(ppu: Ppu, targetFrames: Int, sink: (Frame) -> Unit): CountDownLatch {
        val done = CountDownLatch(targetFrames)
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                sink(frame)
                done.countDown()
            }
        })
        // Producer thread: ticks the PPU until `done` reaches zero. We don't poll
        // on the test thread because that would synchronise with the producer and
        // hide any concurrency issues; the test asserts on the data the listener
        // captures, not on test-thread timing.
        Thread {
            while (done.count > 0) ppu.tick()
        }.also { it.isDaemon = true }.start()
        check(done.await(10, TimeUnit.SECONDS)) {
            "PPU did not produce $targetFrames frames in 10s"
        }
        return done
    }

    @Test
    fun `listeners see exactly two distinct frame buffers across many frames`() {
        val ppu = newPpu()
        val seen = mutableSetOf<Frame>()
        // ConcurrentLinkedQueue keeps the listener (producer thread) safe from the
        // test thread that drains it; we then dedupe into a set on the test thread.
        val queue = ConcurrentLinkedQueue<Frame>()
        driveFrames(ppu, targetFrames = 10) { queue.add(it) }
        while (queue.isNotEmpty()) seen.add(queue.poll())

        assertThat(
            "expected exactly two distinct Frame buffers (the double-buffer pool), got ${seen.size}",
            seen.size, equalTo(2)
        )
    }

    @Test
    fun `consecutive frames pass different frame buffer references`() {
        val ppu = newPpu()
        // For this test we drain the queue into a list on the test thread so we
        // can inspect consecutive elements. The list is built after the producer
        // has finished, so the contents are stable.
        val queue = ConcurrentLinkedQueue<Frame>()
        driveFrames(ppu, targetFrames = 8) { queue.add(it) }
        val captured = generateSequence { queue.poll() }.toList()

        // Every adjacent pair must reference different Frame objects — that proves
        // the swap happens at every endFrame, not (say) only on even frames.
        for (i in 1 until captured.size) {
            assertThat(
                "frames $i and ${i - 1} must use different Frame buffers (swap should happen every endFrame)",
                captured[i] === captured[i - 1],
                equalTo(false)
            )
        }
    }

    /**
     * Sanity check that the producer thread is actually ticking — without it, the
     * structural tests above could pass trivially if [Ppu.tick] never advanced.
     */
    @Test
    fun `producer actually produces frames`() {
        val ppu = newPpu()
        var count = 0
        val done = CountDownLatch(3)
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                count++
                done.countDown()
            }
        })
        val t = Thread {
            while (done.count > 0) ppu.tick()
        }.also { it.isDaemon = true }
        t.start()
        check(done.await(5, TimeUnit.SECONDS)) { "PPU did not produce 3 frames" }
        assertThat(count, greaterThanOrEqualTo(3))
    }
}
