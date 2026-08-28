# 6502 Reset Sequence — Why Nestlin Implements It The Way It Does

## What the 6502 actually does on RESET

The 6502 reset sequence is the same seven-cycle interrupt entry sequence
used for IRQ and NMI, with the three stack *writes* replaced by *reads* and
the vector fixed at `$FFFC/$FFFD`:

```
  #  address    R/W description
  1  PC         R   fetch opcode (discarded; PC is meaningless at reset)
  2  PC         R   read next instruction byte (discarded)
  3  $0100+S    R   read from stack (discarded)
  4  $0100+S-1  R   read from stack (discarded)
  5  $0100+S-2  R   read from stack (discarded)
  6  $FFFC      R   fetch low byte of reset vector
  7  $FFFD      R   fetch high byte of reset vector; S -= 3, I flag set
```

The write-enable is gated but the stack-pointer arithmetic is identical to
the interrupt sequence, so S decrements three times regardless. The
sequence runs on the bus one cycle at a time, exactly like
`MicrocodedInterrupt`.

## Power-on vs RESET-button — the part that matters

On real hardware the two are **not the same**:

- **Power-on:** RAM is uninitialised (the 2A03's internal RAM powers up
  to the indeterminate/0xFF pattern the memory editor observes). A, X, Y
  are zero. S is `$00`; the sequence's three decrements land it at the
  documented power-up value `$FD`.
- **RESET button (soft reset):** A, X, Y, P (except I), and SP are
  **preserved**. RAM is preserved. The sequence still runs and still
  decrements S by three and still forces the I flag — but the data
  registers carry whatever the running program left there.

Pre-deepening Nestlin zeroed A/X/Y/P/SP on the RESET button, which is
wrong and (very rarely) observable by games that check for "did the user
press reset" by inspecting RAM patterns that survive the RESET line but
not a power cycle.

## Why the cycleCount zeroing is at the sequence's completion

`nestest.log` reports the first instruction at CYC:0. The convention is
"cycles since the reset vector fetch", not "cycles since `reset()` was
called". To honour that, `_cycleCount` must reach zero on the tick that
the vector is fetched — *not* on the `reset()` call itself. The
`Cpu.tick()` `finally` block does the per-tick increment and then zeroes
the counter if `resetSequenceCompletedThisTick` was set by the
`activeReset` branch. OAM DMA's `$4014` alignment parity (which uses
`1 + (_cycleCount and 1)`) is therefore identical to pre-deepening
behaviour: first instruction begins at cycle 0.

## Latent bug the deepening fixed

`resetCpuState()` never cleared `_idle`. A CPU parked in a `KIL`/spin
loop after a test program (e.g. nestest's official tests end in KIL)
stayed *frozen* after `reset()` — the new PC landed at the reset vector
but the park guard short-circuited every opcode fetch. The
`reset wakes a parked idle CPU` test (`CpuResetSequenceTest`) and the
spin-ROM swap in `MovieCommandsTest` both pin the corrected behaviour.
Do not remove the `_idle = false` from `beginResetSequence` without
checking those two tests.

## Reference

- [nesdev wiki: CPU RESET](https://www.nesdev.org/wiki/CPU_RESET)
- `MicrocodedReset.kt` — the implementation.
- ADR-0003 — the prior deepening that established the
  "tick-by-tick micro-state" pattern this follows.
- PR #300 — the cycle-stepped work that PR #301 closes out.
