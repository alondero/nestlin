package com.github.alondero.nestlin.gamepak

/**
 * MMC3-style scanline IRQ counter.
 *
 * Implements the MMC3 IRQ counter logic per NESdev wiki:
 * - On each rising A12 edge, the counter is clocked
 * - If counter==0 or reload flag is set: counter = latch, reload = false (no decrement)
 * - Otherwise: counter--
 * - If counter==0 and enabled: IRQ is pending
 *
 * The A12 edge detection itself (with 3 M2 cycle requirement) lives in PpuInternalMemory.
 * This class only implements the counter semantics.
 */
class ScanlineCounter {
    private var irqLatch = 0
    private var irqReload = false
    private var irqEnabled = false
    private var irqCounter = 0
    private var irqPending = false

    /**
     * Write to $C000 — IRQ latch value.
     * The counter will be reloaded with this value at the next A12 rising edge.
     */
    fun writeLatch(value: Int) {
        irqLatch = value and 0xFF
    }

    /**
     * Write to $C001 — IRQ reload trigger.
     * Sets the reload flag; actual reload happens at next A12 rising edge.
     */
    fun triggerReload() {
        irqReload = true
    }

    /**
     * Write to $E000 — IRQ disable (even address) or enable (odd address).
     */
    fun setEnabled(enabled: Boolean) {
        irqEnabled = enabled
        if (!enabled) {
            irqPending = false
        }
    }

    /**
     * Clock the counter on an A12 rising edge.
     * Called by the mapper's notifyA12Edge() implementation.
     */
    fun clock() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch
            irqReload = false
        } else {
            irqCounter--
        }
        if (irqCounter == 0 && irqEnabled) {
            irqPending = true
        }
    }

    fun isIrqPending(): Boolean = irqPending

    fun acknowledgeIrq() {
        irqPending = false
    }

    // State accessors for snapshot/debugging
    fun irqLatchValue(): Int = irqLatch
    fun irqCounterValue(): Int = irqCounter
    fun isIrqEnabled(): Boolean = irqEnabled
}
