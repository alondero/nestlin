# Nestlin — Domain Language

## Glossary

| Term | Definition |
|---|---|
| **CPU bus** | The 6502's 64 KB address space (`$0000–$FFFF`): internal RAM, PPU/APU registers, mapper I/O, and PRG-ROM. The primary memory space users interact with via the Memory Editor. |
| **Memory Editor** | A debug window that displays live CPU bus contents in a hex grid and lets the user edit values while the game runs. Separate JavaFX `Stage`, opened from the Debug menu (Ctrl+M). |
| **peek** | A side-effect-free read of a memory address — returns the backing value without triggering register side effects (vblank clear, latch shift, VRAM increment, data-bus update, dataBus update). Used by the Memory Editor for display. Applies to any addressable backing (CPU bus, future CHR bus). |
| **poke** | A write to a memory address from the Memory Editor. Delegates to `Memory.set()` for full side effects, except `$4014` (OAM DMA) and `$4016` (controller strobe) which are blacklisted to prevent catastrophic unintended effects. |
| **change highlight** | Visual feedback in the Memory Editor when a byte's value differs between refreshes. Directional: green = value increased, blue/cyan = value decreased. Fades over ~500 ms. |
| **memory region** | A named band in the CPU bus memory map (RAM `$0000–$1FFF`, PPU `$2000–$3FFF`, APU/IO `$4000–$401F`, Cart `$4020–$FFFF`). Colour-coded in the Memory Editor's address gutter. |
| **cheat search** | (v2) A structured workflow for narrowing candidate addresses: snapshot → filter (equals/increased/decreased) → repeat until a handful of candidates remain. Deferred from v1. |
| **freeze** | (v2) Locking a memory address to a fixed value so the game can never change it (e.g., infinite lives). Requires hooking the emulation loop. Deferred from v1 — ships alongside cheat search. |
| **ChrMemory** | Flat CHR storage module. Owns the `chrRom` + `chrRam` byte arrays, the CHR-RAM size (default `0x2000`, `0x3000` for Mapper19 N163), and the read-only / CHR-RAM branching. Mapper still computes bank indices and translates to a flat offset before calling `ChrMemory.read(offset)`. NOT banking-aware — that responsibility stays with the mapper. Replaces the duplicated `chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null` pattern across 15 mapper files (see ADR-0002). |
| **CHR bus** | The PPU's 8 KB CHR address space (`$0000–$1FFF`), addressed via PPU pattern tables. Reads/writes flow through `ChrMemory`. Mapper19 N163 has a 12 KB internal CHR-RAM buffer but the bus itself remains 8 KB; the extra CHR-RAM is mapped into the bus via Mapper19's bank registers. |
| **mapper capability interface** | (Forward-looking) An optional interface (`IrqCapable`, `CpuCycleIrqCapable`, `AudioCapable`, `BatteryRamCapable`) that a mapper may implement to declare it supports a feature. The future `MapperCore` split (separate from `Mapper` itself) exposes only `cpuRead/cpuWrite/ppuRead/ppuWrite/currentMirroring`; capabilities are queried via `mapper is IrqCapable` rather than via 11-method interface defaults. |
