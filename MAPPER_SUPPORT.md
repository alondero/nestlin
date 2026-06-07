# NES Mapper Support Status

Active mapper list: **0, 1, 2, 3, 4, 5 (stub), 7, 9, 10, 11, 16, 33, 34, 66, 69, 153, 206.**

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

## Mapper 16 (Bandai FCG / FCG-1 / FCG-2 / LZ93D50)
**Status:** Working (Added 2026-06-02, issue #86)

- **Games:** Dragon Ball Z series (DBZ RPG, DBZ II), Saint Seiya, SD Gundam Gaiden,
  Famicom Jump II (submapper 5), Magical Taruruuto-kun 2 (submapper 5), and most
  Bandai-published cartridges with on-board save EEPROM.
- **Submapper variants** (NES 2.0 byte 8 high nibble; iNES 1.0 falls back to 0):
  - **0 (default):** registers at `$8000-$FFFF`, no PRG-RAM, no EEPROM.
    Writes to `$6000-$7FFF` are open bus (ignored) for the chip. **For iNES
    1.0 dumps (which carry no submapper byte) Nestlin also accepts writes
    to `$6000-$7FFF`** — see the "iNES 1.0 submapper ambiguity" note below.
  - **4 (FCG-1/2):** registers at `$6000-$7FFF`. `$8000-$FFFF` is normal PRG.
    IRQ writes to `$B/$C` are *direct* (no latch).
  - **5 (LZ93D50):** registers at `$8000-$FFFF`. `$6000-$7FFF` is unused.
    `$D` exposes a 24C02 EEPROM via the standard I²C bit-bang protocol
    (SCL = bit 5, SDA = bit 6 of `$D`).
- **iNES 1.0 submapper ambiguity:** many NO-INTRO Bandai FCG dumps ship as
  plain iNES 1.0 (no NES 2.0 submapper byte), but actual games use both
  register windows. Crayon Shin-chan: Ora to Poi Poi writes to `$8008-$800A`
  (the submapper-0 default). Dragon Ball: Daimaou Fukkatsu writes to
  `$7FF0-$7FF9` (the submapper-4 narrow window). Both decode the same way
  on a real chip — the chip's register-decode is fixed, the board just wires
  it to one of the two windows. To make both boot, Nestlin's submapper 0/5
  decode **mirrors register writes to BOTH `$6000-$7FFF` and `$8000-$FFFF`**.
  This is safe because for submapper 0 there is no PRG-RAM in either window
  — any open-bus writes would have been discarded by the chip anyway. If
  the iNES header is upgraded to NES 2.0 with byte 8 high nibble = 4, the
  narrow window is used exclusively.
- **Register map** (selected by lower 4 bits of the write address):
  - `$0`..`$7`: CHR bank for one of eight 1KB windows (`$0000-$1FFF`).
  - `$8`: PRG page select (low 4 bits select one of sixteen 16KB banks).
  - `$9`: Mirroring (bits 0-1: 0=Vert, 1=Horiz, 2=1-screen lower, 3=1-screen upper).
  - `$A`: IRQ enable (bit 0); any write reloads the counter from the `$B:$C` latch.
  - `$B`: IRQ latch low byte.
  - `$C`: IRQ latch high byte.
  - `$D`: EEPROM I²C port (submapper 5 only).
- **PRG layout (16KB granularity):**
  - `$8000-$BFFF`: switchable 16KB bank selected by `$8`.
  - `$C000-$FFFF`: the last 16KB bank (always `prgBankCount - 1`).
- **CHR layout:** eight independent 1KB pages. No CHR-RAM fallback.
- **IRQ:** 16-bit counter, decremented once per CPU (M2) cycle when bit 0 of `$A`
  is set. The counter wraps from `$0000` to `$FFFF` and asserts IRQ on that
  underflow. Any write to `$A` reloads from the latch. Writing `$A` with
  bit 0 = 0 disables the IRQ and acknowledges a pending request.
- **EEPROM (submapper 5 only):** the 24C02 I²C EEPROM is exposed at `$D`. The
  bus is a single 8-bit register: bit 5 = SCL, bit 6 = SDA. Writing `$D`
  drives the bus; reading `$D` returns the slave's SDA (so the master can
  clock data out for reads). Standard 2-wire protocol is implemented in
  `BandaiEeprom.kt` — start/stop/ack/data, device address `0x50`, 8-bit word
  address, sequential-read wrap. This is the same chip the LZ93D50 board
  uses for game saves (replacing the battery-backed PRG-RAM that MMC3
  boards use for the same purpose).
