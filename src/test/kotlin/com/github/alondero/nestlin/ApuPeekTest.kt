package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [Apu.peekRegisterRead] — side-effect-free APU register reads for the Memory
 * Editor (issue #168). The only APU register with a read side effect is $4015,
 * whose real read ([Apu.handleRegisterRead] -> handleStatusRead) acknowledges the
 * frame-counter and DMC IRQs. peek must report the same status but leave the IRQ
 * flags pending, so a debug viewer can never swallow an interrupt the game wants.
 */
class ApuPeekTest {

    private fun apuWithFrameIrqPending(): Apu {
        // Use the factory (issue #22): Memory and Apu are wired together so that
        // memory.apu is non-null. Test scenarios below call apu.* directly anyway,
        // but keeping the factory path means a future change to register dispatch
        // through Memory won't break this helper.
        val (_, apu) = Memory.createWithApu()
        // 4-step sequence, IRQ enabled (bit 7 = 0 mode, bit 6 = 0 inhibit).
        apu.handleRegisterWrite(0x17, 0)
        // Run the frame counter until it raises the frame IRQ (fires once per
        // ~14,915-cycle NTSC sequence; the cap is a generous safety net).
        var ticks = 0
        while (!apu.isIrqPending() && ticks < 50_000) {
            apu.tick()
            ticks++
        }
        return apu
    }

    @Test
    fun `peek of status does not clear pending IRQ flags`() {
        val apu = apuWithFrameIrqPending()
        assertThat("frame IRQ should be pending after running the frame counter",
            apu.isIrqPending(), equalTo(true))

        val peeked = apu.peekRegisterRead(0x15)

        // The peeked status reports the frame IRQ (bit 6)...
        assertThat(peeked.toInt() and 0x40, equalTo(0x40))
        // ...and the IRQ is STILL pending — peek did not acknowledge it.
        assertThat(apu.isIrqPending(), equalTo(true))
    }

    @Test
    fun `real status read clears the IRQ that peek preserved`() {
        val apu = apuWithFrameIrqPending()
        apu.peekRegisterRead(0x15) // does not clear
        assertThat(apu.isIrqPending(), equalTo(true))

        apu.handleRegisterRead(0x15) // the real read acknowledges it
        assertThat(apu.isIrqPending(), equalTo(false))
    }

    @Test
    fun `peek of a non-status register returns the backing byte`() {
        val (_, apu) = Memory.createWithApu()
        apu.handleRegisterWrite(0x03, 0xAB.toByte()) // pulse1 length/timer-high

        assertThat(apu.peekRegisterRead(0x03), equalTo(0xAB.toByte()))
    }
}
