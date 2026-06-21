# ADR-0002: `ChrMemory` deepening — interface with flat-window storage and reserved hooks

**Status:** Accepted
**Date:** 2026-06-20
**Context:** A 2026-06-20 architecture review of `gamepak/` identified that 15 `MapperX.kt` files (Mapper 1, 3, 4, 5, 7, 11, 19, 33, 34, 65, 66, 69, 71, 113, 206) each duplicate the pattern `chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(SIZE) else null`, plus an `if (chrRom.isEmpty()) chrRam!![...] : chrRom[...]` ternary in every `ppuRead` and `ppuWrite`. Three hardcoded sizes recur (`0x2000` standard, `0x3000` for Mapper 19 n163, `0` for a hypothetical absent-CHR case). The review concluded this duplication fails the deletion test — complexity vanishes if a `ChrMemory` module is introduced, complexity reappears across n callers if removed.

## Decision

Introduce a `ChrMemory` module in `src/main/kotlin/com/github/alondero/nestlin/gamepak/`. It owns the `chrRom` + `chrRam` byte arrays, the CHR-RAM size, and the read-only / CHR-RAM branching. The mapper still computes bank indices and translates to a flat offset before calling `ChrMemory.read(offset)`.

### Sub-decisions

| Sub-decision | Choice | Rationale |
|---|---|---|
| **A — what sits behind the seam** | **A2: Storage + flat CHR window.** `ChrMemory` owns storage and the ROM/RAM branching; the mapper owns banking. | Captures ~80% of the duplication without forcing every mapper rewrite at once. Forwards-compatible with the planned `MapperCore` + capability interfaces split. |
| **B — Mapper19 n163 12KB variant** | **B1: Constructor parameter** `chrRamSize: Int = 0x2000`. n163 passes `0x3000`. | Matches how the duplication is currently expressed (one `ByteArray(n)` per mapper). Keeps the seam as one class. |
| **C — save-state ownership** | **C2: Defer to a later phase**, but reserve the seam via default-no-op `serialize(out)` / `deserialize(input)` interface methods. | Folding save state into this pass would violate the "one component at a time" rule. The interface hooks cost zero for mappers without CHR-RAM (default no-op) and provide a real seam when C2 lands. |

### Interface shape

```kotlin
interface ChrMemory {
    fun read(offset: Int): Byte
    fun write(offset: Int, value: Byte)

    // Default no-op hooks — present for future consumers, invisible to 95%
    fun peek(offset: Int): Byte = read(offset)
    fun serialize(out: DataOutput) {}
    fun deserialize(input: DataInput) {}

    companion object {
        fun default(chrRom: ByteArray, chrRamSize: Int = 0x2000): ChrMemory =
            DefaultChrMemory(chrRom, chrRamSize)
    }
}

class DefaultChrMemory(
    chrRom: ByteArray,
    chrRamSize: Int = 0x2000
) : ChrMemory {
    private val ram: ByteArray =
        if (chrRom.isEmpty()) ByteArray(chrRamSize) else ByteArray(0)
    private val rom: ByteArray = chrRom

    override fun read(offset: Int): Byte =
        if (ram.isnotEmpty()) ram[offset] else rom[offset]

    override fun write(offset: Int, value: Byte) {
        if (ram.isnotEmpty()) ram[offset] = value
    }

    override fun serialize(out: DataOutput) {
        if (ram.isnotEmpty()) out.write(ram)
    }

    override fun deserialize(input: DataInput) {
        if (ram.isnotEmpty()) input.readFully(ram)
    }
}
```

### Invariants

- After construction: exactly one of `chrRom` (non-empty) or `chrRam` (size = `chrRamSize`) is active.
- `chrRamSize` is `0x2000` (standard) or `0x3000` (Mapper 19 n163) for all shipping mappers that allocate `chrRam`. The `chrRamSize = 0` case is reserved for a hypothetical cartridge with no CHR space at all (neither ROM nor RAM); Mapper 0's current `ppuRead` returns the constant `0` when `chrRom.isEmpty()`, which would slot into this case after the refactor.
- `peek` is side-effect-free, mirroring the project's existing `peek` precedent (see `Memory.kt`). Default delegates to `read`; mappers that grow read-side-effect banking later override `peek` without changing the `read` interface.

### Error modes

