package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.ppu.PpuAddressedMemory
import java.io.DataInput
import java.io.DataOutput

/**
 * What kind of interrupt [InterruptController.pendingInterrupt] is reporting.
 *
 * NMI always wins over IRQ when both are pending — see the ordering rules on
 * [InterruptController].
 */
enum class InterruptKind { NMI, IRQ }

/**
 * Adapter contract for the PPU's NMI edge source.
 *
 * The PPU owns the actual `nmiOccurred` latch and the PPUCTRL bit-7
 * "generate NMI" gate. This adapter lets [InterruptController] query the
 * combined condition (vblank set AND generateNmi) without knowing it's
 * PPU state, and lets tests substitute a stub without a real PPU.
 *
 * Production: [PpuAddressedMemory]. Tests: a fake implementing this interface.
 */
interface NmiSource {
    /** True if an NMI edge is pending: PPU vblank set AND PPUCTRL bit 7 set. */
    fun nmiPending(): Boolean
    /** Acknowledge the NMI edge — clears the latch. NESdev: edge-triggered, not level. */
    fun acknowledgeNmi()
}

/**
 * Adapter contract for an IRQ source. The mapper and the APU both implement
 * this conceptually; the controller aggregates a list of them.
 *
 * [acknowledgeIrq] is a default no-op because the APU self-clears its IRQ
 * on `$4015` read (per NESdev), not on CPU dispatch — only mappers that have
 * a distinct acknowledge write override it. The controller calls every
 * source's [acknowledgeIrq] on dispatch; the no-op default preserves APU
 * semantics unchanged. The mapper (`MapperIrqSource`) explicitly delegates
 * to `mapper.acknowledgeIrq()`.
 */
interface IrqSource {
    fun irqPending(): Boolean
    fun acknowledgeIrq() {}
}

/**
 * The seam between interrupt producers (PPU / APU / mapper) and the CPU
 * consumer. See issue #190 / ADR-0003.
 *
 * **Why a seam.** The previous implementation had three encoding points for
 * the same concept:
 *  - `Cpu.checkAndHandleNmi` reached into `memory.ppuAddressedMemory` for
 *    both `nmiOccurred` and the PPUCTRL NMI gate (two layers deep into PPU).
 *  - The 1-instruction NMI dispatch latency was a private `Cpu._nmiArmed`
 *    field invisible at the seam — the load-bearing invariant that keeps
 *    Camerica mapper-71 titles (Big Nose, Micro Machines) from hanging on
 *    a vblank-poll race was a buried comment.
 *  - `Cpu.checkAndHandleIrq` reached into `memory.apu` and `memory.mapper`
 *    to aggregate IRQ sources.
 *
 * CPU tests had to drive `cpu.memory.ppuAddressedMemory.nmiOccurred = true`
 * to set up interrupt scenarios — a leaky seam where tests reach three
 * layers deep into PPU state to drive CPU behaviour.
 *
 * **What this interface buys.**
 *  - The 1-instruction NMI latency is the controller's contract, not an
 *    internal `Cpu` detail.
 *  - CPU tests run against `testutil.FakeInterruptController` and need no
 *    PPU at all.
 *  - PPU / APU / mapper detail is encapsulated — swapping the source
 *    adapters doesn't ripple to `Cpu`.
 *
 * **Ordering rules** (encoded in [pendingInterrupt]):
 *  1. NMI precedence over IRQ — while an NMI is pending, IRQs wait their turn.
 *  2. NMI 1-instruction latency — the first call after the NMI becomes
 *     pending returns `null` (the NMI is "armed" for the next boundary).
 *     The second call returns NMI for dispatch — unless the latch was
 *     cleared within the window (a `$2002` vblank poll won the race).
 *  3. Idle park skips the latency — when `idle == true`, the NMI dispatches
 *     immediately (no in-flight instruction to finish; that's what breaks
 *     a `JMP *` spin loop waiting for vblank — see `IdleLoopTest`).
 *  4. The 6502's I-flag (interruptDisable) gates ONLY IRQ — NMI ignores it,
 *     matching real hardware.
 */
interface InterruptController {
    /**
     * Highest-priority interrupt ready for service RIGHT NOW, or `null`.
     *
     * @param idle `true` if the CPU is parked in a spin loop. In that state
     *   the 1-instruction latency is skipped and a pending NMI dispatches
     *   immediately — what breaks the spin and what makes
     *   `IdleLoopTest.nmiBreaksOutOfTheIdleParkAndClearsTheFlag` pass.
     * @param interruptDisable value of the 6502 I-flag (P status bit 2).
     *   When set, only NMI can dispatch — IRQ is gated. Defaults to `false`
     *   so existing call sites that don't care about the flag stay terse.
     */
    fun pendingInterrupt(idle: Boolean, interruptDisable: Boolean = false): InterruptKind?

