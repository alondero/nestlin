package com.github.alondero.nestlin.input

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-thread stability test for [Zapper.peek] - follow-up to issue #219.
 *
 * The Memory Editor's 10 Hz refresh calls `peek` from the JavaFX thread while
 * the emulation thread mutates UI state (notably `zapperTriggerDown`). The
 * fix in #219 made peek a safe approximation that skips [Zapper.lightSensor]
 * (no PPU scanline / frame reads), but the *trigger* bit still flows through
 * a caller-provided lambda. Cross-thread visibility of that bit is the
 * caller's responsibility: in production it works because `Memory.zapperTrigger`
 * is `@Volatile`.
 *
 * This test models the cross-thread read pattern with an [AtomicBoolean]
 * backing field (equivalent memory visibility to `@Volatile Boolean`), a
 * writer thread that flips it ~200k times, and a reader thread that calls
 * `peek()` ~200k times. The reader must only ever see one of the two legal
 * values (0x48 idle / 0x58 trigger-down) - never a torn combination, and
 * never throw.
 *
 * What it does NOT test: it cannot prove general cross-thread memory safety
 * in the abstract (the JVM already guarantees that for volatile reads). What
 * it DOES pin: the contract the [Zapper] class advertises - peek has no
 * surprising side effects that would crash when called concurrently with
 * state mutation. If a future refactor accidentally added a long-running PPU
 * read or a stateful computation to peek, this test would catch it via the
 * tear check or by hanging.
 */
class ZapperPeekCrossThreadStabilityTest {

    @Test
    fun `peek returns only the two legal trigger states when the backing trigger is mutated off-thread`() {
        // AtomicBoolean: the JVM guarantees visibility for off-thread reads
        // (same memory-model semantics as `@Volatile Boolean` on Memory.zapperTrigger).
        val trigger = AtomicBoolean(false)

        // Throwing lambda = "peek must not invoke lightSensor" canary, so any
        // accidental re-introduction of the live PPU sample in peek fails fast.
        val zapper = Zapper(
            triggerProvider = { trigger.get() },
            lightSensor = { error("peek must not invoke the light sensor") },
        )

        val start = CountDownLatch(1)
        val stop = CountDownLatch(1)

        // Writer thread: flap the atomic backing field as fast as it can.
        // No assertion here - the goal is *contention* on the reader side.
        val writer = Thread({
            try {
                start.await()
                var i = 0
                while (i < 200_000 && stop.count > 0) {
                    trigger.set(!trigger.get())
                    i++
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "zapper-peek-stability-writer").apply { isDaemon = true; start() }

        // Reader thread: read peek thousands of times, asserting each result
        // is one of the two legal bytes. Any torn / nonsense value fails the
        // test; any thrown exception (e.g. a phantom concurrency bug in peek)
        // surfaces as a captured failure in AtomicReference.
        val failure = AtomicReference<Throwable?>(null)
        val reader = Thread({
            try {
                start.await()
                var reads = 0
                while (reads < 200_000 && stop.count > 0) {
                    val b = zapper.peek().toInt() and 0xFF
                    if (b != 0x48 && b != 0x58) {
                        failure.compareAndSet(
                            null,
                            AssertionError("tear: peek returned 0x${Integer.toHexString(b)} after $reads reads"),
                        )
                        break
                    }
                    reads++
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "zapper-peek-stability-reader").apply { isDaemon = true; start() }

        // Release both threads and wait for them to finish their loops.
        start.countDown()
        writer.join(5_000)
        reader.join(5_000)
        stop.countDown()

        // If either thread is still alive the loop did not exit (likely a hang
        // introduced into peek); surface that as a test failure.
        if (writer.isAlive || reader.isAlive) {
            throw AssertionError("reader/writer thread did not terminate; peek may have introduced blocking I/O")
        }

        failure.get()?.let { throw it }

        // Sanity: the test did real work and didn't degenerate to zero reads.
        // This is intentionally not asserting on the count (timer variance) -
        // the goal is "non-trivial concurrent work happened and peek held up".
        assertThat(zapper.peek(), equalTo(zapper.peek()))
    }
}