- `offset < 0` or `offset >= size`: throws `IndexOutOfBoundsException` from the underlying `ByteArray` access. Callers are expected to pre-mask (`address and 0x1FFF`).
- Write to CHR-ROM: silently ignored (the mapper's own guard prevents the call when CHR-RAM is absent).
- `chrRamSize = 0` (hypothetical absent-CHR cartridge, or Mapper 0 when its `chrRom` is empty): both `read` and `write` operate on a zero-length `ram` array; `read(0)` returns `0` from the empty array.

### Performance

- `read` / `write`: O(1), constant-time `ByteArray` indexing, zero allocations after construction.
- `peek`: O(1), same as `read`.
- `serialize` / `deserialize`: O(`chrRamSize`) when CHR-RAM is active, O(1) otherwise.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **A1: Pure storage only** — `ChrMemory` is just byte storage; mapper keeps the per-window `if (chrRom.isEmpty()) chrRam!![addr]` checks. | Captures only the allocation duplication; misses the per-call-site branching and the `chrRam!!` force-unwrap across 20+ mappers. |
| **A3: Storage + named banking windows** — `ChrMemory` owns `bankWindows: Array<ChrBank>` where each `ChrBank` has a size and a target. Mapper sets `bankWindows[n] = ChrBank(size, banknumber)`. | Maximum leverage (every mapper's banking moves to `ChrMemory`), but forces every mapper rewrite at once and violates the "one component at a time" rule. Better as a later deepening after the `MapperCore` + capability interfaces split lands. |
| **B2: Separate `ExtendedChrMemory` class for n163.** Two classes, explicit naming. | Matches the `ScanlineCounter` → strategy variants decision (separate class per behavior), but `ChrMemory` is much less behavioral than `ScanlineCounter`. The naming benefit is smaller than the cost of two classes for one concept. |
| **C1: Save state lands with this pass** — `ChrMemory.saveState(out)` / `loadState(in)` are part of this PR. Mapper `saveState` body shrinks immediately. | Larger diff; harder to revert if `ChrMemory` itself needs to roll back. The interface hooks provide the seam without the immediate port. |
| **Concrete `class ChrMemory` with no interface (one-adapter hypothetical seam).** | Forces future `BatteryBackedChrMemory` to wrap the class and intercept via composition — works, but loses the override capability that interface methods provide. Per `LAnGUAGE.md`: *"One adapter = hypothetical seam. Two adapters = real seam."* We're laying the groundwork for two adapters (standard + battery-backed) without forcing the second one to exist yet. |
| **`peek` / `serialize` / `deserialize` as Kotlin extension functions with default implementations.** | Extension functions in Kotlin can't be overridden by adapter classes. The hooks would look like a seam but a future `BatteryBackedChrMemory` couldn't actually override them. Interface methods with default bodies are the correct shape. |
| **Add `nullChrMemory` adapter for Mapper 10's absent-CHR case.** | `DefaultChrMemory(chrRom, chrRamSize = 0)` already handles this uniformly (zero-length `ram` array, `read(0)` returns 0). A separate adapter adds ceremony without removing duplication. |

## Consequences

### Positive

- The `chrRom.isEmpty() ? chrRam!![x] : chrRom[x]` ternary disappears from 20+ mapper files. Mapper `ppuRead`/`ppuWrite` become a one-line bank-to-offset computation plus a `ChrMemory.read` / `write` call.
- The `chrRam!!` force-unwrap disappears.
- The three hardcoded sizes (`0x2000` / `0x3000` / `0`) become a single `chrRamSize` constructor parameter.
- The `peek` hook is established before any mapper grows read-side-effect banking logic (preempts the retrofit hazard documented in `memory-editor-peek-path-issue-168-2026-06-15.md`).
- The `serialize` / `deserialize` hooks reserve the save-state seam with zero cost to mappers that don't need it.

### negative / deferred

- Every mapper gains a `ChrMemory` field where previously it had none. Object graph is one level deeper (deletion test still passes — code is replaced, not added).
- The save-state refactor (C2) is still pending. Per-mapper `saveState` / `loadState` `chrRam != null` guards remain in this pass.
- The eventual `MapperCore` + capability interfaces split (separate ADR) is still pending. `ChrMemory` is forward-compatible with that split via a future `HasChrStorage` capability interface.

### Forward compatibility

- `ChrMemory` slots cleanly into the planned `MapperCore` + capability interfaces split. A future `HasChrStorage` capability interface can expose `chrMemory: ChrMemory` without re-introducing the duplication.
- `BatteryBackedChrMemory` (for VRC6 Mapper 24 and any future battery-backed CHR-RAM) can implement `ChrMemory` and override `serialize` / `deserialize` without touching `DefaultChrMemory`. This is a closed-for-modification, open-for-extension design.

## References

- COnTEXT.md — `ChrMemory`, `CHR bus`, `mapper capability interface` terms added 2026-06-20.
- Memory note `memory-editor-peek-path-issue-168-2026-06-15.md` — the `peek` precedent this ADR mirrors for CHR.
- `docs/adr/0001-memory-editor-refresh-strategy.md` — the format template this ADR follows.