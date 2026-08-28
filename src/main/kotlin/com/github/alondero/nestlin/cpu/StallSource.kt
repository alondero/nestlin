package com.github.alondero.nestlin.cpu

/**
 * The seam that lets [com.github.alondero.nestlin.Memory] request a CPU
 * stall or DMA transfer without reaching back into
 * the CPU through a `var cpu: Cpu?` back-reference. See issue #190 /
 * ADR-0003.
 *
 * The CPU implements [StallSource] with explicit resumable state. Each
 * [Cpu.tick] performs at most one held, read, or write bus action, so an
 * instruction that starts a DMA transfer cannot lose the transfer when its
 * final write completes.
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
     * Stall the CPU for [cycles] cycles. Subsequent ticks perform held bus
     * reads rather than fetching instructions. Calling `stallFor(N)` while
     * already stalled resets the remaining counter to `N`.
     */
    fun stallFor(cycles: Int)

    /** Begin a resumable OAM DMA transfer from the selected 256-byte page. */
    fun startOamDma(page: Int)
}
