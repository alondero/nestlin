package com.github.alondero.nestlin.input

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression for issue #219 — `Zapper.peek` must be safe to call from the
 * Memory Editor's 10 Hz refresh, which runs on the JavaFX thread while the
 * emulation thread writes the PPU's `scanline` and `frame`.
 *
 * The fix is to make peek a deliberate **approximation** of [Zapper.read]:
 * the light bit is hard-coded to 'dark' (D3 set), so [Zapper.lightSensor] is
 * never invoked and the peek path does not reach
 * [com.github.alondero.nestlin.ppu.Ppu.aimBrightness] (which reads the live
 * PPU frame). The trigger bit still reads through the caller-provided
 * lambda — the caller's responsibility for cross-thread visibility is
 * satisfied in production by [com.github.alondero.nestlin.Memory.zapperTrigger]'s
 * `@Volatile` field.
 *
 * The single test below pins the load-bearing guarantee: a `lightSensor`
 * that throws if invoked is the canary for "peek must not sample the live
 * PPU". A future refactor that re-introduced a live light sample from peek
 * would fail this test even if all the byte-math in [ZapperTest] still
 * happened to pass.
 */
class ZapperPeekEditorSafetyTest {

    @Test
    fun `peek must not invoke the light sensor`() {
        // Throwing lambdas are the strongest possible "must not call" pin: any
        // invocation during peek is a test failure with the lambda's message.
        val zapper = Zapper(
            triggerProvider = { false },
            lightSensor = { error("peek must not invoke the light sensor") },
        )

        // Port-2 Zapper — the in-game shape — must skip lightSensor.
        assertThat(zapper.peek(), equalTo(0x48.toByte()))

        // Port-1 Zapper takes the else branch (reads 0); also must skip lightSensor.
        val port1 = Zapper(
            triggerProvider = { false },
            lightSensor = { error("peek must not invoke the light sensor (port 1)") },
            isPort2 = false,
        )
        assertThat(port1.peek(), equalTo(0.toByte()))
    }
}