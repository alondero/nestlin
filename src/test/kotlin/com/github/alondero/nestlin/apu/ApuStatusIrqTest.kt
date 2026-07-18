package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * `$4015` status-register IRQ acknowledge semantics (nesdev APU $4015).
 *
 * The two IRQ flags are acknowledged on DIFFERENT accesses:
 *  - The **frame** interrupt flag is cleared by *reading* `$4015`.
 *  - The **DMC** interrupt flag is cleared by *writing* `$4015` — reading
 *    `$4015` must leave it untouched.
 *
 * The old code had these swapped for DMC: it cleared the DMC flag on read and
 * never cleared it on write, so a game whose IRQ handler reads `$4015` to
 * identify the source would silently ack (and lose) a DMC IRQ, while a write
 * that is supposed to ack it did nothing.
 */
class ApuStatusIrqTest {

    private fun newApu(): Apu = Memory.createWithApu().second

    @Test
    fun `reading $4015 does NOT clear the DMC interrupt flag`() {
        val apu = newApu()
        apu.dmc.irqEnabled = true
        apu.dmc.irqPending = true

        val status = apu.handleRegisterRead(0x15)
        assertTrue(status.isBitSet(7), "DMC IRQ must be reported in status bit 7")
        assertTrue(apu.dmc.irqPending, "reading \$4015 must NOT clear the DMC IRQ")
    }

    @Test
    fun `writing $4015 clears the DMC interrupt flag even when enabling the channel`() {
        val apu = newApu()
        apu.dmc.irqEnabled = true
        apu.dmc.irqPending = true

        // bit 4 set = enable DMC. The clear must come from the write itself, not
        // from the channel-disable path (which would mask the bug).
        apu.handleRegisterWrite(0x15, 0x10.toSignedByte())
        assertFalse(apu.dmc.irqPending, "writing \$4015 must clear the DMC IRQ")
    }

    @Test
    fun `reading $4015 still clears the frame interrupt flag`() {
        val apu = newApu()
        // Drive the 4-step sequencer to its frame-IRQ boundary (cycle 29829).
        repeat(29829) { apu.tick() }
        assertTrue(apu.isIrqPending(), "frame IRQ should be pending at the sequence end")

        val status = apu.handleRegisterRead(0x15)
        assertTrue(status.isBitSet(6), "frame IRQ must be reported in status bit 6")
        assertFalse(apu.isIrqPending(), "reading \$4015 must clear the frame IRQ")
    }
}