- **Verification:**
  - `Mapper16Test` covers PRG banking (low 4 bits, both register windows for
    submapper 0, narrow window for submapper 4, last-bank-fixed), CHR
    banking (all eight 1KB windows), mirroring, the 16-bit CPU-cycle IRQ
    (load via latch, decrement, underflow, acknowledge), and save/load
    round-trip.
  - Submapper-4 narrow register window and direct IRQ writes are tested
    explicitly.
  - Submapper-5 enables the EEPROM, which is exercised with a bit-banged
    24C02 write-then-random-read at `$800D`.
  - `Mapper16RealGameBootTest` is the regression bar: runs Crayon
    Shin-chan and Dragon Ball through 240 frames and asserts both reach
    the title screen (CHR banking active, >200K instructions executed).
  - `Mapper16ScreenshotTest` captures frames 60, 300, 1500 of both games
    to `build/reports/bandai-fcg-screenshots/` for visual inspection.

---

## Mapper 33 (Taito TC0190 / TC0350)
**Status:** Working (Added 2026-06-07, issue #131)

- **Games:** Don Doko Don, Akira, *Power Blazer*, *Operation Wolf*, and the Famicom port of *Bubble Bobble*. Roughly 12 Taito-published Famicom titles in total.
- **What it is:** a discrete-logic Taito mapper — an MMC3-class chip *without* an IRQ counter. Closer in spirit to the Bandai FCG family (multi-bank CHR, 8KB PRG slots) than to UNROM-style mappers. The register-decode (`addr & 0xA003`, picking the register from bit 13 plus bits 0-1) is the same shape used by many discrete-logic mappers of the era.
- **Register layout (eight registers, decoded via `addr & 0xA003`):**
  - `$8000` — **PRG bank 0** (low 6 bits) + **mirroring** (bit 6: 0=Vert, 1=Horiz).
  - `$8001` — **PRG bank 1** (low 6 bits).
  - `$8002` — **CHR pair** for `$0000-$07FF` (2KB bank number).
  - `$8003` — **CHR pair** for `$0800-$0FFF` (2KB bank number).
  - `$A000`..`$A003` — **CHR 1KB banks** for `$1000-$1FFF` (one register per 1KB page).
  - The `$Axxx` registers decode across the whole `$A000-$BFFF` range, so e.g. `$BFFE` hits `$A002`. Same for `$8xxx`.
- **PRG geometry (8KB granularity, 4 banks):**
  - `$8000-$9FFF` — switchable via `$8000` (low 6 bits, modulo PRG-bank count).
  - `$A000-$BFFF` — switchable via `$8001` (low 6 bits, modulo PRG-bank count).
  - `$C000-$DFFF` — fixed to `prgBankCount - 2` (second-to-last 8KB bank).
  - `$E000-$FFFF` — fixed to `prgBankCount - 1` (last 8KB bank).
  - The "fixed at second-to-last / last" pair is the same trick used by Mapper 16, Mapper 2 (UNROM), and the half-MMC3 family. No PRG-RAM.
- **CHR geometry (mixed 2KB+1KB granularity, 8 banks in 1KB units):**
  - `$0000-$03FF`, `$0400-$07FF` — 1KB pair set by `$8002` as `(v*2, v*2+1)`. The LSB of the written value is *preserved* (unlike MMC3 R0/R1, which mask it off). NESdev is explicit: "the value written for the two 2 KiB CHR banks do not drop the LSB."
  - `$0800-$0BFF`, `$0C00-$0FFF` — same scheme via `$8003`.
  - `$1000-$13FF`..`$1C00-$1FFF` — four 1KB pages, one register each, via `$A000`..`$A003`.
- **Mirroring:** bit 6 of the most recent `$8000` write. There is no separate mirroring register — the chip latches it on every `$8000` write. Initial value at power-on is the iNES header's mirroring bit. No 1-screen mode.
- **No IRQ. No PRG-RAM.** (The TC0350 *variant* — separate iNES mapper number 48 — adds a scanline IRQ and is out of scope here.)
- **Implementation notes:**
  - The `addr & 0xA003` mask is the key thing to get right; bits 2-12 of the address are dropped. Every existing test that doesn't have an explicit aliasing check relies on this implicitly, so `Mapper33Test` covers it with `$8042`, `$8FF2`, `$9FFA`, `$A040`, `$BFFE` aliases.
  - The mirroring flag is stored as a `Boolean` (not a nullable mirroring mode) so the header's initial value drives the boot state until the game writes `$8000` for the first time.
  - `prgBankCount` is `coerceAtLeast(1)` so a malformed 0-PRG ROM (truncated dump) can't make the fixed-bank read path compute a negative index.
  - CHR RAM fallback for 0KB-CHR dumps (same pattern as Mapper 2 / 3 / 11 / 71) so homebrew ROMs don't crash on PPU read.
- **Verification:**
  - `Mapper33Test` covers dispatch from the iNES header, default PRG state (penultimate-at-`$C000`, last-at-`$E000`), `prgBank0`/`prgBank1` selection via the low 6 bits (and high bits being ignored), single-bank wrap, the `addr & 0xA003` register aliasing, the 2KB pair decode at `$8002`/`$8003` (with LSB preserved, distinguishing it from MMC3 R0/R1), the four 1KB registers at `$A000`..`$A003`, mirroring header-driven initial state and bit-6-driven override, mirroring independence from the PRG field, mirror-write isolation (`$8001`/`$8002`/`$8003`/`$Axxx` writes don't change mirroring), CHR-RAM fallback, save/load round-trip, and `snapshot()` reporting of all eight CHR banks plus the mirroring flag.
  - `Mapper33RegressionTest` (Mesen-comparison group) — boots *Don Doko Don (Japan)* on the real ROM. Asserts (1) the TC0190 PRG bank actually pages during boot (proves `$8000`/`$8001` are wired, the cheap "did the mapper do anything" guard from the Star Soldier recipe), and (2) Nestlin's CHR pattern-table is **byte-identical** to Mesen2's across all eight 1KB windows at frame 60. The full OAM/palette/PPUCTRL comparison is reported alongside for human inspection but not asserted, because Don Doko Don's NMI handler rewrites OAM between Mesen2's pre-NMI capture (scanline 240) and Nestlin's post-NMI capture (scanline 261) — the same documented cross-emulator offset as `BigNoseHangTest` / `MicroMachines...`, and not a TC0190 bug. The decisive evidence is the **byte-identical CHR section** in the diff report at `build/reports/state-diffs/don-doko-don-frame-60/diff-report.txt`.

---

## Mapper 153 (Bandai FCG variant)
**Status:** Working (Added 2026-06-02, issue #86)

- **Games:** Famicom Jump II (Europe) and other Bandai titles that wire the
  FCG silicon with an 8KB PRG-RAM window at `$6000-$7FFF` instead of using
  EEPROM. Same physical chip as mapper 16, different board.
- **What it is:** mapper 16 with one extra register that toggles `$6000-$7FFF`
  between PRG-RAM (8KB) and an extra PRG bank.
- **Register map:** identical to mapper 16 submapper 0/5 (registers at
  `$8000-$FFFF`; with iNES 1.0 ambiguity also at `$6000-$7FFF`) plus:
  - `$D`: bit 7 selects the source of `$6000-$7FFF`:
    - bit 7 = 0: `$6000-$7FFF` reads/writes go to 8KB PRG-RAM.
    - bit 7 = 1: `$6000-$7FFF` reads come from a 16KB PRG bank
      (low 4 bits of `$D` select the bank; only the low 8KB is visible).
    Writes to `$6000-$7FFF` while bit 7 = 1 are ignored (masked off), so
    games can use bit 7 as a write-protect.
- **PRG layout (16KB granularity):** same as mapper 16 — `$8000-$BFFF` is
  switchable via `$8`, `$C000-$FFFF` is the last 16KB bank.
- **CHR layout:** eight 1KB pages (same as mapper 16).
- **IRQ:** identical to mapper 16 (16-bit counter, CPU-cycle clocked,
  latch-then-reload-on-`$A`).
- **PRG-RAM persistence:** when the iNES header's battery bit is set, the
  8KB PRG-RAM is exposed via `batteryBackedRam()` and writes set
  `batteryDirty`, so `.sav` persistence works the same as for Mapper 1/4/206.
- **Verification:** `Mapper153Test` covers PRG banking, the `$D` bit-7
  PRG-RAM-vs-PRG toggle (with the write-mask behaviour), CHR banking,
  mirroring, the full 16-bit IRQ, battery-backed RAM, and save/load
  round-trip.
**Status:** Working (Fixed 2026-04-17)

- **Games:** Bible Adventures, Action 52, Crystalis (some versions)
- **Behavior:** CHR bank switching via bits 4-7 of $8000-$FFFF writes, PRG fixed at $8000-$FFFF
- **Bugs Fixed (2026-04-17):**
  1. CHR banking was reading bits 0-1 (PRG bank field) instead of bits 4-7 (CHR bank field)
  2. PRG banking was fixed to bank 0 only; now properly switches 32KB PRG banks via bits 0-1
- **Format (per NESdev wiki):** `CCCC LLPP` where CCCC = 8KB CHR bank (bits 4-7), LL = unused/lockout, PP = 32KB PRG bank (bits 0-1)
- **Verified:** Bible Adventures now shows gameplay content (was all-black before fix)

## Mapper 66 (GxROM)
**Status:** Working (Added 2026-05-30, register decode fixed 2026-05-31)

- **Games:** Super Mario Bros. + Duck Hunt, Dragon Power, Gumshoe, Doraemon
- **Behavior:** Bank-select register (`xxPP xxCC`, written anywhere in $8000-$FFFF):
  - 32KB PRG banks switched via **bits 4-5**
  - 8KB CHR banks switched via **bits 0-1**
  - No PRG RAM
  - Fixed mirroring from iNES header
- **Notes:** Simple discrete mapper. PRG window at $8000-$FFFF is one 32KB bank. CHR banks are 8KB. The initial implementation decoded PRG as bits 0-2 / CHR as bits 3-4, which corrupted graphics (Gumshoe, SMB+Duck Hunt) and stopped some games booting (Doraemon).

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
- **Gimmick! (Mr. Gimmick, Europe) boots** (`GimmickPalBootTest`, fixed 2026-06-01, issue #82).
  The earlier "separate FME-7 IRQ bug" diagnosis was **wrong**. Verified against a Mesen2
  `--testRunner` capture: Gimmick's boot is driven by its **NMI** handler (`$E9C6`), not the
  FME-7 IRQ — it *enables* NMI (`$2000=$B0`) and fires an NMI every frame; **zero** FME-7 IRQs
  fire during boot (the per-frame NMI reloads the IRQ counter to `$FFFF` before it can underflow).
  The real bug was in the **PPU**, not this mapper: the CPU-visible `nmiOccurred` latch was not
  cleared at the pre-render scanline (only PPUSTATUS bit 7 was), so a stale latch from a vblank
  the game never acknowledged via `$2002` fired a spurious immediate NMI the moment the game
  enabled NMI mid-frame — and that mistimed NMI disabled NMI for good, hanging the boot spin at
  `$F2B8`. Fixed in `PpuAddressedMemory.clearVBlankAtPreRender` (regression: `PpuVblankTimingTest`).
  A `$D` "sticky enable latch" workaround added for this issue was reverted to hardware-accurate
  (live, non-latched) bits, since it was both wrong and would spuriously re-fire one-shot raster
  IRQs in Batman / Gremlins 2.

---

## Mappers 21, 23, 25 (Konami VRC4)
**Status:** Working — register-level (Added 2026-06-02)

- **Games:**
  - **Mapper 21:** Wai Wai World 2: SOS!! Parsley Jō, Ganbare Goemon Gaiden 2
  - **Mapper 23:** Wai Wai World, Salamander, Crisis Force, Parodius Da!
  - **Mapper 25:** Gradius II, Bio Miracle Bokutte Upa, Teenage Mutant Ninja Turtles (Famicom),
    Ganbare Goemon Gaiden, Moero!! Pro Tennis
- **What it is:** the Konami VRC4 chip — fine-grained banking and a configurable
  IRQ counter that drives many of Konami's mid-to-late Famicom hits. The three
  iNES mapper numbers are the *same chip*; the differentiator is which CPU
  address pins drive the chip's register-select inputs, which Konami varied
  across PCB designs. Concretely:

  | Mapper | Submappers | Sub-bit 0 ← | Sub-bit 1 ← | Register address pattern             |
  |--------|------------|-------------|-------------|--------------------------------------|
  | 21     | VRC4a      | A1          | A2          | `$x000, $x002, $x004, $x006`         |
  | 21     | VRC4c      | A6          | A7          | `$x000, $x040, $x080, $x0C0`         |
  | 23     | VRC4f      | A0          | A1          | `$x000, $x001, $x002, $x003`         |
  | 23     | VRC4e      | A2          | A3          | `$x000, $x004, $x008, $x00C`         |
  | 25     | VRC4b      | A1          | A0 (swap!)  | `$x000, $x002, $x001, $x003`         |
  | 25     | VRC4d      | A3          | A2 (swap!)  | `$x000, $x008, $x004, $x00C`         |

  Without NES 2.0 submapper info we can't pick between each pair, so we
  **OR both candidate sub-register decodes** — VRC4f games only ever touch
  address bits 0-1 (bits 2-3 are always zero in their writes) and VRC4e games
  only ever touch bits 2-3, so the OR is unambiguous in practice. Same trick
  for the other pairs. This is what FCEUX and Nintaco do.
- **Registers** (canonical layout, with `sub` = decoded 0..3):
  - `$8xxx`     PRG bank 0 select — 5-bit 8KB bank for `$8000` (or `$C000` in swap mode).
  - `$9xxx sub 0/1` Mirroring — bits 0-1: 0=Vertical, 1=Horizontal, 2=1-screen-lower, 3=1-screen-upper.
  - `$9xxx sub 2/3` PRG mode + WRAM enable — bit 0 = WRAM enable, bit 1 = PRG swap mode.
  - `$Axxx`     PRG bank 1 select — 5-bit 8KB bank for `$A000`.
  - `$Bxxx-$Exxx` CHR bank selects — each group (`$B`/`$C`/`$D`/`$E`) hosts two
    9-bit CHR bank registers. Sub 0/2 writes the low nibble (4 bits), sub 1/3
    writes the high nibble (5 bits). That gives 8 × 1KB CHR windows with
    9-bit bank indices (512 KB CHR max).
  - `$Fxxx sub 0` IRQ latch low nibble.
  - `$Fxxx sub 1` IRQ latch high nibble.
  - `$Fxxx sub 2` IRQ control — `....MEA`: M = cycle (1) / scanline (0) mode,
    E = enable, A = "enable after acknowledge". Writing acks pending IRQ; if
    E=1 the counter reloads from the latch and the prescaler resets to 341.
  - `$Fxxx sub 3` IRQ acknowledge — clears pending and copies A→E.
- **PRG geometry:**
  - `$8000-$9FFF`: prg0 (swap mode 0) or second-to-last bank (swap mode 1).
  - `$A000-$BFFF`: prg1 (always switchable via `$Axxx`).
  - `$C000-$DFFF`: second-to-last bank (swap mode 0) or prg0 (swap mode 1).
  - `$E000-$FFFF`: last bank (always fixed).
- **WRAM:** 8KB at `$6000-$7FFF`, gated by the WRAM enable bit at `$9002`.
  When disabled, reads return 0 and writes are dropped. When enabled, the
  page is exposed via `batteryBackedRam()`; the Nestlin layer gates actual
  `.sav` persistence on `Header.hasBattery`.
- **IRQ (the VRC IRQ — second CPU-cycle-clocked mapper IRQ in Nestlin):** an
  8-bit counter that *increments* (not decrements) each step. When it wraps
  `$FF` → reload, the IRQ line is asserted (if E=1). Two clock sources:
  - **Cycle mode (M=1):** counter clocks every CPU cycle.
  - **Scanline mode (M=0):** counter clocks roughly once per scanline. We
    implement the NESdev-spec integer prescaler — start at 341, subtract 3
    each CPU cycle, and clock the counter whenever the prescaler crosses
    zero (adding 341 back). 341 / 3 = 113.66 → the 114→114→113 cycle rhythm
    that real VRC4 hardware uses.
- **Verification:**
  - `Vrc4Test` — PRG fixed-bank invariant, PRG bank 0/1 switching across both
    swap modes, WRAM gating + dirty flag, 1KB CHR bank switching, 9-bit CHR
    bank addressing via the low+high nibble protocol, CHR RAM fallback, all
    four mirroring modes + header fallback, IRQ in both cycle and scanline
    modes (including the exact 114-cycle prescaler boundary), the
    enable/acknowledge protocol with A→E copy, save/load round-trip, and
    version-byte rejection.
  - `Vrc4VariantDecodeTest` — each submapper's address-pin layout is exercised
    independently by issuing a sub-register write at the variant's canonical
    address and asserting the corresponding internal effect (mirroring change
    or WRAM enable).
  - End-to-end ROM boot/render verification is left for a follow-up Mesen2
    comparison — Gradius II (Mapper 25) and Salamander (Mapper 23) are the
    natural oracles.

---

## Mapper 71 (Camerica / Codemasters / BF909x)
**Status:** Working (Added 2026-06-02, issue #88)

- **Games:** Micro Machines, Dizzy series (Dizzy: The Ultimate Cartoon Adventure, Fantasy World Dizzy, etc.), Linus Spacehead's Cosmic Crusade, Bee 52, Big Nose the Caveman, Firehawk, and the rest of the Camerica / Codemasters unlicensed library.
- **What it is:** the BF909x chip, an UNROM-shaped mapper with two important
  differences from Mapper 2:
  - The PRG bank field is the **whole byte** (no mask), supporting up to 256
    16 KB PRG banks. The selected bank is taken modulo the PRG-bank count.
  - Mirroring is **software-switchable** via a separate write, in a
    game-detected "firehawk" sub-mode.
- **PRG banking (default mode):**
  - `$8000-$BFFF` — switchable 16 KB bank. Whole write byte is the bank number.
  - `$C000-$FFFF` — fixed to the last 16 KB bank.
  - Writes anywhere in `$8000-$FFFF` do the bank select.
  - Mirroring follows the iNES header.
- **Firehawk (BF9097) sub-mode:** auto-engaged the first time the game writes
  to `$9000` (any value, including zero — only the address matters). Once
  latched, the chip **re-shapes** its register layout:
  - `$8000-$BFFF` writes become **1-screen mirroring** writes. **Bit 4**
    selects the screen: `0` = lower (`$2000`), `1` = upper (`$2400`). All
    other bits are ignored.
  - `$C000-$FFFF` writes continue to select the 16 KB PRG bank.
  - This is sticky — there is no path back to default mode.
- **CHR:** a single fixed 8 KB page mapped across `$0000-$1FFF`. No CHR
  banking. Dumps that ship 0 KB of CHR get 8 KB of CHR RAM (writable).
- **No IRQ. No PRG-RAM.**
- **Implementation notes:** the `$9000` latch is implemented as a single
  `bf9097Mode: Boolean` that flips on first hit; the live mirroring override
  is a nullable Boolean so the iNES header drives `currentMirroring()` until
  the game latches firehawk mode (the snapshot reports this as `-1`).
- **Verification:** `Mapper71Test` covers
  - mapper dispatch from header byte 6/7 = `0x47`,
  - default-mode PRG banking (initial state, single-bank wrap, write to
    `$8000`, `$C000`, and `$FFFF`, all 16 KB banks reachable, oversized bank
    numbers wrapping modulo PRG count),
  - fixed 8 KB CHR (no banking even after a PRG bank change) and the
    0 KB CHR → 8 KB CHR-RAM fallback,
  - default mirroring tracks the iNES header (horizontal *and* vertical),
  - firehawk latch on `$9000` (and that the latch write is not mistaken for
    a PRG bank select),
  - firehawk-mode `$8000-$BFFF` writes set mirroring without disturbing PRG,
  - firehawk mirroring bit 4 lower/upper + bit-mask isolation,
  - firehawk `$C000-$FFFF` still selects PRG,
  - one-way firehawk latch,
  - `saveState`/`loadState` round-trip of PRG bank, firehawk latch, and
    mirroring override (and CHR RAM when present),
  - `snapshot()` reports the right `prgBank`, `bf9097Mode`, and
    `firehawkMirrorUpper` for both states.
- **End-to-end ROM verification** (Micro Machines booting) is left for a
  follow-up Mesen2 comparison once a test ROM is sourced; the unit-test
  coverage above is sufficient to drive the spec correctly.
  - *Updated 2026-06-02:* end-to-end Mesen2 state comparison at frame 120
    is **MATCH** for CHR, palette, and OAM. A separate OAM-DMA halt-cycle
    bug in `Memory.kt` was uncovered while validating the gameplay path —
    see `MemoryOamDmaTest`. Fixing it materially reduced the per-frame CPU
    drift in OAM-heavy games.
  - *Updated 2026-06-03:* two further (non-mapper) bugs surfaced on this
    family were fixed. (1) The title-screen **"band"** was a PPU forced-blank
    bug — a disabled PPU must still scan out the backdrop colour
    (`PpuForcedBlankBackdropTest`); it was NOT the OAM-DMA drift. (2)
    **Big Nose the Caveman** booted to a black screen because the CPU
    dispatched NMI with no latency, starving the game's `$2002` vblank-poll;
    fixed with a 1-instruction NMI latency (`BigNoseHangTest`). Big Nose now
    boots fully. **Micro Machines still hangs when starting a race** —
    tracked separately as issue #113 (a different, gameplay-only timing bug;
    the mapper itself is verified working).

---

## Mapper 206 (DxROM / Namcot 108 / Namcot 109 / Namcot 118 / Namcot 119)
**Status:** Working (Added 2026-06-01)

- **Games:** Gauntlet (DRROM — 4-screen, 128 KB PRG, 64 KB CHR), Ring King, RBI Baseball
  (DEROM), Dragon Buster, Pac-Man (Namco), Mappy-Land, Berzerk, and other Namco/Tengen titles.
- **What it is:** a stripped-down MMC3. The Namco 108 family removes the
  configurable bits that the simplified chips don't expose:
  - **PRG mode is hardwired** to MMC3 PRG mode 0: `$8000-$9FFF` = R6 (switchable),
    `$A000-$BFFF` = R7 (switchable), `$C000-$DFFF` = second-to-last bank (fixed),
    `$E000-$FFFF` = last bank (fixed). Bit 6 of the bank-select register is ignored.
  - **CHR mode is hardwired** to MMC3 CHR mode 0: R0/R1 select 2 KB banks at
    `$0000-$0FFF` (low bit ignored, so adjacent 1 KB pages pair), R2-R5 select
    four 1 KB banks at `$1000-$1FFF`. Bit 7 of the bank-select register is ignored.
  - **Mirroring is hardwired** from the iNES header — there is no `$A000` mirroring
    register. (DRROM Gauntlet uses 4-screen, signalled via the header's 4-screen bit;
    the cartridge board wires it physically.)
  - **No scanline IRQ counter** — writes to `$C000`/`$C001` (latch/reload) and
    `$E000`/`$E001` (enable/disable) are accepted as no-ops. Mapper 206 inherits
    Mapper's default `isIrqPending() = false` and `acknowledgeIrq() = {}`.
- **Register protocol:** identical to MMC3 from the CPU's point of view.
  - `$8000` (even address) Bank select — bits 0-2 pick R0-R7. Bits 6-7 are masked off
    in the value before being stored, but the address is decoded normally.
  - `$8001` (odd address) Bank data — low 7 bits become the bank number.
  - The whole `$8000-$FFFF` window collapses to the `$8000`/`$8001` register pair
    (i.e. `addr &= 0x8001`), matching the Mesen reference.
- **PRG-RAM:** 8 KB at `$6000-$7FFF`, always enabled (Namco 108 has no `$A001`
  protect register). When the iNES header has the battery bit set, the page is
  exposed via `batteryBackedRam()` and writes set `batteryDirty`, so `.sav` persistence
  works the same as for Mapper 1/4/5.
- **Verification:** `Mapper206Test` covers PRG mode 0 (R6 at `$8000`, R7 at `$A000`,
  second-to-last at `$C000`, last at `$E000`), CHR mode 0 (R0/R1 2 KB with low-bit
  ignored, R2-R5 1 KB), the bank-select/data protocol across the full `$8000-$FFFF`
  decode, hardwired PRG/CHR modes (writes with bits 6/7 set in `$8000` do not flip
  into mode 1 / invert), header-driven mirroring, PRG-RAM read/write, the missing
  IRQ, CHR-RAM fallback, and save/load round-trip. End-to-end ROM boot/render
  verification is left for a follow-up Mesen2 comparison (see `MapperVerificationTest`
  pattern) — Gauntlet and RBI Baseball are the natural oracles.

---

## Battery-Backed Save RAM (.sav files)

**Status:** Supported on mappers 1, 4, 5, 153, 206 (Added 2026-06-02 for mapper 153)

- **Format:** Raw bytes, no header. Bit-for-bit compatible with FCEUX / Nestopia / Mesen / Mesen2.
- **Location:** `saves/<rom-basename>.sav` next to the working directory.
- **Trigger:** iNES header byte 6 bit 1 (battery flag). When unset, no `.sav` file is created even if the cartridge has PRG-RAM.
- **Flush policy:** every 10 seconds if PRG-RAM is dirty, plus on clean shutdown, ROM switch, and hard reset. Atomic file replace (write `.sav.tmp` then move) keeps the existing save intact if the JVM dies mid-write.
- **Coverage:** Mapper 1 (MMC1 — Zelda, Final Fantasy, Kid Icarus), Mapper 4 (MMC3 — Mega Man 4-6, StarTropics, Crystalis), Mapper 5 (MMC5 — Castlevania III when battery-flagged), Mapper 153 (Bandai FCG-153 — Famicom Jump II), Mapper 206 (Namcot 108/109/118 — Gauntlet, RBI Baseball). Mappers without `$6000-$7FFF` PRG-RAM (0, 2, 3, 7, 9, 11, 16, 34) cannot host battery saves and are unaffected. (Mapper 16 submapper 5 uses EEPROM instead.)
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
