# NES Mapper Support Status

## Mapper 0 (NROM)
**Status:** Working

- **Games:** Donkey Kong, Super Mario Bros, Mega Man 1-6
- **Behavior:** No bank switching. PRG ROM at $8000 is always PRG[0x0000-0x3FFF]
- **Notes:** Simple, direct mapping. Full support.

## Mapper 1 (MMC1/SxROM)
**Status:** Working

- **Games:** Tetris, Lolo 1, Chip 'n Dale
- **Behavior:**
  - 5-bit shift register for bank switching
  - PRG mode 3 (default): $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
  - PRG mode 2: $8000-$BFFF fixed to bank 0, $C000-$FFFF switchable
  - PRG mode 0/1: 32KB mode (both 16KB banks switch together)
  - CHR ROM loaded via mapper delegate into pattern tables
  - **PRG RAM ($6000-$7FFF):** 8KB RAM supported (Added 2026-04-27)
- **Banking Fix Applied (2026-04-15):** Mode 3 and mode 2 had inverted fixed/switchable assignments per MMC1 spec

## Mapper 2 (CNROM/UNROM)
**Status:** Working

- **CNROM (original):** 8KB CHR bank switching, fixed PRG at $8000-$FFFF
- **UNROM (commercial variant):** 16KB PRG bank switching at $8000-$BFFF via $8000-$FFFF writes
  - Bits 0-2 of value written select the 16KB PRG bank (0-7 for 128KB ROM)
  - $8000-$BFFF: switchable 16KB PRG bank window
  - $C000-$FFFF: fixed to last PRG bank (bank 7 for 128KB, bank 3 for 64KB)
  - Same CHR banking as CNROM (bits 0-1 for CHR bank selection)
- **Games:** Castlevania, Contra, 1943, DuckTales, California Games (UNROM); original CNROM games
- **PRG Banking:** $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
- **CHR Banking:** 8KB banks, switch via bits 0-1 of $8000-$9FFF writes

## Mapper 3 (NINA-003/006)
**Status:** Working

- **Games:** Paperboy
- **Behavior:** 8KB CHR ROM bank switching via $8000-$9FFF (bits 0-1)
- **PRG:** Fixed 32KB at $8000-$FFFF

## Mapper 4 (MMC3/TxROM)
**Status:** Supported