    /**
     * Acknowledge a dispatched interrupt — clears the source latch.
     * NMI acknowledges the [NmiSource]; IRQ acknowledges every [IrqSource]
     * in the controller's list.
     */
    fun acknowledge(kind: InterruptKind)

    /** Save-state hook for any controller-side state. Default: no-op. */
    fun saveState(out: DataOutput) {}
    /** Save-state hook. Default: no-op. */
    fun loadState(input: DataInput) {}
}

/**
 * The production [InterruptController]. Owns the 1-instruction NMI
 * dispatch latency ([nmiArmed]); queries the [NmiSource] for the actual
 * edge and the [IrqSource] list for live IRQ state on every tick.
 *
 * Stateless beyond [nmiArmed] — every IRQ query goes fresh to the
 * sources, so the controller never holds a stale "IRQ pending" boolean
 * that could desync from a mapper's real counter.
 */
class NesInterruptController(
    private val nmiSource: NmiSource,
    private val irqSources: List<IrqSource>,
) : InterruptController {

    /**
     * 1-instruction NMI dispatch latency. See [InterruptController] for
     * the rationale; the load-bearing test is `BigNoseHangTest`
     * (Camerica mapper 71).
     *
     * Persisted in save state so a savestate mid-latency replays
     * deterministically (the latch has to round-trip — see VERSION 3
     * bump in `nmi-one-instruction-latency-2026-06-03.md`).
     */
    var nmiArmed: Boolean = false
        private set

    override fun pendingInterrupt(idle: Boolean, interruptDisable: Boolean): InterruptKind? {
        if (!nmiSource.nmiPending()) {
            // NMI latch was cleared within the latency window — e.g. a
            // `$2002` vblank poll read the just-set flag, suppressing
            // the NMI for this frame. This is the correct hardware race
            // outcome (Camerica mapper-71 titles depend on it).
            nmiArmed = false
            if (interruptDisable) return null
            // Indexed iteration (not `irqSources.any { ... }`) — this method
            // is called once per CPU instruction (~500K-1.8M/sec) and the
            // `any` lambda allocates an iterator on every call. `for (i in
            // irqSources.indices)` inlines to an index range check with no
            // allocation. (Code review finding, efficiency angle.)
            for (i in irqSources.indices) {
                if (irqSources[i].irqPending()) return InterruptKind.IRQ
            }
            return null
        }
        if (!nmiArmed && !idle) {
            nmiArmed = true
            return null  // arm; dispatch on next boundary
        }
        // NMI wins over any pending IRQ — see ordering rule 1.
        return InterruptKind.NMI
    }

    override fun acknowledge(kind: InterruptKind) {
        when (kind) {
            InterruptKind.NMI -> {
                nmiArmed = false
                nmiSource.acknowledgeNmi()
            }
            InterruptKind.IRQ -> {
                // Indexed iteration for the same reason as pendingInterrupt —
                // cheap cold path (~60Hz on IRQ dispatch), but keep the
                // idiom consistent.
                for (i in irqSources.indices) {
                    irqSources[i].acknowledgeIrq()
                }
            }
        }
    }

    override fun saveState(out: DataOutput) {
        out.writeBoolean(nmiArmed)
    }

    override fun loadState(input: DataInput) {
        nmiArmed = input.readBoolean()
    }
}

// ---- Default-wiring adapters ---------------------------------------------
// Kept in the same file because they're part of the controller's wiring
// contract — anyone wiring the production emulator uses these. Tests should
// use `testutil.FakeInterruptController` directly.

/** Adapter: routes IRQ queries/acks through the current mapper in [memory].
 *  The mapper can change on cartridge swap, so this adapter always queries
 *  `memory.mapper?` at call time — there's no per-cartridge state to cache. */
class MapperIrqSource(private val memory: Memory) : IrqSource {
    override fun irqPending(): Boolean = memory.mapper?.isIrqPending() ?: false
    override fun acknowledgeIrq() { memory.mapper?.acknowledgeIrq() }
}

/** Adapter: routes IRQ queries through [apu]. Acknowledge is inherited as
 *  no-op — the APU self-clears its IRQ on `$4015` read (per NESdev), not on
 *  CPU dispatch, which is the existing behaviour we preserve. */
class ApuIrqSource(private val apu: Apu) : IrqSource {
    override fun irqPending(): Boolean = apu.isIrqPending()
    // acknowledgeIrq() intentionally inherited as no-op.
}

/** Build the default production wiring for [memory]. Requires the memory to
 *  have been created via `Memory.createWithApu()` so [Apu] is initialised. */
fun defaultInterruptController(memory: Memory): NesInterruptController =
    NesInterruptController(
        nmiSource = memory.ppuAddressedMemory,
        irqSources = listOf(MapperIrqSource(memory), ApuIrqSource(memory.apu)),
    )