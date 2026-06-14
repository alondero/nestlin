# ADR-0001: Memory Editor refresh strategy — poll at fixed UI rate

**Status:** Accepted  
**Date:** 2026-06-14  
**Context:** The Memory Editor displays live CPU bus contents while the emulation loop runs at ~60fps (NTSC) / ~50fps (PAL), mutating memory thousands of times per frame. The editor needs to refresh its 64 KB hex grid periodically.

## Decision

The Memory Editor polls memory at a **fixed 10 Hz UI timer** (~100 ms interval), independent of the emulation frame rate.

Each tick:
1. `peek()` the visible address range (not the full 64 KB — only the rows currently scrolled into view).
2. Diff against the previous snapshot to detect changed cells.
3. Repaint only dirty cells; apply directional change highlighting (green = increased, blue/cyan = decreased, 500 ms fade).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Refresh every emulated frame (60 Hz)** | 64 KB diff + JavaFX repaint at 60 fps is expensive; humans can't read hex at that rate anyway. |
| **Event-driven (memory write callback)** | CPU bus sees thousands of writes per frame; funnelling each one to the FX thread would drown the event queue and couple the hot emulation path to UI code. |
| **User-configurable rate** | Extra UI complexity for uncertain benefit. Easy to add later if 10 Hz proves wrong — it's a single constant. |

## Consequences

- A change that lasts < 100 ms between two poll ticks may be missed by the editor. Acceptable — the editor is a human-facing debug tool, not a logic analyser.
- The 500 ms fade highlight clears after ~5 refresh ticks, keeping the display readable even for rapidly-changing addresses (frame counters, timers).
- Only visible rows are peeked, so scrolling to a different region has near-zero cost even at 10 Hz.
