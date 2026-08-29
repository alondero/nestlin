package com.github.alondero.nestlin

/**
 * The DMC-vs-OAM DMA arbitration seam (issues #228 and #294).
 *
 * On real hardware, the 2A03's DMC channel can stall the CPU on a
 * `$4014`-initiated OAM DMA's *get* phase by one CPU cycle per DMC sample
 * read. From the programmer's perspective the DMA still completes in
 * 513-514 CPU cycles *plus* an extra delay proportional to the number
 * of DMC reads that happened to coincide with the transfer.
 *
 * Nestlin's OAM DMA is driven by [com.github.alondero.nestlin.cpu.Cpu.tick]
 * as a state machine. While [dmcReadInProgress] returns `true`, the
 * CPU must PAUSE the OAM DMA's source-read half-cycle (the buffer
 * read) but KEEP ticking the write half-cycle so OAM continues to be
 * filled at the same per-tick cadence. When DMC is no longer reading,
 * the OAM DMA resumes from the same byte index and read phase.
 *
 * The default [NONE] reports no DMC in progress, so behaviour matches a
 * 2A03 with no DMC channel firing. Issue #228's DMC stalls wire their
 * `dmcReadInProgress` here so they layer on top of OAM DMA without
 * needing to know its internals.
 *
 * Lives in the top-level package so a CPU-bound test can install one
 * without importing [com.github.alondero.nestlin.cpu].
 */
interface DmaArbiter {
    /**
     * True if the DMC channel is currently reading a sample and must
     * win the bus over OAM DMA. When true, the OAM DMA's get phase
     * stalls for the current CPU cycle. Reset to false between DMC
     * reads.
     */
    val dmcReadInProgress: Boolean

    companion object {
        /** No arbitration — OAM DMA always wins (matches DMC-silent hardware). */
        val NONE: DmaArbiter = object : DmaArbiter {
            override val dmcReadInProgress: Boolean = false
        }
    }
}
