package com.github.alondero.nestlin.cpu

/**
 * The seam that lets [com.github.alondero.nestlin.Memory] request a CPU
 * stall (e.g. for the 513-cycle OAM DMA halt) without reaching back into
 * the CPU through a `var cpu: Cpu?` back-reference. See issue #190 /
 * ADR-0003.
 *
 * The CPU implements [StallSource] by setting its internal `workCyclesLeft`
 * to [cycles]; the scheduler in [Cpu.tick] decrements that counter every
 * tick, suspending instruction fetch — exactly as a real CPU is stalled
 * for the duration of an OAM DMA.
 *
 * Why an interface rather than a `Cpu?` back-reference: the previous
 * `Memory.cpu: Cpu?` field existed SOLELY to set `workCyclesLeft = 513`
 * from inside the `$4014` handler, coupling Memory's source code to the
 * CPU class. The interface narrows that coupling to a single capability
 * ("you may stall this CPU for N cycles") — Memory no longer imports or
 * knows about `Cpu`'s internal scheduling field.
 */
interface StallSource {
    /**
     * Stall the CPU for [cycles] cycles. Subsequent ticks decrement a
     * counter rather than fetching instructions. Idempotent — calling
     * `stallFor(N)` while the CPU is already mid-stall resets the
     * remaining counter to `N` (matches OAM DMA semantics: each new
     * `$4014` write restarts the 513-cycle halt, not extends it).
     */
    fun stallFor(cycles: Int)
}