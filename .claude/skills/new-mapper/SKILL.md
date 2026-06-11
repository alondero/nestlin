---
name: new-mapper
description: Checklist-driven workflow for implementing a new NES mapper in Nestlin (or fixing a mapper that "works in unit tests but the game doesn't boot"). Invoke whenever adding mapper support for a new mapper number, implementing a board (MMC/VRC/FME/Namco/Bandai/etc.), or when a newly-merged mapper still fails in a real game. Encodes the oracle ordering and test requirements distilled from every past mapper implementation.
---

# New Mapper Implementation Checklist

Five past mappers shipped with green test suites that asserted the bug (GxROM decode,
RAMBO-1 ×2, Bandai EEPROM, NES 2.0 header split). Every one happened because the wrong
source was treated as ground truth, or the test exercised registers the real game never
touches. This checklist exists to make those failure modes impossible. Work through it
in order; do not skip steps because the mapper "looks simple".

## Step 0 — Identify the actual board (headers lie)

- Decode the ROM's header yourself before trusting the issue title. Known traps:
  - Every Namco 108/DxROM dump (Gauntlet, Ring King, RBI Baseball, Mappy-Land) is
    labelled **mapper 4** in iNES bytes 6/7. Patch a *copy* for mapper-206 work:
    byte6 `(b6 & 0x0F) | 0xE0`, byte7 `(b7 & 0x0F) | 0xC0`.
  - Captain Tsubasa II is mapper 4, NOT 33. Alien Syndrome is 118, NOT 64.
  - iNES 1.0 dumps omit the submapper the board may require (broke two Bandai games).
- NES 2.0 decode: mapper = `(byte6>>4) | (byte7&0xF0) | (isNes20 ? (byte8&0x0F)<<8 : 0)`;
  byte 7 **bit 4 is mapper bit D4**, not a flag; NES 2.0 = byte 7 bits 2-3 == 0b10;
  submapper = byte 8 high nibble.

## Step 1 — Oracle ordering (hard rule)

1. **nesdev wiki page** for the mapper number — the spec.
2. **Mesen2's mapper source** (`Core/NES/Mappers/<family>/<Board>.h` in the Mesen2 repo)
   — the tiebreaker. When wiki prose and Mesen disagree, **Mesen wins** (proven twice:
   VRC4 `$9002` bit layout, RAMBO-1 IRQ reload). When the GitHub **issue spec disagrees
   with both, the issue is design *intent* only** — the RAMBO-1 issue's "no-IRQ/masked
   bits" claims were simply wrong.
3. Watch for family look-alikes that differ in one trap detail: RAMBO-1 is "MMC3-shaped"
   but has 4-bit bank selects, K-bit 1KB CHR mode, R15, and `latch+2` IRQ reload;
   TC0190 R0/R1 preserve the LSB unlike MMC3; mapper 25 swaps the low address bits.

## Step 2 — Implementation conventions

- Register decode masks: most boards alias addresses — decode via `addr & MASK` `when`
  arms (see `Mapper33.kt` for `addr & 0xA003`, `Mapper113.kt` for a `$4100` window with
  mask `0xE100`).
- IRQ clocking: a mapper counts **either** A12 edges (MMC3 family) **or** CPU cycles
  (FME-7, Bandai, via `Mapper.tickCpuCycle()`) — never both.
- Reads the chip doesn't drive must return the **open-bus value** (`dataBus`), not 0 —
  a 0 read decodes as BRK and derails boot (Mind Blower Pak).
- Mappers with state: bump `saveStateVersion`, override `saveState`/`loadState`, call
  `super` first.

## Step 3 — Tests (all four kinds, in this order)

1. **Unit tests with `testutil.TestRomBuilder`** — never hand-roll the 16-byte header
   (7+ past incidents: missing magic, 16KB-vs-8KB unit confusion, submapper nibble).
   Assert register decode, bank arithmetic, mirroring, IRQ enable/disable/ack.
2. **Game-usage trace test** — run `tools/mesen-trace/write-watch.lua` against the real
   ROM to record which registers/modes the game *actually* writes, then make a test
   that drives exactly that sequence (the `Mapper64KlaxBankTraceTest` pattern). A suite
   that only exercises registers the game never touches is how RAMBO-1 stayed "green"
   through four broken versions.
3. **Mesen2 regression test** — subclass `compare.MapperRegressionTestBase`
   (~25 lines): bank-moves-during-boot guard + render-output (OAM/palette/PPUCTRL/
   PPUMASK) byte-compare at frame N. Annotate `@RequiresMesen2`; add the class to the
   `mesenTests` list in `build.gradle.kts`. Pick a **static-screen** capture frame —
   animated screens swap CHR banks mid-frame and aren't phase-stable (GxROM lesson).
   Note OAM can be legitimately incomparable when the game's NMI handler rewrites OAM
   between the two capture points (Don Doko Don) — CHR byte-equality is the mapper's
   proof in that case.
4. **For scanline-IRQ mappers: assert WHEN the IRQ fires** — count + scanline vs
   Mesen2 (`tools/mesen-trace/interrupt-counter.lua` vs Nestlin's `Cpu.nmiCount`/
   `irqCount`), not end-of-frame CHR bytes. An end-of-frame compare cannot see a
   mid-frame double-fire (the RAMBO-1 v6 trap: "CHR matched byte-for-byte" while the
   IRQ fired twice per frame).

## Step 4 — Boot verification against the real game

- Run the game; if it doesn't boot, run `./gradlew diverge -Prom=<rom> -Pframe=60`
  FIRST — the DivergenceLocalizer's classification table maps the divergence shape to
  the likely subsystem before you form a hypothesis. Do not start from a guess.
- Update `MAPPER_SUPPORT.md` (games tested, quirks) and close out with the regression
  suite green: `./gradlew test` and `./gradlew testMesenComparison`.
