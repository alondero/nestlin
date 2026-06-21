package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression test for the OAM DMA halt timing bug.
 *
 * Background (2026-06-02, issue #88 follow-up):
 * OAM DMA ($4014 write) transfers 256 bytes from a CPU RAM page to PPU OAM.
 * Per NESdev, the CPU is suspended for 513 cycles while this happens —
 * each byte transfer is 2 PPU cycles (1 read + 1 write), 256 × 2 = 512,
 * plus 1 setup cycle on alignment.
 *
 * Before the fix, Memory's OAM DMA handler did the 256 byte copies in a
 * tight synchronous loop without halting the CPU. The CPU then immediately
 * fetched its next instruction on the same cycle, "skipping" 513 cycles.
 * This caused a per-frame drift of up to ~3% for OAM-heavy games, which
 * desynced Nestlin from Mesen2 in as few as 5 frames. Micro Machines
 * (mapper 71) was the canary.
 *
 * The fix: after the byte copy, set `cpu.workCyclesLeft = 513` so the CPU
 * spends the next 513 ticks decrementing workCyclesLeft instead of
 * fetching new instructions — exactly as real hardware does.
 *
 * The OAM content is identical to before the fix (the byte copy is still
 * synchronous), so the *result* of the DMA is unchanged; only the timing
 * is corrected.
 */
class MemoryOamDmaTest {

    @Test
    fun `OAM DMA halts the CPU for 513 cycles`() {
        // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
        // the IRQ-check path reads it on every cpu.tick().
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory)
        memory.cpu = cpu

        // Fill a CPU RAM page ($0100-$01FF) with a known pattern so we can
        // verify the bytes were copied *and* the CPU halt was applied.
        for (i in 0 until 256) {
            memory[0x0100 + i] = i.toSignedByte()
        }

        // Sanity: CPU starts ready for an instruction (workCyclesLeft == 0).
        assertThat(cpu.workCyclesLeft, equalTo(0))

        // Trigger OAM DMA from page $01.
        memory[0x4014] = 0x01.toSignedByte()

        // The CPU should now be halted for 513 cycles. Without the fix this
        // would be 0, because the synchronous byte copy wouldn't update it.
        assertThat(
            "OAM DMA must halt the CPU for 513 cycles; got ${cpu.workCyclesLeft}",
            cpu.workCyclesLeft,
            equalTo(513)
        )

        // And the OAM must still contain the copied bytes (correctness unchanged).
        for (i in 0 until 256) {
            val oamByte = memory.ppuAddressedMemory.objectAttributeMemory[i].toUnsignedInt()
            assertThat("OAM[$i]", oamByte, equalTo(i and 0xFF))
        }
    }

    @Test
    fun `multiple DMAs in a row each halt the CPU independently`() {
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory)
        memory.cpu = cpu

        // First DMA: page $02, contents 0..255.
        for (i in 0 until 256) {
            memory[0x0200 + i] = i.toSignedByte()
        }
        memory[0x4014] = 0x02.toSignedByte()
        assertThat(cpu.workCyclesLeft, equalTo(513))

        // Simulate 513 ticks of CPU "halt" (decrement each tick).
        repeat(513) { cpu.tick() }
        // After 513 ticks, the CPU is ready for a new instruction.
        assertThat(cpu.workCyclesLeft, equalTo(0))

        // Second DMA: page $03.
        for (i in 0 until 256) {
            memory[0x0300 + i] = (0xFF - i).toSignedByte()
        }
        memory[0x4014] = 0x03.toSignedByte()
        // A fresh DMA must re-halt the CPU. If the first DMA's halt "stuck"
        // (e.g. because someone wired a one-shot), this would be 0.
        assertThat(cpu.workCyclesLeft, equalTo(513))
    }
}
