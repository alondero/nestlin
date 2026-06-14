# Nestlin — Domain Language

## Glossary

| Term | Definition |
|---|---|
| **CPU bus** | The 6502's 64 KB address space (`$0000–$FFFF`): internal RAM, PPU/APU registers, mapper I/O, and PRG-ROM. The primary memory space users interact with via the Memory Editor. |
| **Memory Editor** | A debug window that displays live CPU bus contents in a hex grid and lets the user edit values while the game runs. Separate JavaFX `Stage`, opened from the Debug menu (Ctrl+M). |
| **peek** | A side-effect-free read of a memory address — returns the backing value without triggering register side effects (vblank clear, latch shift, VRAM increment, data-bus update). Used exclusively by the Memory Editor for display. |
| **poke** | A write to a memory address from the Memory Editor. Delegates to `Memory.set()` for full side effects, except `$4014` (OAM DMA) and `$4016` (controller strobe) which are blacklisted to prevent catastrophic unintended effects. |
| **change highlight** | Visual feedback in the Memory Editor when a byte's value differs between refreshes. Directional: green = value increased, blue/cyan = value decreased. Fades over ~500 ms. |
| **memory region** | A named band in the CPU bus memory map (RAM `$0000–$1FFF`, PPU `$2000–$3FFF`, APU/IO `$4000–$401F`, Cart `$4020–$FFFF`). Colour-coded in the Memory Editor's address gutter. |
| **cheat search** | (v2) A structured workflow for narrowing candidate addresses: snapshot → filter (equals/increased/decreased) → repeat until a handful of candidates remain. Deferred from v1. |
| **freeze** | (v2) Locking a memory address to a fixed value so the game can never change it (e.g., infinite lives). Requires hooking the emulation loop. Deferred from v1 — ships alongside cheat search. |
