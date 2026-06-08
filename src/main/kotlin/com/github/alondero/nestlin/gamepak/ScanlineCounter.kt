package com.github.alondero.nestlin.gamepak

import java.io.DataInput
import java.io.DataOutput

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
 *
 * Tengen RAMBO-1 (Mapper 64) has a documented quirk: the reload value is
 * `(latch + 1) and 0xFF` instead of just `latch`. Per the nesdev wiki,
 * "Klax still requires +1 to run perfectly" because of this. Toggle via
 * [setReloadPlusOne] — off by default (standard MMC3 behaviour).
 */
class ScanlineCounter {
    private var irqLatch = 0
    private var irqReload = false
    private var irqEnabled = false
    private var irqCounter = 0
    private var irqPending = false
    private var reloadPlusOne = false
    // Tengen RAMBO-1 (Mapper 64) supports two IRQ clock modes per nesdev:
    // scanline (A12-clocked, the MMC3 default) and CPU cycle (clocked once
    // per `clock()` call, intended to be wired to `tickCpuCycle()`). The
    // mode is selected by `$C001` bit 0. Off by default.
    private var cpuCycleMode = false

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
     * Toggle the Tengen RAMBO-1 reload behaviour. Off by default (standard
     * MMC3 behaviour). When on, [clock] uses RAMBO-1's two-path reload
     * (explicit reload = latch+1 or latch+2; auto-reload = latch+1), matching
     * Mesen2. The rest of the IRQ machinery (enable, acknowledge, pending) is
     * identical between MMC3 and RAMBO-1.
     */
    fun setReloadPlusOne(enabled: Boolean) {
        reloadPlusOne = enabled
    }

    /**
     * Toggle the Tengen RAMBO-1 CPU-cycle IRQ clock mode. Off by default
     * (scanline / A12-clocked, standard MMC3 behaviour). When on, the
     * counter clocks once per `clock()` call regardless of the PPU A12
     * edge — the mapper is expected to call `clock()` from its
     * `tickCpuCycle()` hook. The mode is selected at runtime by `$C001`
     * bit 0 (set → CPU cycle, clear → A12).
     */
    fun setCpuCycleMode(enabled: Boolean) {
        cpuCycleMode = enabled
    }

    /**
     * True if the counter is being clocked from CPU cycles rather than
     * PPU A12 edges. Exposed for snapshot/debugging — Mapper 64 uses
     * `tickCpuCycle()` to gate `clock()` calls based on this flag.
     */
    fun isCpuCycleMode(): Boolean = cpuCycleMode

    /**
     * Clock the counter on an A12 rising edge.
     * Called by the mapper's notifyA12Edge() implementation.
     */
    fun clock() {
        if (reloadPlusOne) {
            // Tengen RAMBO-1, modelled exactly on Mesen2's `Rambo1.h` (the
            // reference oracle). Two distinct reload paths, unlike MMC3:
            //   - explicit reload (after a $C001 write): counter =
            //     (latch <= 1) ? latch + 1 : latch + 2.
            //   - auto-reload when the counter underflows to 0: counter =
            //     latch + 1.
            //
            // CRITICAL: the IRQ fires ONLY when the counter is *decremented*
            // to zero — never when a reload sets it to zero. Klax disarms its
            // single-per-frame split by reloading a large latch (0xFE), which
            // wraps to (0xFE + 2) & 0xFF = 0x00. Firing on that reload-to-zero
            // produced a spurious second IRQ that corrupted every CHR bank
            // below the split (the "garbage 0s" glitch). Mesen2 puts the
            // trigger test inside the decrement branch for exactly this reason.
            when {
                irqReload -> {
                    irqCounter = (if (irqLatch <= 1) irqLatch + 1 else irqLatch + 2) and 0xFF
                    irqReload = false
                }
                irqCounter == 0 -> irqCounter = (irqLatch + 1) and 0xFF
                else -> {
                    irqCounter = (irqCounter - 1) and 0xFF
                    if (irqCounter == 0 && irqEnabled) irqPending = true
                }
            }
        } else {
            // Standard MMC3: fires on reload-to-zero or decrement-to-zero.
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
    }

    fun isIrqPending(): Boolean = irqPending

    fun acknowledgeIrq() {
        irqPending = false
    }

    // State accessors for snapshot/debugging
    fun irqLatchValue(): Int = irqLatch
    fun irqCounterValue(): Int = irqCounter
    fun isIrqEnabled(): Boolean = irqEnabled

    fun saveState(out: DataOutput) {
        out.writeInt(irqLatch)
        out.writeBoolean(irqReload)
        out.writeBoolean(irqEnabled)
        out.writeInt(irqCounter)
        out.writeBoolean(irqPending)
    }

    fun loadState(input: DataInput) {
        irqLatch = input.readInt()
        irqReload = input.readBoolean()
        irqEnabled = input.readBoolean()
        irqCounter = input.readInt()
        irqPending = input.readBoolean()
    }
}
