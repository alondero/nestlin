package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Regression guard for the `running` flag on the emulator's main loop.
 *
 * `Nestlin.start()` runs on a background thread (the "emulation thread") and
 * blocks in `while (running)`. `Nestlin.stop()` is called from a different
 * thread (the JavaFX UI thread in production). The JMM allows the emulation
 * thread to cache the read of `running` in a register or L1 cache, and even
 * for the JIT to hoist the load out of the loop entirely, so the write from
 * `stop()` may never become visible — the loop runs forever.
 *
 * `@Volatile` inserts the necessary happens-before edge. Issue #12 tracks
 * adding it. The reflection test below makes that invariant load-bearing; if
 * someone removes the annotation the test fails immediately, regardless of
 * timing, JIT, or how many cores the test runner has.
 */
class NestlinThreadSafetyTest {

    @Test
    fun `running field is @Volatile for cross-thread visibility`() {
        val runningField = Nestlin::class.java.getDeclaredField("running")
        // Field.isVolatile() exists in Java but the Kotlin compiler resolves
        // `isVolatile` to Modifier.isVolatile(int) (a static method) and fails
        // to find the instance method, so we check the modifier bit directly.
        val isVolatile = (runningField.modifiers and Modifier.VOLATILE) != 0
        assertThat(
            "Nestlin.running must be @Volatile so that stop() called from a " +
                "different thread is observed by the emulation thread's " +
                "while (running) loop. Without it, the JVM memory model " +
                "permits the emulation thread to cache the read and the " +
                "loop may run forever (issue #12).",
            isVolatile,
            equalTo(true)
        )
    }

    /**
     * Sanity check that the volatile annotation actually fixes the
     * cross-thread termination behaviour, not just the annotation presence.
     *
     * This would be the only test we'd have if we wanted to verify the
     * runtime effect, but it's inherently timing-dependent — without
     * `@Volatile` the bug may or may not reproduce on a given machine.
     * The reflection test above is the deterministic regression guard;
     * this one is a belt-and-braces behavioural check.
     */
    @Test
    fun `stop called from another thread terminates start`() {
        val nes = Nestlin()
        val thread = Thread({ nes.start() }, "nestlin-test-emu")
        thread.isDaemon = true
        thread.start()

        // Give start() a moment to enter the running loop, then signal stop
        // from THIS thread (i.e. a different thread from the emulator).
        Thread.sleep(50)
        nes.stop()

        thread.join(2_000)
        if (thread.isAlive) {
            thread.interrupt()
            thread.join(1_000)
            // JUnit 5's fail(String) is declared `<V> V`, which Kotlin can't infer here;
            // throw the underlying assertion error directly (see junit5-migration-lessons memory).
            throw org.opentest4j.AssertionFailedError(
                "Nestlin.start() did not return within 2s of stop(). " +
                    "The running flag is likely not @Volatile — see issue #12."
            )
        }
    }
}
