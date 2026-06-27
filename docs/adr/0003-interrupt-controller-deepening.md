# ADR-0003: InterruptController deepening — single seam for CPU↔PPU↔Memory interrupt semantics

**Status:** Accepted
**Date:** 2026-06-27
**Context:** A 2026-06-20 architecture review (see ADR-0002 — PR #188) surfaced that interrupt handling was scattered across three subsystems, each encoding the same concept in a different way. The pre-deepening shape had three encoding points:

1. `Cpu.checkAndHandleNmi` / `checkAndHandleIrq` were inline in `Cpu.tick()` with deep coupling to `workCyclesLeft`, `_nmiArmed`, `_idle`, and `memory.ppuAddressedMemory.{nmiOccurred, controller.generateNmi()}`.
2. `Memory` held a `var cpu: Cpu?` back-reference set after construction, ONLY to halt the CPU with `cpu.workCyclesLeft = 513` during OAM DMA.
3. CPU tests poked `cpu.memory.ppuAddressedMemory.nmiOccurred = true` directly — a leaky seam where the test reached three layers deep into PPU state to drive a CPU scenario.

The 1-instruction NMI dispatch latency (per the project memory note `nmi-one-instruction-latency-2026-06-03.md` and the regression `BigNoseHangTest`) lived as a private method buried inside `Cpu.tick()` — the load-bearing invariant of "Camerica/Codemasters mapper-71 titles don't hang on a vblank-poll race" was invisible at the seam.

## Decision

Introduce an `InterruptController` interface in `src/main/kotlin/com/github/alondero/nestlin/cpu/InterruptController.kt`. Three adapter contracts hang off it:

- `NmiSource` — the PPU's edge-triggered NMI latch (`PpuAddressedMemory` in production).
- `IrqSource` — a single IRQ line (mapper or APU; aggregated as a `List<IrqSource>` in the controller).
- `InterruptController.pendingInterrupt(idle, interruptDisable)` returns `InterruptKind.{NMI, IRQ, null}`.
- `InterruptController.acknowledge(kind)` clears the dispatched source.

A companion `StallSource` interface replaces `Memory.cpu: Cpu?` with `Memory.stallSource: StallSource?`. `Cpu` implements `StallSource` (sets `_workCyclesLeft = cycles`).

### Sub-decisions

| Sub-decision | Choice | Rationale |
|---|---|---|
| **A — interface vs sealed class** | **A1: `interface`** with a separate `NesInterruptController` production impl and a `testutil.FakeInterruptController` for tests. | Two adapters (production + test) is the project glossary's signal for a real seam, not a hypothetical one. Forward-compatible with future per-mapper controllers (e.g. an FME-7 controller that owns cycle-counted IRQs directly). |
| **B — push vs pull for IRQ sources** | **B2: pull-based.** Controller queries `irqSources.any { it.irqPending() }` on every tick; no cached boolean. | IRQ sources have no latency (just "is the line asserted?"), so caching adds a desync hazard for no benefit. The mapper and APU already expose `isIrqPending()` as their source-of-truth — wrapping them in `IrqSource` adapters is one line each. |
| **C — `acknowledgeIrq()` default** | **C1: default no-op** in `IrqSource`; `MapperIrqSource` overrides to call `mapper.acknowledgeIrq()`; `ApuIrqSource` leaves it as no-op. | The APU self-clears its IRQ on `$4015` read (per NESdev), not on CPU dispatch — only the mapper has a distinct "acknowledge" write. The default preserves the existing behaviour exactly. |
| **D — `interruptDisable` (6502 I-flag)** | **D1: parameter to `pendingInterrupt(idle, interruptDisable)`.** Default `false`. | NMI ignores the I-flag; IRQ respects it. Passing the flag into the controller means the controller is the single place that encodes "what does an interrupt dispatch look like right now?" — `Cpu` just executes what the controller returns. |
| **E — save-state ownership** | **E1: controller owns it.** `NesInterruptController.saveState(out)` writes `nmiArmed` (1 byte); `SaveState.VERSION` bumps 3→4; the controller's block sits between the CPU block and RAM. | The 1-instruction latency is the controller's contract — its persistence belongs with the controller, not split between CPU and controller. The VERSION bump is the existing convention (issues #88, #100). |

### Interface shape

```kotlin
enum class InterruptKind { NMI, IRQ }

interface NmiSource {
    fun nmiPending(): Boolean
    fun acknowledgeNmi()
}

interface IrqSource {
    fun irqPending(): Boolean
    fun acknowledgeIrq() {}  // default no-op (APU self-clears on $4015)
}

interface InterruptController {
    fun pendingInterrupt(idle: Boolean, interruptDisable: Boolean = false): InterruptKind?
    fun acknowledge(kind: InterruptKind)
    fun saveState(out: DataOutput) {}
    fun loadState(input: DataInput) {}
}

interface StallSource {
    fun stallFor(cycles: Int)
}
```

### Ordering rules (encoded in `pendingInterrupt`)

1. **NMI precedence over IRQ** — while an NMI is pending, IRQs wait their turn.
2. **NMI 1-instruction latency** — first call after the NMI becomes pending returns `null` (the NMI is "armed" for the next boundary). Second call returns NMI for dispatch — unless the latch was cleared within the window (a `$2002` vblank poll won the race).
3. **Idle park skips the latency** — when `idle == true`, the NMI dispatches immediately (no in-flight instruction to finish; that's what breaks a `JMP *` spin loop waiting for vblank).
4. **I-flag (interruptDisable) gates ONLY IRQ** — NMI ignores it, matching real hardware.

### Invariants

- After construction: exactly one source per interrupt class. The `NmiSource` is the PPU (no other producer); the `IrqSource` list aggregates mapper + APU.
- `pendingInterrupt` is *purely* a query — calling it twice without an intervening `tick` is well-defined and returns the same answer (no hidden side effects beyond `nmiArmed` state, which the controller owns).
- `acknowledge` is the only path that mutates producer state — once dispatched, `NmiSource.nmiPending()` returns false until the PPU re-arms it on the next vblank.
- `StallSource.stallFor(N)` is idempotent: calling it while the CPU is already mid-stall resets the remaining counter to `N` (matches OAM DMA semantics — each `$4014` write restarts the 513-cycle halt).

### Error modes

- `StallSource` is `null` on `Memory` before the CPU wires it (init order: `Memory.createWithApu()` → `Cpu(memory)` → `Cpu.init` sets `memory.stallSource = this`). Any code path that calls `$4014` before this wiring is unreachable in production (no ROM can be loaded before the CPU exists).
- `IrqSource.acknowledgeIrq()` no-op default means a future IRQ source that forgets to override it just never clears — observable as a stuck IRQ line. Documented at the interface; tests should exercise every concrete source's acknowledge path.

### Performance

- `pendingInterrupt` — O(N) over `irqSources.size` per tick. In practice `N == 2` (mapper + APU). Zero allocations.
- `acknowledge` — O(N) over `irqSources.size` on IRQ dispatch (rare — once per IRQ line). NMI dispatch is O(1).
- `stallFor(N)` — O(1), single integer write.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Sealed class `InterruptKind` with `when` over a single `MutableList<InterruptEvent>`** | Loses the source-adapter contract — the controller would need to know about every concrete source type. |
| **Push-based IRQ** (`controller.armIrq()` called by mapper/APU on every state change) | Requires every IRQ source to know about the controller and wire a callback. Mapper IRQ counters (MMC3, FME-7) already expose `isIrqPending()` as their source-of-truth — pull is one line of adapter, push is a callback wiring change per mapper. |
| **Single `InterruptController.armNmi()` / `armIrq()` push API** (the issue's literal text) | Mixes the asymmetry (NMI needs controller state for the latency; IRQ doesn't) into one API. The consumer would still need to know which sources to push from. Cleaner to keep the producer-push path for NMI (via `setVBlank` → controller's internal NMI state) and the consumer-pull path for IRQ, and have the controller hide both behind one `pendingInterrupt` API. |
| **Keep `_nmiArmed` on `Cpu`, expose via `Cpu.nmiArmed` accessor that reads through the controller** | The interface invariant must own the latency — a leaky accessor doesn't enforce anything and tests would still poke at `cpu.nmiArmed` instead of the controller's state. |
| **Skip the `StallSource` interface and keep `Memory.cpu: Cpu?`** | Saves ~30 lines of code but keeps the back-reference that the issue explicitly flagged. The interface is the minimum-blast-radius fix. |
| **Default no-op `acknowledgeIrq` overridable per source** (current choice) vs. **two separate interfaces `DispatchIrqSource` / `PollIrqSource`** | Two interfaces adds ceremony for a one-method difference. The default no-op documents the asymmetry at the call site: readers see `mapper.acknowledgeIrq()` is overridden, `apu.acknowledgeIrq()` is silently not — exactly the asymmetry we want to preserve. |

## Consequences

### Positive

- `Cpu.checkAndHandleNmi` / `checkAndHandleIrq` are gone — replaced by a single `interruptController.pendingInterrupt(idle, interruptDisable)` call and a shared `dispatchInterrupt(kind, vector)` helper. The 1-instruction NMI latency invariant is now an interface contract, not a buried comment.
- `Memory.cpu: Cpu?` is gone — replaced by `Memory.stallSource: StallSource?`. The CPU↔Memory coupling narrows to one capability ("you may stall this CPU").
- CPU tests run against `testutil.FakeInterruptController` — no PPU poke required (`cpu.memory.ppuAddressedMemory.nmiOccurred = true` was a 3-layer-deep leaky seam).
- The `IdleLoopTest.nmiBreaksOutOfTheIdleParkAndClearsTheFlag` test no longer touches PPU state — pure controller semantics.
- `InterruptCounterTest.armedButSuppressedNmiDoesNotCount` asserts `fakeController.nmiArmed` directly — the invariant is exposed as a test surface, not buried in `Cpu`.

### Negative / deferred

- `SaveState.VERSION` bumps 3→4. Existing savestates made by older builds will fail to load with `Unsupported save state version 3 (expected 4)` — intentional per the existing convention (issues #88, #100).
- The CPU block in savestates now has a 4-byte reserved slot where `_nmiArmed` used to live. Saves 1 byte of CPU-block growth vs. keeping the field; the controller block is the new home.
- Production `Cpu(memory)` defaults to `defaultInterruptController(memory)` which builds the production wiring from `memory.ppuAddressedMemory` and `memory.apu`. Tests that need a custom controller pass one explicitly (the three leaky-seam tests do this; all other CPU tests keep using the default with no behaviour change).
- `compare/` observation reads (`nestlin.memory.ppuAddressedMemory.nmiOccurred` in `KirbyVBlankTest`, `VBlankTimingTest`, etc.) stay as-is — they're observational, not test-setup. Removing them would be a separate cross-cutting change.

### Forward compatibility

- The controller's `saveState`/`loadState` hooks are reserved for future controller-side state (e.g. an FME-7 cycle counter the controller owns). The current 1-byte block is a trivial floor; adding bytes later is an additive change.
- A future `MapperCapabilityInterface` split (see ADR-0002 forward-compat note) can replace `MapperIrqSource` with an inline `IrqSource` impl on each mapper that needs one — the controller's pull-based API doesn't change.

## References

- Issue #190 — the GitHub issue this ADR resolves.
- ADR-0002 — the ChrMemory deepening whose architecture review surfaced the scattered interrupt handling.
- `docs/adr/0002-chr-memory-deepening.md` — same "one component at a time" phasing rule this ADR follows.
- Memory note `nmi-one-instruction-latency-2026-06-03.md` — the load-bearing invariant the controller now encapsulates.
- Memory note `memory-no-mapper-bus-silent-drop-2026-06-20.md` — separate but related concern (CPU bus), explicitly noted in issue #190 as out of scope.
- `BigNoseHangTest` (Camerica mapper 71) — the canary for the 1-instruction NMI latency.
- `MemoryOamDmaTest` — the regression for the 513-cycle OAM DMA halt; now driven through `StallSource` instead of a `Cpu?` back-reference.
- `CONTEXT.md` — `InterruptController`, `StallSource`, `NmiSource`, `IrqSource` glossary entries added 2026-06-27.