- **Games:** Many later NES games (Mega Man 4-6, StarTropics, Contra, Kirby's Adventure, Crystalis, Bad Dudes, etc.)
- **Behavior:**
  - Dual register pairs at even/odd addresses ($8000/8001, $A000/A001, $C000/C001, $E000/E001)
  - Bit 7 of $8000 = CHR inversion mode (chrPrgInvert)
  - Bit 6 of $8000 = PRG banking mode (prgMode)
  - **PRG RAM ($6000-$7FFF):** 8KB RAM supported with enable/protect bits at $A001 (Added 2026-04-27)
  - PRG banking:
    - $8000-$9FFF: prgBank6 when prgMode=0, prgBankCount-2 when prgMode=1
    - $A000-$BFFF: always prgBankA (R7, switchable)
    - $C000-$DFFF: prgBankCount-2 when prgMode=0, prgBank6 when prgMode=1
    - $E000-$FFFF: always last bank (prgBankCount-1)
  - CHR banking (normal mode): R0-R1 = 2KB at $0000-$0FFF, R2-R5 = four 1KB at $1000-$1FFF
  - CHR banking (inverted mode): R2-R5 = four 1KB at $0000-$0FFF, R0-R1 = 2KB at $1000-$1FFF
  - Scanline IRQ via PPU A12 rising edge detection (wired 2026-04-17)
- **Fixes Applied (2026-05-18):**
  - PPU sprite-fetch pipeline rewritten: sprite evaluation now happens at cycle 65 (no pattern reads) and sprite tile fetches happen at cycles 257-320 of the previous scanline, matching real PPU. Removed eager sprite reads at cycle 0 that were producing a second spurious A12 rising edge per scanline.
  - 8x16 sprite mode: pattern table now resolved per-sprite (PPUCTRL bit 3 was being used for all sprites, which is only correct for 8x8 mode).
  - Result: MMC3 IRQ counter now clocks at exactly 241 A12 rising edges per frame (240 visible + 1 pre-render), down from ~480. Validated by `A12EdgeRateTest`.
- **Fixes Applied (2026-04-23):**
  - IRQ reload at $C001: Removed immediate `irqCounter = valueInt`; reload now correctly defers to next A12 rising edge per NESdev spec
- **Fixes Applied (2026-04-17):**
  - PRG banking: Fixed inverted prgMode branches per NESdev MMC3 spec
  - CHR banking: Fixed R0/R1 bank calculation per NESdev spec
  - IRQ counter reload: Fixed per NESdev spec - reload occurs when counter==0 OR reload flag is set
  - A12 edge detection: Wired from PPU pattern table access to mapper via a12EdgeListener

## Mapper 9 (MMC2 / PxROM)
**Status:** Working (Added 2026-05-20)

- **Games:** Mike Tyson's Punch-Out!! / Punch-Out!! (the only commercial NES game using MMC2)
- **Behavior:**
  - PRG: 8KB switchable bank at `$8000-$9FFF` (selected by writes to `$A000-$AFFF`, low 4 bits); `$A000-$FFFF` fixed to the last three 8KB banks in order.
  - CHR: two 4KB windows (`$0000-$0FFF`, `$1000-$1FFF`), each with a 2-state latch (`FD`/`FE`) that selects one of two CHR bank registers.
  - Latch transitions fire on PPU pattern-table reads at coarse tile-row boundaries:
    - Read in `$0FD8-$0FDF` → `latch0 = FD`
    - Read in `$0FE8-$0FEF` → `latch0 = FE`
    - Read in `$1FD8-$1FDF` → `latch1 = FD`
    - Read in `$1FE8-$1FEF` → `latch1 = FE`
  - **Read-then-flip ordering is critical:** the triggering read returns data from the current bank, and the latch flip affects only subsequent reads in that window.
  - Mirroring: writes to `$F000-$FFFF` (bit 0) select vertical (0) or horizontal (1).
  - No IRQ.
- **Implementation notes:** MMC2's latch is the only mechanism in the codebase where CHR banking is driven by PPU read addresses rather than CPU writes. The same latch design generalises to Mapper 10 (MMC4) — only PRG banking granularity differs.
- **Verification:** `Mapper9Test` covers PRG fixed-bank invariant, PRG bank select, both CHR latches with read-then-flip ordering, latch independence, latch trigger range, and mirroring control.

## Mapper 10 (MMC4 / FxROM)
**Status:** Working (Added 2026-05-23)

- **Games:** Fire Emblem Gaiden, Famicom Wars (the only two commercial games using MMC4)
- **Behavior:**
  - PRG: a single switchable 16KB bank at `$8000-$BFFF` (selected by writes to `$A000-$AFFF`, low 4 bits); `$C000-$FFFF` fixed to the last 16KB bank.
    (This is the only difference from MMC2/Mapper 9, which instead switches an 8KB window and fixes the last three 8KB banks.)
  - 8KB PRG RAM at `$6000-$7FFF`, exposed as battery-backed via `batteryBackedRam()`/`batteryDirty`. The Nestlin layer gates actual `.sav` persistence on `Header.hasBattery`, so Fire Emblem Gaiden persists saves while Famicom Wars (no battery) stays volatile.
  - CHR: two 4KB windows (`$0000-$0FFF`, `$1000-$1FFF`), each with a 2-state latch (`FD`/`FE`) selecting one of two CHR bank registers. **Identical to MMC2.**
  - Latch transitions fire on PPU pattern-table reads at coarse tile-row boundaries:
    - Read in `$0FD8-$0FDF` → `latch0 = FD`; `$0FE8-$0FEF` → `latch0 = FE`
    - Read in `$1FD8-$1FDF` → `latch1 = FD`; `$1FE8-$1FEF` → `latch1 = FE`
  - **Read-then-flip ordering is critical:** the triggering read returns data from the current bank; the latch flip affects only subsequent reads in that window.
  - Mirroring: writes to `$F000-$FFFF` (bit 0) select vertical (0) or horizontal (1).
  - No IRQ.
- **Implementation notes:** `Mapper10` shares MMC2's CHR-latch design verbatim (see [[mmc2-mmc4-latch-read-then-flip-ordering-2026-05-20]]); only `cpuRead`'s PRG geometry and the added PRG RAM differ from `Mapper9`.
- **Verification:**
  - `Mapper10Test` — PRG fixed-bank invariant, 16KB switchable window, low-4-bit bank select, PRG RAM read/write, both CHR latches with read-then-flip ordering, latch independence, latch trigger range, mirroring control.
  - `Mapper10RegressionTest` (Mesen-comparison group) — boots Fire Emblem Gaiden on the real ROM and asserts (1) the MMC4 PRG bank actually pages during boot, and (2) Nestlin's render-output state (OAM, palette, PPUCTRL, PPUMASK) is byte-identical to the Mesen2 oracle at frame 120.

## Mapper 7 (AxROM)
**Status:** Working (Verified 2026-04-27)

- **Games:** Marble Madness, R.C. Pro-Am, Battletoads, Arch Rivals, Solar Jetman, Cobra Triangle, Beetlejuice
- **Behavior:**
  - 32KB PRG ROM bank switching via writes to `$8000-$FFFF` (bits 0-2 select bank)
  - 8KB CHR RAM (not ROM) — fully writable pattern table memory
  - Single-screen mirroring controlled by bit 3 of bank write (0=lower screen, 1=upper screen)
- **Notes:** AxROM bankswitches on writes to the **entire** `$8000-$FFFF` range (unlike UNROM/Mapper 2 which ignores `$C000-$FFFF` writes)
- **Verification:** Marble Madness and R.C. Pro-Am pass `MapperVerificationTest` headless rendering tests

---

## Mapper 11 (Color Dreams)
**Status:** Working (Fixed 2026-04-17)

- **Games:** Bible Adventures, Action 52, Crystalis (some versions)
- **Behavior:** CHR bank switching via bits 4-7 of $8000-$FFFF writes, PRG fixed at $8000-$FFFF
- **Bugs Fixed (2026-04-17):**
  1. CHR banking was reading bits 0-1 (PRG bank field) instead of bits 4-7 (CHR bank field)
  2. PRG banking was fixed to bank 0 only; now properly switches 32KB PRG banks via bits 0-1
- **Format (per NESdev wiki):** `CCCC LLPP` where CCCC = 8KB CHR bank (bits 4-7), LL = unused/lockout, PP = 32KB PRG bank (bits 0-1)
- **Verified:** Bible Adventures now shows gameplay content (was all-black before fix)

---

## Mapper 69 (Sunsoft FME-7 / 5A / 5B)
**Status:** Working — NTSC titles (Added 2026-05-29)

- **Games:** Batman: Return of the Joker, Gimmick! (Mr. Gimmick), Gremlins 2, Hebereke
- **Register protocol:** command/parameter pair.
  - `$8000-$9FFF` Command register — low 4 bits select the command.
  - `$A000-$BFFF` Parameter register — writing here invokes the selected command.
- **Commands:**
  - `$0-$7`: CHR bank for one of the eight 1KB windows (`$0000-$1FFF`), 8-bit bank number.
  - `$8`: `$6000-$7FFF` bank — bits 0-5 = bank, bit 6 = RAM(1)/ROM(0) select, bit 7 = RAM enable.
    When RAM is selected but disabled the region is open bus (power-on write protection).
  - `$9/$A/$B`: 8KB PRG banks for `$8000` / `$A000` / `$C000` (bits 0-5). `$E000-$FFFF` is fixed to the last bank.
  - `$C`: mirroring — 0=Vertical, 1=Horizontal, 2=1-screen lower, 3=1-screen upper.
  - `$D`: IRQ control — bit 7 = counter-enable (decrement), bit 0 = IRQ-enable (assert line).
    Any write to `$D` also acknowledges a pending IRQ.
  - `$E`/`$F`: IRQ counter low / high byte.
- **IRQ (the differentiator vs MMC3):** a 16-bit counter decremented once per CPU (M2)
  cycle. When it underflows from `$0000` to `$FFFF` an IRQ is generated (if IRQ-enable is
  set). This is the first CPU-cycle-clocked mapper IRQ in Nestlin: the `Mapper` interface
  gained `tickCpuCycle()` (default no-op; A12-clocked mappers like MMC3 ignore it) and
  `Cpu.tick()` calls it exactly once per CPU cycle. The line is held until software writes
  command `$D`, so `acknowledgeIrq()` is intentionally **not** overridden.
- **5B expansion audio** (`$C000-$FFFF`, three square channels) is out of scope and ignored
  — a follow-up that depends on the expansion-audio mixer API (see issue #51).
- **Verification:**
  - `Mapper69Test` — PRG/CHR banking, `$6000` RAM/ROM enable+protect, mirroring, the full
    16-bit per-cycle IRQ (load, enable/disable gating, underflow, acknowledge), and save/load.
  - `Mapper69BootRenderTest` — Batman (USA) boots, enables rendering, draws substantial
    content, and drives the FME-7 IRQ.
- **PAL ROMs:** Nestlin now has a PAL timing core (issue #77 — `Region.kt`, 3.2:1 PPU:CPU
  ratio, PAL APU tables; auto-detected from header/filename, overridable, fully unit-tested).
  Validated end-to-end on a real PAL game with a simple mapper: **Kick Off (Europe)**
  auto-detects PAL and boots to a rendered screen (rendering on by frame 10, PPUMASK $FE) —
  see `KickOffPalSmokeTest`.
- **Gimmick! still does not boot — separate FME-7 IRQ bug, not the PAL core.** Diagnosis
  (2026-05-30): the only local dump (European/PAL) sets up the FME-7 IRQ during early init
  (irqEnable=1, counter running), then spins forever at `$F2B8` (`LDA $F0 / BNE`) with the IRQ
  disabled, waiting for zero-page `$F0` to be cleared by an interrupt-driven routine (~`$E9C6`)
  that never runs. Same under forced NTSC. OAM DMA is NOT involved (zero `$4014` transfers).
  Control ROMs kirby/lolo1/tetris and **Batman (same FME-7 mapper, NTSC)** all boot through the
  same harness, so this is mapper-69/raster-IRQ-specific, not a PAL-timing or harness issue.
  Harness `GimmickPalBootTest` is in place but `@Ignore`d until the FME-7 IRQ behaviour is fixed.

---

## Battery-Backed Save RAM (.sav files)

**Status:** Supported on mappers 1, 4, 5 (Added 2026-05-23)

- **Format:** Raw bytes, no header. Bit-for-bit compatible with FCEUX / Nestopia / Mesen / Mesen2.
- **Location:** `saves/<rom-basename>.sav` next to the working directory.
- **Trigger:** iNES header byte 6 bit 1 (battery flag). When unset, no `.sav` file is created even if the cartridge has PRG-RAM.
- **Flush policy:** every 10 seconds if PRG-RAM is dirty, plus on clean shutdown, ROM switch, and hard reset. Atomic file replace (write `.sav.tmp` then move) keeps the existing save intact if the JVM dies mid-write.
- **Coverage:** Mapper 1 (MMC1 — Zelda, Final Fantasy, Kid Icarus), Mapper 4 (MMC3 — Mega Man 4-6, StarTropics, Crystalis), Mapper 5 (MMC5 — Castlevania III when battery-flagged). Mappers without `$6000-$7FFF` PRG-RAM (0, 2, 3, 7, 9, 11, 34) cannot host battery saves and are unaffected.
- **Known limitations:**
  - PRG-RAM size is treated as 8 KB regardless of iNES byte 8 / NES 2.0 byte 10. Games with 16/32 KB battery RAM (some MMC5 titles) won't save their full state.
  - MMC5 extended RAM (`$5C00-$5FFF`) is not persisted. Only the `$6000-$7FFF` PRG-RAM window is.
  - FDS (Famicom Disk System) saves use the `.fds` disk-image format and are out of scope.

## Adding New Mappers

1. Implement in `gamepak/` directory
2. Extend `Mapper` base class or trait
3. Update `GamePak.kt` to instantiate new mapper
4. Add entry to `MAPPER_SUPPORT.md`
5. Test with known-good ROM
6. If the mapper has `$6000-$7FFF` PRG-RAM, override `batteryBackedRam()` and set `batteryDirty = true` on successful writes (see `Mapper1.kt`, `Mapper4.kt`).
