package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.testutil.testRom
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * The diagnostic counters `Cpu.nmiCount` / `Cpu.irqCount` increment exactly once
 * per *dispatched* interrupt — at the point the vector is taken, not when the NMI
 * is merely armed/pending. The compare/DivergenceLocalizer harness relies on
 * per-frame deltas of these to compare NMI/IRQ-per-frame against Mesen2.
 *
 * Issue #190: these tests now drive the interrupt via a `FakeInterruptController`
 * instead of poking `cpu.memory.ppuAddressedMemory.nmiOccurred = true`. The
 * behaviour they assert is unchanged — only the seam changed.
 */
class InterruptCounterTest {

    @Test
    fun nmiCountIncrementsOncePerDispatchedNmi() {
        // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
        // the IRQ-check path reads it on every tick.
        //
        // Issue #190: inject a FakeInterruptController to drive the NMI through
        // the seam rather than through PPU state.
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply {
            currentGame = GamePak(spinLoopRom())
            reset()
        }
        assertThat(cpu.nmiCount, equalTo(0))
        assertThat(cpu.irqCount, equalTo(0))

        // Reset vector points at the JMP-to-self spin loop at $C000; park there.
        repeat(6) { cpu.tick() }

        // Arm an NMI edge via the controller seam. The CPU is idle (parked in
        // a spin loop) so the 1-instruction latency is skipped — a single tick
        // both arms and dispatches.
        fakeController.armNmi()

        cpu.tick() // dispatches (idle park skips the 1-instruction latency)

        assertThat(cpu.nmiCount, equalTo(1))
        assertThat(cpu.irqCount, equalTo(0))
        assertThat(cpu.registers.programCounter, equalTo(0xC010.toSignedShort()))
        // The fake should also have observed the acknowledgement (proves the
        // Cpu called back into the controller's acknowledge(NMI) seam).
        assertThat(fakeController.acknowledgedNmiCount, equalTo(1))

        // The vector was taken once; ticking through the 7 interrupt cycles (and the
        // handler) must not bump the counter again while no new NMI is pending.
        repeat(20) { cpu.tick() }
        assertThat(cpu.nmiCount, equalTo(1))
    }

    @Test
    fun armedButSuppressedNmiDoesNotCount() {
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply {
            reset()
            registers.programCounter = 0x0000.toSignedShort()
            // A run of NOPs so there is always an in-flight instruction (no idle park),
            // which means the NMI is armed first and dispatches one instruction later.
            for (addr in 0x0000..0x000F) memory[addr] = 0xEA.toSignedByte()
        }

        // Arm an NMI: it gets ARMED at the next instruction boundary...
        fakeController.armNmi()
        cpu.tick() // boundary: arms the NMI, executes one more instruction
        // Issue #190: assert the controller's internal `nmiArmed` directly
        // (no longer a `cpu.nmiArmed` property — the invariant now lives
        // inside the controller, not the CPU).
        assertThat(fakeController.nmiArmed, equalTo(true))
        assertThat(cpu.nmiCount, equalTo(0))

        // ...but the latch is cleared within the latency window (as a $2002 vblank
        // poll would do), so the NMI is suppressed and must never be counted.
        fakeController.clearNmi()
        repeat(10) { cpu.tick() }
        assertThat(cpu.nmiCount, equalTo(0))
        assertThat("controller must have disarmed on clearNmi", fakeController.nmiArmed, equalTo(false))
    }

    /**
     * Minimal 16KB NROM: JMP-to-self at $C000, RTI at $C010,
     * reset vector -> $C000, NMI vector -> $C010. (Same shape as IdleLoopTest.)
     */
    private fun spinLoopRom(): ByteArray = testRom {
        prgKb = 16
        chrKb = 0
        prg[0x0000] = 0x4C.toByte() // JMP $C000
        prg[0x0001] = 0x00.toByte()
        prg[0x0002] = 0xC0.toByte()
        prg[0x0010] = 0x40.toByte() // RTI
        prg[0x3FFA] = 0x10.toByte() // NMI vector -> $C010
        prg[0x3FFB] = 0xC0.toByte()
        resetVector(0xC000)
    }
}