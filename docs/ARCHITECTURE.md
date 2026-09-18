# Architecture

This page is for contributors and coding agents who need the current ownership map before changing emulator code. It is a map of ownership, not a promise that every class name will remain unchanged. When a boundary changes, update this file and the relevant ADR in the same change.

## Runtime flow

```text
ROM path
  -> file loader (.nes / .7z)
  -> GamePak + Header + mapper
  -> Nestlin / EmulatorSession
  -> RunLoop
  -> CPU cycles
       -> Memory (CPU bus)
            -> PPU registers / APU registers / controller / mapper
       -> PPU ticks and renders frames
       -> APU ticks and mixes samples
       -> mapper IRQ or audio hooks
  -> JavaFX UI or headless CLI
```

`Nestlin` owns orchestration and the emulation seam. `session/` owns loaded-ROM identity, timing, run-loop lifecycle, rewind, and optional RetroAchievements coordination. `RunLoop` owns wall-clock pacing; CPU, PPU, APU, and mapper code must not invent a second pacing loop.

## Subsystem ownership

| Area | Package/files | Owns | Does not own |
| --- | --- | --- | --- |
| CPU | `cpu/` | 6502 registers, opcodes, addressing, interrupt consumption | PPU timing policy or UI state |
| CPU bus | `Memory.kt` | `$0000-$FFFF` dispatch, RAM, PPU/APU/controller/mapper routing, DMA requests | Mapper bank policy |
| PPU | `ppu/` | 2C02 timing, VRAM/OAM/palette, rendering, NMI source | CPU instruction dispatch |
| APU | `apu/`, `Apu.kt` | Five base channels, frame counter, mixer, resampler | Host-device lifecycle |
| Cartridge | `gamepak/` | iNES/NES 2.0 parsing, PRG/CHR storage, mirroring, mapper registers and IRQ/audio hooks | ROM acquisition or UI presentation |
| Session | `session/`, `rewind/` | Loaded ROM identity, region config, lifecycle, rewind, optional RA service | Core hardware behavior |
| UI | `ui/` | JavaFX windows, controls, menus, screenshots, save-state actions | Direct subsystem internals; use public seams |
| Headless tools | `cli/` and Gradle tasks | Replay, boot smoke, divergence, native smoke, benchmarks | GUI startup |

## State and threading rules

- CPU, PPU, APU, mapper, controller, and session state must have explicit save/load ownership. `SaveState` orchestrates serialization; callers should not reach into a subsystem's private fields.
- The emulation thread is the owner of live machine mutation. UI actions that save or load state must use the pause/coordination path in `Application.kt`.
- `peek` is side-effect-free and is used by inspection tools. `poke` is a real write with intentionally blacklisted dangerous operations such as OAM DMA and controller strobe.
- `InterruptController` is the seam between PPU/APU/mapper interrupt producers and the CPU consumer. Preserve NMI latency, NMI-over-IRQ ordering, and I-flag gating when changing it.
- A mapper clocks IRQ logic either from PPU A12 edges or CPU cycles, according to the board. Do not add both clocks without a hardware reason and a regression test.

## Test seams

The preferred evidence order is:

1. Pure unit or component tests for arithmetic, register decode, and state transitions.
2. Hook-based behavior assertions for cycle-sensitive interactions.
3. Mesen2 structured state comparisons for real-game behavior.
4. Pixel captures only when the visual output itself is the contract.

The full policy and test-lane commands are in [`TESTING_STRATEGY.md`](TESTING_STRATEGY.md). Mapper-specific workflow is in [`MAPPER_SUPPORT.md`](../MAPPER_SUPPORT.md) and `.claude/skills/new-mapper/SKILL.md`.

## Durable decisions

Use an ADR for an ownership or interface decision that affects multiple subsystems. Existing examples include memory-editor refresh, CHR memory ownership, and interrupt-controller boundaries in [`docs/adr/`](adr/).
