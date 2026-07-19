# NES Mapper Support Status

Active mapper list: **0, 1, 2, 3, 4, 5 (stub), 7, 9, 10, 11, 16, 22, 24, 26, 33, 34, 64, 65, 66, 68, 69, 153, 206, 228.**

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
  - **Consecutive-write ignore (issue #235, 2026-07-19):** the serial port ignores a data write that lands within one CPU cycle of the previous serial write (the 6502 read-modify-write dummy/real write pair), so only the first bit shifts. The bit-7 reset is never ignored (Shinsenden). Cycle stamped via `tickCpuCycle()`; matches Mesen2 `MMC1.h`.
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

- **Games:** Paperboy (32KB-PRG); Joust (16KB-PRG)
- **Behavior:** 8KB CHR ROM bank switching via $8000-$9FFF (bits 0-1)
- **PRG:** Fixed 16KB or 32KB at $8000-$FFFF. In 16KB mode the chip ignores A14, so $C000-$FFFF mirrors $8000-$BFFF. Issue #231 fixed the 16KB crash by switching the CPU-window read from `and 0x7FFF` to `% programRom.size`.

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

## Mapper 5 (MMC5 / ExROM)
**Status:** STUB — not yet playable

- **Games (target):** Castlevania III: Dracula's Curse (the canonical MMC5 title), Just Breed,
  Laser Invasion, Uncharted Waters, Metal Slader Glory.
- **What works:** the cartridge loads and dispatches to `Mapper5`, and battery-backed PRG-RAM at
  `$6000-$7FFF` persists (mapper 5 is in the `.sav` coverage list).
- **What is missing (why Castlevania III is not playable):** the bank-select decode needs a
  rewrite, plus MMC5's fill mode, the `$5205/$5206` multiplier output, ExRAM (`$5C00-$5FFF`)
  nametable/attribute modes, and the scanline IRQ. There is **no expansion audio** (MMC5's two
  pulse channels + PCM).
- **Verification:** unit-level register coverage only; no end-to-end boot/render test yet — it would
  fail until the items above land. Use `./gradlew bootcheck -Prom=<castlevania3.nes>` to track
  progress (expect FAIL/garbage until the bank decode + scanline IRQ are implemented).
- See the "Known limits" note in `CLAUDE.md` for the same summary.

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

## Mapper 18 (Jaleco SS880006)
**Status:** Working (Added 2026-07-09, issue #135)

- **Games:** *Jajamaru Gekimaden — Maboroshi no Kinmajou* (the localized name is
  *Magical Kid's Adventure* / *Nephrite*), *Ninja Jajamaru — Ginga Daisakuen*,
  *Pizza Pop!*, and *Toukon Club*.
- **Spec source:** Mesen2's `Core/NES/Mappers/Jaleco/JalecoSs88006.h` is the
  oracle. The [nesdev wiki page](https://www.nesdev.org/wiki/INES_Mapper_018)
  describes the chip as "resembling a scrambled VRC4" — the scattered
  `addr & 0xF003` register decode (even-address / odd-address nibbles) is
  the chip's defining trait.
- **Spec gotcha vs. issue text:** the GitHub issue #135 register-layout
  description ("16KB PRG, 8KB CHR, no IRQ, no PRG-RAM, hardware-fixed
  mirroring") is **incorrect** — it reflects a simplified mental model.
  The actual chip uses 8KB PRG, 1KB CHR, has an IRQ counter with
  configurable width (16/12/8/4-bit), a software-controlled mirroring
  register at `$F002`, and optional 8KB PRG-RAM on games that wire it.
  The current Nestlin implementation follows the Mesen2 model precisely;
  PRG-RAM support can be added later when a game that needs it is
  exercised end-to-end (the NO-INTRO Jajamaru Gekimaden dump has iNES
  byte 8 = 0, so no PRG-RAM is allocated today).
- **Geometry:**
  - **PRG:** 3 × 8KB switchable banks at `$8000-$DFFF`; the last 8KB
    bank at `$E000-$FFFF` is fixed.
  - **CHR:** 8 × 1KB switchable banks at `$0000-$1FFF`.
- **Register map (selected by `addr & 0xF003`, data `D0-D3` is the nibble):**
  - `$8000`/`$8001`: PRG bank 0 (`$8000-$9FFF`) — even = low 4 bits, odd = high 4.
  - `$8002`/`$8003`: PRG bank 1 (`$A000-$BFFF`).
  - `$9000`/`$9001`: PRG bank 2 (`$C000-$DFFF`).
  - `$A000..$D003`: the 8 CHR banks, ordered the same way (2 registers per
    1KB window: `$A000/$A001` is bank 0, `$A002/$A003` is bank 1, then
    `$B000..`, `$C000..`, `$D000..` for banks 2-7).
  - `$E000..E003`: IRQ reload nibbles `[3:0]` / `[7:4]` / `[11:8]` / `[15:12]`.
  - `$F000`: reload IRQ counter from the 4-nibble latch + ACK.
  - `$F001`: bit 0 = IRQ enable; bits 1-3 select counter width —
    `%1xx`=4-bit, `%01x`=8-bit, `%001`=12-bit, otherwise 16-bit.
  - `$F002`: mirroring (0=Horiz, 1=Vert, 2=1ScA, 3=1ScB).
  - `$F003`: expansion audio (µPD7755C — NOT implemented).
- **IRQ:** clocked every CPU cycle (NOT PPU A12 edges). The IRQ fires on
  the cycle the masked counter transitions from 1 to 0 (Mesen's
  `if(--counter == 0)` semantics). `$F000` reloads from the 4-nibble latch
  AND clears the pending IRQ; `$F001` writes also clear it (per Mesen).
- **Quirks:**
  - No bus conflicts on register writes (unlike VRC chips).
  - Some SS88006-based cartridges include a µPD7755C/µPD7756C ADPCM
    audio decoder, accessed through `$F003`; not implemented in Nestlin.
  - The chip's PRG-RAM window (`$6000-$7FFF`, where present on NES 2.0
    dumps) is not exposed by this implementation since the games verified
    so far (iNES byte-8 = 0) do not allocate it. To add PRG-RAM later,
    handle the `WorkRamSize` header field and expose via
    `batteryBackedRam()`/`batteryDirty` (see Mapper 16/19 for the pattern).

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

## Mapper 19 (Namco 163 / N163)
**Status:** Working (Added 2026-06-12, issue #59)

- **Games:** *Kaijuu Monogatari (Japan)* (the Atlus-published translated
  English title reads as "Megami Tensei II" in the menu) is the canonical
  N163 boot oracle on Adam's machine. Other N163 titles (e.g.
  *Yume Penguin Monogatari*, *Star Wars (Namco)*, *Mappy-Land*'s Japanese
  variant) load the same mapper.
- **Chip:** Namco 163. MMC3-class banking (8KB PRG, 1KB CHR) plus an
  8-channel wavetable synth on the same die. The 3 audio register
  shapes — `$F800` address port, `$4800-$4FFF` data port, and
  `$E000` bit 6 sound-enable — live on the audio class, not the mapper;
  the mapper's CPU-read/write decode dispatches into it.
- **PRG banking:** `$E000` bits 0-5 select bank 0 at `$8000` (8KB units,
  modulo PRG size), `$E800` bits 0-5 select bank 1 at `$A000`, and
  `$F000` bits 0-5 select bank 2 at `$C000`. The last 8KB bank is fixed
  at `$E000` and holds the reset/NMI/IRQ vectors. With 16 banks, the
  register value is masked to 4 bits in practice; the snapshot reports
  the raw register byte (other mappers' convention) which can read up
  to 0x3F when the game writes 8KB-page addresses into a smaller chip.
- **CHR banking:** `$8000-$9FFF` and `$A000-$BFFF` decode 8 standard
  1KB CHR banks. `$C000-$DFFF` extends with 4 more 1KB banks (the
  NES 2.0 "extra CHR" range); a high bit (0x100) on the index
  flips that window to **nametable** mode (used by the 4-screen
  mirroring trick). `$E800` bits 0-1 select between "CHR" and
  "nametable" mode for the upper half, with `$E800` bit 0 toggling
  the low half the same way — same trick the Nesdev wiki describes
  for the "lowChrNtMode" / "highChrNtMode" flags.
- **PRG-RAM:** 4 × 2KB pages at `$6000-$7FFF`, with a per-page
  write-protect mask (`$F800` bits 0-3) and a global write-enable
  (bit 6 of `$F800`).
- **IRQ:** 16-bit counter at `$5000`/`$5800`; bit 15 of the counter
  enables, low 15 bits count down to `0x7FFF` (the spec's "0x7FFF on
  enable" trap). Both CPU-write addresses are the ack — a write to
  `$5000` OR `$5800` clears the pending IRQ (no separate enable
  address; the counter-15 bit IS the enable).
- **Expansion audio (the headline feature of #59):** Eight wavetable
  voices, all sharing one mixed output. The chip's 128-byte internal
  RAM serves two purposes: `$00-$3F` is wavetable sample data
  (4-bit packed: low nibble = even position, high nibble = odd),
  `$40-$7F` is 8 channel register sets, 8 bytes each
  (frequency, phase, frequency/2+length, phase/2, wave address,
  volume). Bits 4-6 of byte `$7F` (channel 8's volume byte) set the
  **active channel count** — value 0 means "only channel 8",
  value 7 means "all 8 channels". The chip round-robins through the
  active channels (7, 6, 5, …) with one update per 15 CPU cycles,
  reading the 4-bit sample at `(phase >> 16) + waveAddress`, biasing
  to ±7, multiplying by the 4-bit volume, and storing in a per-channel
  output latch. `currentSample()` sums the active channels' latches,
  divides by `count + 1`, and normalizes by /120 to fit a [-1, +1]
  range. The APU mixer's `EXPANSION_GAIN=0.15` then puts a fullscale
  N163 channel at roughly the same level as a VRC6 voice.
  Registered with the expansion-audio mixer (issue #50) at
  cartridge load via `expansionAudioChannels() = listOf(audio)`.
- **Implementation:** `Mapper19` in `gamepak/Mapper19.kt` and
  `Namco163Audio` in `gamepak/Namco163Audio.kt`. The mapper is a
  thin dispatcher; the audio class owns the 128-byte RAM, the 15-cycle
  update cadence, and the round-robin `currentChannel` index.
  `saveStateVersion = 2` (bumped from default 1) since the audio
  state is non-trivial.
- **Verification:**
  - `Mapper19Test` covers dispatch from the iNES header
  (mapper 19 → Mapper19), default PRG state (banks 0/0/`count-1` at
  `$8000`/`$A000`/`$C000`, last bank at `$E000`), 8KB PRG banking
  with modulo wrap, all 12 1KB CHR windows defaulting to bank 0,
  the `$C000-$DFFF` extra CHR extension with the 0x100 nametable
  bit, `$E800` low/high nametable-mode flags, CHR-RAM fallback for
  0KB-CHR dumps, the 4× 2KB PRG-RAM window with per-page
  write-protect, IRQ counter load / enable / fire / ack, save/load
  round-trip with version-byte rejection, and `snapshot()` reporting
  of all banks + the IRQ counter / pending flag.
  - `Namco163AudioTest` covers the audio engine directly: data port
  cursor + auto-increment, 128-byte address wrap, sound-enable
  bit 6 of `$E000`, the wave-length formula (`256 - (reg & 0xFC)`),
  the 15-cycle update cadence, the round-robin sequence (7 → 6 →
  5 → … → wrap), the channel-7 volume / active-count byte aliasing
  (the only shared byte in the whole 128-byte map), the
  4-bit bipolar sample (`rawNibble - 8`, then × volume), the
  `currentSample` mix formula (`sum / (n+1) / 120`), and
  save/load round-trip.
  - `Mapper19RegressionTest` (Mesen-comparison group) — boots
  *Kaijuu Monogatari* on the real ROM. Asserts (1) the N163 PRG
  bank register actually pages during the first 60 frames (proves
  the `$E000`/`$E800`/`$F000` decode is wired, same cheap guard as
  the other mapper tests), and (2) Nestlin's render output
  (OAM, palette, PPUCTRL, PPUMASK) is byte-identical to Mesen2's
  at frame 60. The full snapshot + diff goes to
  `build/reports/state-diffs/kaijuu-monogatari-frame-60/`. The ROM
  is resolved from the canonical NO-INTRO path on Adam's machine,
  override with `NESTLIN_KAIJUU_MONOGATARI_ROM`.

---

## Mapper 22 (Konami VRC2a)
**Status:** Working (Added 2026-07-12, issue #137)

- **Games:** *TwinBee 3 - Poko Poko Daimaou (Japan)* is the only VRC2a title in the local NO-INTRO library and the regression oracle for this mapper. Other known VRC2a games include *Takeshi no Chōsenjō*, *Wai Wai World 2*, and several other late-1980s Konami / Famicom titles — none of which are locally available. Note: the GitHub issue text mentions "Akumajō Special: Boku Dracula-kun" as a VRC2a game, but in practice that title's iNES header is **mapper 23 (VRC4)**, not 22 — the issue spec was wrong on this point.
- **Spec source:** the nesdev wiki (INES Mapper 022) plus Mesen2's `Core/NES/Mappers/Konami/VRC2_4.h` oracle. The chip is the VRC2 — a stripped-down VRC4 with the same register address map but a smaller feature set. Where the GitHub issue text disagrees with the wiki + Mesen2 source, the wiki/Mesen2 wins (the issue's "no CHR banking" and "no mirroring" claims were both wrong).
- **Address-pin decode:** identical to VRC4b / Mapper 25 — CPU A1 → sub-bit 0, CPU A0 → sub-bit 1. `$x000` → sub 0, `$x001` → sub 2, `$x002` → sub 1, `$x003` → sub 3.
- **PRG geometry:**
  - `$8000-$9FFF` — switchable 8KB bank via `$8000` (5-bit).
  - `$A000-$BFFF` — switchable 8KB bank via `$A000` (5-bit).
  - `$C000-$DFFF` — **fixed** to the second-to-last 8KB bank. (VRC2 has no `$9002` swap-mode register; the only way for `$C000` to see `prg0` is the VRC4 swap mode, which VRC2 doesn't expose.)
  - `$E000-$FFFF` — **fixed** to the last 8KB bank (reset / NMI / IRQ vectors).
- **CHR geometry:** eight independent 1KB banks at `$0000-$1FFF`, each selected via the VRC4-style low+high nibble protocol (`$B000-$B003` for windows 0-1, `$C000-$C003` for windows 2-3, etc.). **VRC2a quirk:** the chip only wires 7 of the 9 stored CHR-select bits — the effective page index is the stored 9-bit value right-shifted by 1 (Mesen2's `if (_variant == VRC2a) page >>= 1`). A 0KB-CHR dump gets 8KB of writable CHR-RAM (same fallback as Mapper 2 / 3 / 11 / 71).
- **Mirroring:** `$9000` / `$9001` write **bit 0** selects Vertical (0) or Horizontal (1). The VRC2 silicon ties bit 1 to a no-op (VRC4's 1-screen-lower / 1-screen-upper modes aren't exposed), so any value with bit 1 set collapses to V or H per bit 0. The header drives the initial state until the game writes `$9000` for the first time.
- **No IRQ counter.** `$F000-$F003` writes are silently dropped; `tickCpuCycle` is a no-op; `isIrqPending` always returns false. The VRC2 chip literally has no IRQ device (VRC4 added one).
- **No WRAM.** `$6000-$7FFF` reads return the 6502 open-bus value (`dataBus`); writes are accepted and discarded. The VRC2 silicon has only a 1-bit latch at `$6000-$6FFF` that no commercial cartridge wires up (PCB 351618 ties the EEPROM data pin to ground). `batteryBackedRam()` returns `null` so the Save RAM layer doesn't allocate an unused `.sav` file.
- **No `$9002` swap-mode / WRAM-enable register.** Writes to `$9002` / `$9003` are silently dropped — the chip has no register there at all.
- **Implementation:** `Mapper22` is a thin subclass of the existing `Vrc4` base class. It overrides `decodeSubRegister` (the VRC4b swap), `cpuRead` (open-bus for `$6000-$7FFF`), `cpuWrite` (drops `$6000`, `$9002-$9003`, and `$Fxxx` writes; bit-masks `$9000`/$9001` mirroring to V/H), `currentMirroring` (collapses bit-1 VRC4 modes to V/H; preserves the `-1` header-fallback sentinel), `ppuRead` (applies the `>> 1` CHR shift), `tickCpuCycle` (no-op), `isIrqPending` (false), `batteryBackedRam` (null), and `dataBus` (so `$6000-$7FFF` reads actually return open bus in tests). The Vrc4 base class's `prg0`, `prg1`, `mirroringMode` fields and `writeChr` helper were promoted from `private` to `protected` for this purpose.
- **Save state:** inherits VRC4's version-2 format — round-trip works because the VRC2a-specific state is a strict subset of VRC4's.
- **Verification:**
  - `Mapper22Test` (22 cases) covers dispatch from the iNES header, default PRG state (banks 0/0/second-to-last/last), `prg0`/`prg1` switching via the VRC4b address layout, $9002 swap-mode and WRAM-enable bits being no-ops, $6000-$7FFF open-bus read + dropped writes + null `batteryBackedRam`, $9000 V/H mirroring + bit-1-ignored collapse + header fallback, $Fxxx IRQ non-firing even after 10K CPU cycles, all 8 CHR windows with the `>> 1` shift quirk, CHR high-nibble before the shift, CHR-RAM fallback for 0-CHR dumps, save/load round-trip, version-byte rejection, and a swap-correctness assertion that proves `$B001` (sub 2) targets `chr1` and not `chr0` (a VRC4a decode would clobber chr0 here).
  - `Mapper22RegressionTest` (`@RequiresMesen2`, in the `mesen` lane) — boots TwinBee 3 on the real ROM, asserts (1) `prg0` actually pages during boot (the "did the mapper do anything" guard), and (2) Nestlin's CHR banks are byte-identical to Mesen2's at frame 60.
  - **Real-ROM boot gate:** `./gradlew bootcheck -Prom="S:/Media/Nintendo NES/Games/TwinBee 3 - Poko Poko Daimaou (Japan) (Translated En).nes" -Pframes=120` returns **PASS** (rendering on by frame 8, 56% non-blank, 4 distinct PRG states, 3 distinct CHR states, 118 NMIs fired, 0 IRQs — exactly what a chip-without-IRQ should show).

---

## Mapper 24 (Konami VRC6a)
**Status:** Working (Added 2026-06-11, issue #58)

- **Games:** *Akumajou Densetsu (Japan)* (Castlevania III: Dracula's Curse, the JP release).
  This is the canonical VRC6a boot oracle.
- **Chip:** Konami VRC6a. Same silicon as VRC6b / Mapper 26, but with a
  different A0/A1 address-pin wiring to the chip's sub-register-select
  input. On VRC6a the CPU's A0 → sub-bit 0 and A1 → sub-bit 1 directly
  (so `$x000, $x001, $x002, $x003` present the canonical layout).
- **PRG banking:**
  - `$8000-$BFFF` — 16K switchable (4-bit select)
  - `$C000-$DFFF` — 8K switchable (5-bit select)
  - `$E000-$FFFF` — 8K fixed-last
- **CHR banking:** `$D000-$D003` and `$E000-$E003` select 8× 1KB CHR banks
  (PPU mode 0 — the only mode Castlevania III, Esper Dream 2 and Madara
  use; modes 1-3 are recognised but logged to stderr rather than rendered).
- **Mirroring:** `$B003` bits 2-3 select vertical/horizontal/1-screen-lower/
  1-screen-upper. With `$B003` never written, the chip defaults to
  vertical (MM=0).
- **WRAM:** `$B003` bit 7 enables the 8KB PRG-RAM window at `$6000-$7FFF`.
  When disabled, reads return the open-bus value (`memory.dataBus`).
- **IRQ:** VRC-shaped 8-bit increment-to-wrap counter at `$F000-$F002`
  with the standard M (cycle/scanline) / E (enable) / A (after-ack) bits.
  Scanline prescaler is the 341/-3 trick shared with VRC4.
- **Expansion audio (the headline feature of #58):** Three voices — two
  VRC6 pulse channels (4-bit volume, 16-step duty) and one VRC6 sawtooth
  (8-bit accumulator, 6-add-then-reset cycle). All three are registered
  with the APU's expansion-audio mixer (issue #50) at cartridge load.
- **Implementation:** Shared abstract base `Vrc6` in `gamepak/Vrc6.kt`
  with `Mapper24` overriding only the sub-register decode
  (`address and 0x03` for VRC6a) and the `mapperId` (24). The audio
  channels are split into `Vrc6Pulse` / `Vrc6Saw` / `Vrc6FrequencyControl`
  in `gamepak/Vrc6Audio.kt`, all implementing `ExpansionAudioChannel`.
- **Verification:**
  - `Vrc6Test` covers dispatch from the iNES header (mapper 24 → Mapper24,
    mapper 26 → Mapper26), default PRG state (bank 0 at `$8000`/`$A000`
    /`$C000`, last 8KB at `$E000`), 16K PRG banking via `$8000` writes
    (4-bit field, modulo-wrap), 8K PRG banking via `$C000` writes
    (5-bit field), all 8 1KB CHR windows defaulting to bank 0, the
    `$D000-$D003` and `$E000-$E003` register decode, mirroring
    selection via `$B003` bits 2-3, the open-bus read at `$6000-$7FFF`
    when WRAM is disabled, the read-write page when WRAM is enabled,
    IRQ firing on counter wrap from 0xFF→latch, IRQ silence when E=0,
    IRQ acknowledge at `$F002` re-loading from the A bit, `snapshot()`
    reporting all banks, save/load round-trip, and VRC6b's address-pin
    swap.
  - `Vrc6AudioTest` covers the three voices directly: VRC6 pulse's
    16-step duty generator (M bit, D threshold, step counter wrap),
    VRC6 saw's accumulator-and-reset cycle (rate, peak output ≥30/31),
    VRC6 frequency control at `$9003` (halt, 16× shift, 256× shift,
    256× overriding 16×, halt winning over shift), save/load
    round-trip for all three, and a Goertzel-spectrum test of the
    VRC6 saw producing a fundamental at the expected divider frequency
    through the APU mixer.
  - `Mapper24RegressionTest` (Mesen-comparison group) — boots
    *Akumajou Densetsu (Japan)* on the real ROM. Asserts (1) the VRC6
    `prg16` bank register actually pages during boot, and (2) Nestlin's
    CHR pattern-table is byte-identical to Mesen2's across all eight
    1KB windows at frame 60. Same NMI/OAM-offset rationale as Don Doko
    Don (CHR-only is the proof; full snapshot + diff is reported for
    inspection).

---

## Mapper 26 (Konami VRC6b)
**Status:** Working (Added 2026-06-11, issue #58)

- **Games:** *Esper Dream 2 - Aratanaru Tatakai (Japan)* and
  *Mouryou Senki Madara (Japan)*. The canonical VRC6b boot oracle is
  Esper Dream 2 (an Akumajou-Densetsu-sized 384KB cartridge).
- **Chip:** Konami VRC6b. Same silicon as VRC6a / Mapper 24, but with
  A1 → sub-bit 0 and A0 → sub-bit 1 (i.e. the low two address bits are
  swapped before reaching the chip). To the CPU this appears as
  registers at `$x000, $x002, $x001, $x003` instead of `$x000, $x001,
  $x002, $x003`. The PRG/CHR/IRQ/audio behaviour is otherwise identical
  to VRC6a.
- **Implementation:** `Mapper26` extends the shared `Vrc6` base and
  overrides only `decodeSubRegister` (the A0/A1 swap) and `mapperId`
  (= 26). All other code paths, including the three audio channels,
  are shared with VRC6a.
- **Verification:**
  - `Vrc6Test.vrc6b sub-register decode swaps bits 0 and 1` proves the
    A0/A1 swap is honoured: a write to `$D001` lands in CHR register
    R2 (the `$0800` 1KB window), not R1 (`$0400`); a write to `$D002`
    lands in R1, not R2.
  - `Mapper26RegressionTest` (Mesen-comparison group) — boots
    *Esper Dream 2* on the real ROM. Same pattern as Mapper 24:
    bank-moves-during-boot guard + byte-identical CHR compare vs Mesen2
    at frame 60.

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

---

## Mapper 11 (Color Dreams / NINA-007)
**Status:** Working (Fixed 2026-04-17)

- **Games:** Bible Adventures, Action 52, Crystalis (some versions)
- **Behavior:** CHR bank switching via bits 4-7 of $8000-$FFFF writes, PRG fixed at $8000-$FFFF
- **Bugs Fixed (2026-04-17):**
  1. CHR banking was reading bits 0-1 (PRG bank field) instead of bits 4-7 (CHR bank field)
  2. PRG banking was fixed to bank 0 only; now properly switches 32KB PRG banks via bits 0-1
- **Format (per NESdev wiki):** `CCCC LLPP` where CCCC = 8KB CHR bank (bits 4-7), LL = unused/lockout, PP = 32KB PRG bank (bits 0-1)
- **Verified:** Bible Adventures now shows gameplay content (was all-black before fix)

---

## Mapper 34 (BNROM / NINA-001)
**Status:** Working (NINA-001 variant added 2026-07-19, issue #233)

- **Games:**
  - **BNROM (submapper 2):** Deadly Towers (USA).
  - **NINA-001 (submapper 1):** Impossible Mission II (USA) (Unl).
- **What it is:** two unrelated boards sharing one iNES number. Both bank the **entire** 32 KB
  PRG window at `$8000-$FFFF` (no fixed bank, unlike UNROM/Mapper 2). The boards differ in
  everything else — register addresses, CHR bank granularity, and PRG-RAM presence.
- **Variant detection:**
  1. **NES 2.0 submapper byte is authoritative** (header byte 8 high nibble). Submapper 1 =
     NINA-001; submapper 2 = BNROM. Unknown submappers fall through.
  2. **Plain iNES fallback heuristic** — when no submapper byte is present, CHR-ROM size
     disambiguates: `>8 KB` ⇒ NINA-001 (the two 4 KB CHR windows need ≥16 KB to be useful);
     `≤8 KB` ⇒ BNROM. Real-world commercial NINA-001 titles ship ≥16 KB CHR (Impossible
     Mission II carries 16 KB) and BNROM titles ship exactly 8 KB (Deadly Towers), so the
     heuristic is unambiguous in practice. 0 KB CHR (CHR-RAM homebrew) defaults to BNROM
     — both variants expose the same CHR-RAM regardless of decode, and BNROM's
     write-anywhere register is the more permissive.
- **BNROM register decode (writes to `$8000-$FFFF`):** a single byte — `prgBank = value & 0x07`
  (32 KB PRG bank, modulo PRG-bank count, max 128 KB PRG = 4 banks), `chrBank = (value >> 3) & 0x03`
  (8 KB CHR bank, modulo CHR size). Bits 5-7 of the write value are unused on real BNROM hardware.
  **Any** write in `$8000-$FFFF` fires the register — there is no address decode. Writes outside
  that range (`$6000-$7FFF`, etc.) are silently dropped.
- **NINA-001 register decode:** three discrete registers — no address-aliasing, only the exact
  addresses matter:
  - `$7FFD` → **PRG bank** (bit 0 only, max 64 KB PRG = 2 banks).
  - `$7FFE` → **CHR bank 0** (4 KB window for PPU `$0000-$0FFF`, low 4 bits = bank, max 16 banks / 64 KB CHR).
  - `$7FFF` → **CHR bank 1** (4 KB window for PPU `$1000-$1FFF`, low 4 bits = bank, max 16 banks / 64 KB CHR).
  - `$6000-$7FFF` → **8 KB PRG-RAM** (read/write, no write-protect bit). Exposed via
    `batteryBackedRam()`; `Nestlin.loadBatteryRam` gates `.sav` persistence on `Header.hasBattery`.
  - Writes outside those four ranges (`$8000-$FFFF`, etc.) are silently dropped.
- **CHR:** 8 KB banked from CHR ROM (BNROM) / two 4 KB windows banked from CHR ROM (NINA-001). A
  0 KB-CHR dump gets 8 KB of writable CHR RAM shared across the whole `$0000-$1FFF` window (same
  fallback as Mappers 2/3/7/11/33/71).
- **Mirroring:** fixed from the iNES header (solder-pad on real hardware). No software-controlled
  mirroring register. No IRQ on either variant.
- **Implementation notes:**
  - A single `Mapper34` class branches internally on a `Variant` enum chosen at construction time.
    A "shared base class + subclass" pattern was considered and rejected — the two boards share
    only the 32 KB PRG-window topology + CHR-RAM fallback + header-driven mirroring, and the
    register protocol differs enough that the abstraction would have hidden more than it revealed.
  - Save state version bumped 2 → 3 (issue #100 convention): the variant ordinal is written first
    so `loadState` can refuse a BNROM save state being loaded into a NINA-001 instance (and vice
    versa) instead of silently corrupting state. The check throws
    `SaveState.IncompatibleSaveStateException` with a message naming both variants.
  - NINA-001 PRG-RAM uses the same `batteryBackedRam()` convention as Mapper 10 / 16 / 153 — the
    buffer is allocated unconditionally and the higher layer gates `.sav` persistence. NINA-001
    boards in the wild don't carry a battery (Impossible Mission II has no save game), so the
    buffer is typically volatile.
- **Verification:**
  - `Mapper34Test` covers both variants. For BNROM: dispatch, PRG low-3-bit decode + high-bit
    mask, write-anywhere register (`$8000`/`$FFFF`/`$ABCD` all fire), all 4 PRG banks reachable,
    PRG modulo wrap, CHR bits 3-4 decode + high-bit mask, CHR-RAM fallback, header mirroring,
    `batteryBackedRam() == null`, save/load round-trip, and a save-state variant-mismatch
    rejection assertion. For NINA-001: dispatch, `$7FFD` bit-0 + high-bit mask, `$7FFD`-only PRG
    write target (writes elsewhere don't move the bank), `$7FFE`/`$7FFF` CHR-bank registers,
    CHR window independence (write `$7FFE` does NOT affect `$1000-$1FFF`), 8 KB PRG-RAM at
    `$6000-$7FFF` (read/write, `batteryDirty` toggle on write, `batteryBackedRam()` non-null,
    no aliasing with PRG ROM), CHR-RAM fallback, header mirroring, save/load round-trip, and a
    save-state variant-mismatch rejection assertion. Variant-detection tests cover NES 2.0
    submapper 1/2 (submapper wins over heuristic even when the heuristic would disagree), the
    plain iNES >8 KB / ≤8 KB heuristic, and the 0 CHR default. Snapshot tests cover both
    `type` labels ("BNROM" vs "NINA-001") and the differing bank-key set.
  - `Mapper34RegressionTest` (`@Tag("mesen")`, in the mesen lane via `MapperRegressionTestBase`)
    covers both Deadly Towers (BNROM) and Impossible Mission II (NINA-001): the cheap
    `assertBankSwitchesDuringBoot` guard for each (BNROM watches `prgBank`, NINA-001 watches
    `chrBank1` because that's the window IM2 actively pages) plus the Mesen2 byte-compare at
    frame 60 for each. ROMs are resolved from `S:/Media/Nintendo NES/Games/`; override with
    `NESTLIN_DEADLY_TOWERS_ROM` / `NESTLIN_IMPOSSIBLE_MISSION_II_ROM`.
  - **Real-ROM boot gate:** `./gradlew bootcheck -Prom=".../Deadly Towers (USA).nes" -Pframes=120`
    and `./gradlew bootcheck -Prom=".../Impossible Mission II (USA) (Unl).nes" -Pframes=120`
    both return **PASS**.

## Mapper 64 (Tengen RAMBO-1)
**Status:** Working (Added 2026-06-07, issue #132)

- **Games:** ~12-15 Tengen-published titles including *Skull & Crossbones*, *Alien Syndrome*, *Klax*, *Road Runner*, *Rolling Thunder*, *Toobin'*. Tengen's unlicensed MMC3 derivative.
- **What it is:** an MMC3-*like* chip that shares the register protocol ($8000/$8001 select+data, $A000 mirroring, $C000-$E001 IRQ) but has genuinely different *banking*. Modelled byte-for-byte on Mesen2's `Rambo1.h` (the reference oracle); where the nesdev prose and Mesen disagree, Mesen wins because the state-diff tests compare against it. Differences from MMC3:
  - **4-bit register select.** `$8000` bits 0-3 select R0-R15 (MMC3 uses bits 0-2). This makes R8/R9 (extra 1 KB CHR banks) and R15 (a third switchable PRG bank) reachable.
  - **Three switchable 8 KB PRG banks** — R6, R7, R15. `$E000` is fixed to the last bank; neither `$8000` nor `$C000` is ever a fixed second-to-last bank (that's the MMC3 layout).
  - **1 KB CHR mode** (`$8000` bit 5, the "K" bit) adds R8/R9.
  - `$A001` is "not implemented" — RAMBO-1 has no PRG-RAM to enable/protect, so writes are discarded.
  - **No PRG-RAM** at `$6000-$7FFF` (real hardware: "PRG RAM capacity: None"). Reads return 0; writes discarded.
  - **CPU-cycle IRQ mode** (`$C001` bit 0) in addition to the MMC3 A12/scanline mode.
- **PRG layout (8 KB granularity):**
  - PRG mode 0 (`$8000` bit 6 = 0): `$8000`=R6, `$A000`=R7, `$C000`=R15.
  - PRG mode 1 (`$8000` bit 6 = 1): `$8000`=R15, `$A000`=R6, `$C000`=R7.
  - `$E000-$FFFF` — always fixed to the last 8 KB bank.
- **CHR layout (eight 1 KB pages):** physical page index XOR'd with 4 when CHR A12 inversion (`$8000` bit 7) is set. Logical slot → register: slot0=R0, slot1=`K?R8:R0+1`, slot2=R1, slot3=`K?R9:R1+1`, slot4-7=R2-R5. (Matches Mesen exactly — the 2 KB windows use R0/R0+1 directly, no low-bit masking.) CHR-RAM fallback for 0 KB-CHR dumps.
- **PRG-RAM:** none. `$6000-$7FFF` reads return 0; writes discarded. `batteryBackedRam()` returns `null`.
- **Mirroring:** `$A000` even-address write (bit 0 = 0 vertical, 1 horizontal) overrides the iNES header (inherited from Mapper4).
- **IRQ:** two clock sources, mutually exclusive, selected by `$C001` bit 0:
  - **A12 / scanline mode** (default): clocked by PPU A12 rising edges, like MMC3.
  - **CPU-cycle mode**: the counter is clocked once every **4** CPU cycles (Mesen: `_cpuClockCounter = (_cpuClockCounter+1) & 3`). A12 edges are ignored in this mode.
  - **Reload** (in `ScanlineCounter`, RAMBO-1 path): explicit `$C001` reload sets counter = `latch+1` (latch ≤ 1) or `latch+2` (latch > 1); auto-reload after underflow sets `latch+1`. This is the documented "Klax needs +1" quirk.
  - **Fire only on decrement-to-zero**, never on reload-to-zero (Mesen2 `Rambo1.h` puts the trigger test inside the decrement branch). This matters because a game disarms its single per-frame split by reloading a LARGE latch (Klax uses `0xFE`, which wraps `0xFE+2 → 0x00`): firing on that reload-to-zero produced a spurious second IRQ that corrupted every CHR bank below the split (the "garbage 0s" glitch). The MMC3 path still fires on reload-or-decrement to zero. Regression: `Mapper64IrqDisarmTest`.
- **Implementation notes:**
  - `Mapper64` subclasses `Mapper4` to reuse the IRQ counter, mirroring, and CHR-RAM fallback, but **owns its PRG/CHR banking** (a 16-entry register file `reg[0..15]` plus the mode flags) because RAMBO-1's layout genuinely differs from MMC3. It overrides `handleBankSelectWrite` (4-bit select + the K/PRG-mode/invert bits), `handleBankDataWrite`, `cpuRead`, `ppuRead`, `notifyA12Edge` (ignore A12 in CPU-cycle mode), `tickCpuCycle` (div-4), `saveState`/`loadState`, and `snapshot`.
  - **Why it was originally broken:** the first implementation inherited MMC3's 3-bit register select, two-PRG-bank layout, and 2 KB-only CHR. The register *protocol* is identical to MMC3, so games booted and ran "stably" — but Klax selects R15, R8/R9, and K-mode (verified by `Mapper64KlaxBankTraceTest`), all of which the MMC3 decode silently aliased or ignored. Klax `JSR`s into `$C000` (=R15 on RAMBO-1, a wrong fixed bank under MMC3 decode), diverged on the first frame, and rendered a blank screen. The misleading "boots stably" plus MMC3-shaped unit tests is why earlier work chased CPU/PPU timing and open-bus instead of the banking.
  - **Data-bus tracking** (in `Memory.kt` + `Mapper.kt`) remains in place but **opt-in** — `Mapper64.cpuRead` returns 0 for open-bus, which Klax does not depend on. The infrastructure is for mappers that genuinely need it (HES NTD-8 / Mapper 113; see memory entry `hes-mb-mesen2-divergence-2026-06-07`).
- **Verification:**
  - `Mapper64Test` (32 tests) covers: header dispatch; power-on PRG banks (all register 0, `$E000` last); R6/R7 PRG switching; **R15 as the third PRG bank at `$C000` (mode 0) / `$8000` (mode 1)**; **4-bit register select (R15 not aliased onto R7)**; PRG mode 1 swap; 2 KB CHR (R0/R0+1, R1/R1+1, no masking); R2-R5 1 KB banks; **K-mode 1 KB CHR with R8/R9**; CHR A12 inversion (XOR 4); bank-select/data protocol; `$A000` mirroring; `$A001` no-op; `$6000-$7FFF`/`$4020-$5FFF` read 0; writes discarded; `batteryBackedRam()` null; A12 IRQ with the latch+1/latch+2 reload; explicit-reload latch+2 for latch>1; **CPU-cycle IRQ clocked every 4 cycles**; CPU-cycle mode ignores A12; CHR-RAM fallback; save/load round-trips banking + modes + IRQ state + CPU-cycle mode; snapshot reports mapper 64 / `prgRam = null`.
  - `Mapper64KlaxBankTraceTest` asserts Klax actually exercises R15, K-mode, and R8/R9 — guarding *why* the full decode is required.
  - `Mapper64RealGameBootTest` boots *Klax*, *Skull & Crossbones*, and *Road Runner* for 600 frames (rendering enabled throughout, mapper switching banks, not stalled).
  - `Mapper64IrqDisarmTest` guards the split-screen IRQ: arm `latch=0x3F` → fires once → disarm `latch=0xFE` → assert NO fire within 240 scanlines. This is the regression for the "garbage 0s below the split" double-fire.
  - `Mapper64KlaxRegressionTest` (Mesen2 state diff) — at the title-screen frames (60, 240) Klax's CHR and palette are **byte-identical to Mesen2** and the CPU PC matches within the capture-scanline offset. After the IRQ disarm fix, Klax renders its **full title screen** (KLAX logo, START/OPTION/STUFF menu, Aztec borders, copyright) matching Mesen2 — previously the region below the scanline split was garbage. All five RAMBO-1 titles (Klax, Skull & Crossbones, Road Runner, Rolling Thunder, Toobin') render their title/attract screens cleanly. Remaining state-diff deltas (OAM, PPU control/mask) are the documented pre-NMI-vs-post-NMI capture offset, not a render bug.

## Mapper 65 (Irem H3001)
**Status:** Working (Added 2026-06-14, issue #133)

- **Games:** *R-Type*, *Kickle Cubicle*, *Infiltrator*, *The Adventures of Rad Gravity*, *Metal Storm*, *Spartan X 2* (Famicom), *Spelunker II*. ~8-10 Irem H3001 titles in total. R-Type is named in the issue as the worked example but is absent from the local NO-INTRO library; the only mapper 65 game in the local library is *Spartan X 2 (Japan, translated)* (256 KB), and that's the regression oracle used in `Mapper65RegressionTest`. The other Irem H3001 titles in the library (Rad Gravity, Kickle Cubicle, Infiltrator, Metal Storm) all carry iNES headers labelled mapper 1 or mapper 4, not 65 — so they cannot be used as oracles here.
- **What it is:** a 32 KB PRG window with 8 KB granularity at `$8000`/`$A000`/`$C000` (the `$E000-$FFFF` slot is fixed to the last bank), 8 × 1 KB CHR pages selected individually at `$B000`-`$B007`, software-controlled mirroring at `$9001` bit 7, and a 16-bit CPU-cycle-clocked down-counter for raster-timed IRQs.
- **Register decode (mask 0xF000):** every write in `$8000-$FFFF` collapses to its 4 KB page base. Within `$9000` and `$B000` the low 3 bits of the address pick the sub-register.
- **Register map (selected by `addr & 0xF000`, then sub-bits for `$9xxx` / `$Bxxx`):**
  - `$8000` — 8 KB PRG bank at `$8000-$9FFF` (whole byte, modulo bank count).
  - `$A000` — 8 KB PRG bank at `$A000-$BFFF`.
  - `$C000` — 8 KB PRG bank at `$C000-$DFFF`.
  - `$E000-$FFFF` — **fixed to the last 8 KB bank**; no register.
  - `$9001` — bit 7 = 1 → Horizontal, 0 → Vertical. Other bits ignored (only bit 7 matters, per Mesen2). The iNES header drives the initial state until the game writes `$9001` for the first time.
  - `$9003` — bit 7 enables the IRQ counter. Any write to `$9003` (regardless of value) also acknowledges a pending IRQ. Writing bit 7 = 0 disables.
  - `$9004` — reloads the counter from the `$9005:$9006` reload value. Any write also acknowledges a pending IRQ. Does *not* enable the counter; that's `$9003`'s job.
  - `$9005` / `$9006` — high / low byte of the 16-bit reload value.
  - `$B000`-`$B007` — 8 × 1 KB CHR bank selects (low 3 bits of the address pick the register; bits 3-11 alias).
  - All other addresses in `$9000`-`$9FFF` and `$D000`-`$FFFF` are silently ignored (no PRG bank-layout register despite what the nesdev wiki prose says — Mesen2 doesn't model one).
- **PRG geometry (8 KB granularity, 4 pages):**
  - `$8000-$9FFF`: switchable, selected by `$8000`.
  - `$A000-$BFFF`: switchable, selected by `$A000`.
  - `$C000-$DFFF`: switchable, selected by `$C000`.
  - `$E000-$FFFF`: **fixed to the last 8 KB bank** (no register; same trick as Mapper 33 / Mapper 16 / Mapper 2).
- **CHR geometry (1 KB granularity, 8 pages):**
  - `$0000-$03FF`: page 0, via `$B000`.
  - `$0400-$07FF`: page 1, via `$B001`.
  - … through …
  - `$1C00-$1FFF`: page 7, via `$B007`.
  - The whole-byte value is the 1 KB bank index. `% chrRom.size` keeps oversized writes from indexing past the array.
- **Power-on state** (per Mesen2 `InitMapper`): pages 0/1/2 = banks 0/1/0xFE (0xFE modulo'd at read time — for a 32-bank ROM it reads bank 30; for a 16-bank ROM it reads bank 0; for a 256-bank ROM it reads bank 0xFE exactly); page 3 = last bank. Mirroring = iNES header's mirroring bit.
- **Mirroring:** `$9001` bit 7 — 1 = Horizontal, 0 = Vertical. Header is the initial value. There is no 1-screen mode.
- **IRQ (the differentiator vs TC0190/MMC3):** a 16-bit down-counter at `$9005:$9006`. Decremented once per CPU (M2) cycle when the enable bit (`$9003` bit 7) is set. When the counter reaches zero, the IRQ line is asserted and the enable bit is **cleared automatically** — the chip is **one-shot**, no wrap. Games that want re-arming IRQs must write `$9004` (reload) and `$9003` bit 7 (re-enable) in that order. Both `$9003` and `$9004` writes also acknowledge a pending IRQ. This is the same CPU-cycle-clocked mapper IRQ pattern as Mapper 69 (FME-7) and Mapper 16 (Bandai FCG) — wired through `Mapper.tickCpuCycle()`.
- **No PRG-RAM, no expansion audio.**
- **Implementation notes:**
  - **The GitHub issue spec is wrong on three points**: it claims (a) 4×8 KB switchable PRG slots — only 3 are switchable; `$E000` is fixed. (b) Header-driven mirroring — mirroring is software-controlled at `$9001`. (c) No IRQ — the chip has a 16-bit CPU-cycle-clocked IRQ. The Mesen2 source (`Core/NES/Mappers/Irem/IremH3001.h`) is the oracle (per the new-mapper skill — when the issue disagrees with Mesen2, Mesen2 wins). Implementing to the issue spec would have made `Mapper65RegressionTest` byte-mismatch Mesen2 on the first frame.
  - The decode mask is `0xF000` (per Mesen2's `IremH3001_WriteMask`); the previous nesdev prose ("$9000 PRG bank layout" register) is silently ignored — those writes do nothing. The wiki prose and Mesen2 disagree; Mesen2 wins.
  - `prgBankCount` is `coerceAtLeast(1)` so a malformed 0-PRG ROM (truncated dump) can't make the fixed-bank read at `$E000` compute a negative index.
  - CHR RAM fallback for 0 KB-CHR dumps (same pattern as Mappers 2/3/7/11/33/34/71) so homebrew ROMs don't crash on PPU read.
  - The IRQ counter is `Int` (32 bits) but the effective range is masked to 16 bits at read time — the chip's value never goes negative; on decrement it stops at 0 and disables.
- **Verification:**
  - `Mapper65Test` (31 tests) covers: header dispatch; **power-on PRG state (banks 0/1/0xFE mod 32/last)**; the three PRG registers at `$8000`/`$A000`/`$C000` (whole-byte select); `$E000-$FFFF` fixed to the last bank (no register changes it); PRG-modulo wrap; the **0xF000 decode mask** (aliasing to `$8042`, `$8FFF`, `$A123`, `$ACDE`, `$C001`, `$CFFF`); CHR register writes for all eight 1 KB pages; the **low-3-bits sub-decode** within `$B000`-`$BFFF` (aliasing to `$B105`, `$BA05`, `$BFFF`); CHR-RAM fallback; mirroring header default + `$9001` bit 7 + the 0xF000/0x00FF sub-decode for `$9001`; silent ignore of `$9000`/`$9002`/`$9007..$900F` and `$D000..$FFFF` writes; IRQ enable via `$9003` bit 7; **16-bit reload value from `$9005:$9006`**; counter decrements per `tickCpuCycle`; **fires on zero and auto-disables (one-shot, no wrap)**; acknowledge on `$9003` or `$9004` write; counter idle when `$9003` bit 7 is clear; `$9004` reload does not require a preceding `$9003` enable (the chip latches the reload value, then `$9003` arms it); save/load round-trip of all PRG/CHR/mirroring/IRQ state + CHR-RAM when present; `snapshot()` reports `prgBank0..2`, all 8 CHR banks, mirroring, and the full IRQ state.
  - `Mapper65RegressionTest` (`@RequiresMesen2`) — boots *Spartan X 2 (Japan, translated)* on the real ROM. Asserts (1) the `$8000` PRG bank register actually pages during boot (the cheap "did the mapper do anything" guard from the Star Soldier recipe), and (2) Nestlin's render-output state (OAM, palette, PPUCTRL, PPUMASK) is byte-identical to the Mesen2 oracle at frame 120.


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

## Mapper 68 (Sunsoft-4 — a.k.a. "Sunsoft 2")
**Status:** Working (Added 2026-06-27, GH #134)

- **Games:** *The Legend of Valkyrie*, *Nangoku Shounen Papuwa-kun*, *Nantettatte!! Baseball*
- **Silicon:** the Sunsoft-4 IC. Despite the "Sunsoft 2" name sometimes used for this
  iNES number (and the GitHub issue title), both the nesdev wiki page (INES Mapper 068)
  and Mesen2's `MapperFactory` map iNES mapper 68 → `Sunsoft4`. No expansion audio
  (mapper 67 is the Sunsoft-3 with 5B audio; mapper 69 is the Sunsoft FME-7).
- **Register layout (`addr & 0xF000` decoded):**
  - `$8000-$8FFF`: 2KB CHR bank 0 → PPU `$0000-$07FF`
  - `$9000-$9FFF`: 2KB CHR bank 1 → PPU `$0800-$0FFF`
  - `$A000-$AFFF`: 2KB CHR bank 2 → PPU `$1000-$17FF`
  - `$B000-$BFFF`: 2KB CHR bank 3 → PPU `$1800-$1FFF`
  - `$C000-$CFFF`: Nametable CHR reg 0 (active only when `$E000` bit 4 is set)
  - `$D000-$DFFF`: Nametable CHR reg 1 (active only when `$E000` bit 4 is set)
  - `$E000-$EFFF`: Mirroring (bits 0-1) + CHR-for-nametable mode (bit 4)
  - `$F000-$FFFF`: PRG bank 0 (bits 0-2) + external-PRG select (bit 3) + PRG-RAM enable (bit 4)
- **Mirroring ($E000 bits 0-1):** 0=Vertical, 1=Horizontal, 2=Screen A only, 3=Screen B only.
- **PRG:** 16KB banks. Page 0 (at `$8000-$BFFF`) is switchable; page 1 (at `$C000-$FFFF`)
  is fixed to the last 16KB bank on power-on (matches Mesen2 `Sunsoft4::InitMapper`).
- **External PRG mode** (`$F000` bit 3 = 0, only when PRG > 8 banks): selects an
  external PRG page 8..(prgPageCount-1) — used by a few larger titles.
- **PRG-RAM** (`$6000-$7FFF`): 8KB, battery-backed when the iNES header has the battery
  flag set. Reads return open bus until `$F000` bit 4 is set; writes always accepted
  (so the licensing-IC timer arms regardless).
- **Licensing IC** (Namco-style, *Nantettatte!! Baseball* only): any write to
  `$6000-$7FFF` arms a ~107,520-CPU-cycle countdown during which `$8000-$BFFF`
  returns open bus. Counts down once per CPU (M2) cycle via the standard
  `tickCpuCycle()` hook.
- **Verification:** unit tests in `Mapper68Test.kt` (27 cases) cover register decode,
  2KB CHR banking, mirroring modes, external PRG, PRG-RAM enable + open bus, licensing
  timer arm/expiry, and save/load round-trip. The PRG-RAM buffer is exposed via
  `batteryBackedRam()` so `.sav` persistence follows the same path as Mapper 1/4.

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

## Mapper 113 (HES NTD-8 / PT-554A)
**Status:** Working

- **Games:** *Mind Blower Pak* and *Total Funpak* — the two HES Australia (1992) unlicensed
  multicarts. Unrelated to the NINA-003/006 family (Mapper 3) despite a superficially similar
  register, and unrelated to MMC-anything.
- **What it is:** a single 8-bit control register, banking the **entire** 32 KB PRG window at
  `$8000-$FFFF` and the 8 KB CHR window at `$0000-$1FFF`. No fixed bank, no PRG-RAM, no IRQ.
- **Register decode — the *only* Nestlin mapper whose banking register lives outside PRG space.**
  The chip-select gate is `(addr & 0xE100) == 0x4100`: A8 set, A9-A12 clear, A13-A15 clear — so
  the register aliases across `$4100-$41FF`, `$4300-$43FF`, … `$5F00-$5FFF` (16 pages), low 8 bits
  ignored. Bit layout (NESdev iNES Mapper 113, matching Mesen `Mapper113::WriteRegister`):
  ```
  bit 7 6 5 4 3 2 1 0
      M C P P P C C C
  ```
  `prgBank = (v >> 3) & 0x07` (bits 3-5), `chrBank = ((v >> 3) & 0x08) | (v & 0x07)` (bit 6 is the
  CHR high bit, bits 0-2 the low bits), `vertical = (v & 0x80) != 0`. Both fields wrap modulo the
  ROM's bank count.
- **Power-on:** the **last** 32 KB PRG bank is mapped (not bank 0). Mind Blower Pak's init code
  lives in the last bank; its first register write is `$59`, which under the correct decode selects
  PRG bank 3 — i.e. *keeps* the last bank mapped — and the boot continues into the RAM main loop.
- **Open-bus (`$0000-$7FFF`) reads:** the chip has nothing to drive there, so `cpuRead` returns the
  residual 6502 data-bus byte (`Mapper.dataBus`, fed by `Memory`) rather than 0. Without it, Mind
  Blower Pak's reset trampoline decodes a `BRK` and never boots.
- **Region:** `(australia)` in the NO-INTRO filename is a *where-sold* marker, not a timing one —
  HES shipped NTSC silicon. `GamePak.forceNtscMappers(113) = Region.NTSC` overrides the filename's
  PAL guess to match the real cartridge's frame rate / palette / CPU:PPU ratio. It's a
  hardware-accuracy override, not a boot fix — both games boot under either region once the decode
  is correct (the #163 garbled title was the decode bug below, not a timing issue).
- **CHR:** 8 KB banked from CHR ROM; a 0 KB-CHR dump gets 8 KB of writable CHR RAM (same fallback
  as Mappers 2/3/7/11/33/34/71).
- **Verification:** `Mapper113Test` covers the full PRG/CHR bank range, the bit decode (asserted
  through a spec-encoding `reg(prg, chr, vertical)` helper so the tests can't drift into the
  implementation's own formula), 16-page register aliasing, open-bus reads, CHR-RAM fallback, and
  save/load. `Mapper113RegressionTest` (`@RequiresMesen2`) byte-compares CHR vs Mesen2 at frame 60.
  `Mapper113TotalFunpakBootTest` checks the second HES game's power-on state. **Issue #163** was a
  decode bug here — PRG read from bits 4-6 instead of 3-5 — which sent Mind Blower Pak's first boot
  write to the wrong bank; the 31 original unit tests passed only because they hand-rolled the same
  wrong formula. Lesson: assert mapper decode against the *spec value*, not a magic byte derived
  from the implementation.

## Mapper 119 (Nintendo MMC6 / MMC3C variant)
**Status:** Working (Added 2026-07-12, GH #136)

- **Games:** *Metal Storm*, *StarTropics II — Zoda's Revenge*, *Burai Fighter*,
  *High Speed*, *Pin-Bot*, *Mighty Bomb Jack*, *Power Blade 2*, *Tetra Star: The Fighter*.
  About 8 known titles.
- **What it is:** an MMC3 derivative with a 1 KB on-board work-RAM block instead of the
  MMC3 8 KB `$6000-$7FFF` PRG-RAM. PRG banking, CHR banking, and scanline IRQ are
  bit-identical to MMC3 — we subclass `Mapper4` and only override the WRAM path.
- **iNES 1.0 vs NES 2.0:** iNES 1.0 dumps of MMC6 titles ship with mapper number
  `119`. NES 2.0 dumps ship as mapper `4` with **submapper 1** (`Header.submapper` is
  the high nibble of NES 2.0 byte 8). Both paths must work: this `Mapper119` class
  handles the iNES-1.0 / mapper-119 dump form; the NES-2.0 / mapper-4 submapper-1 form
  is already routed through `Mapper4` and inherits the standard MMC3 WRAM behaviour —
  which is **wrong** for MMC6 (it would give an 8 KB PRG-RAM at `$6000-$7FFF` when
  real hardware exposes 1 KB at `$7000-$73FF`). NES-2.0-with-submapper dispatch into
  Mapper119 is a follow-up if any pure NES-2.0 MMC6 dump turns up broken; current
  iNES-1.0 dumps of Metal Storm are mapper 4 plain (no submapper field), so the
  existing `Mapper4` path covers them.
- **Work-RAM layout** (the whole reason for the chip):
  | Bank | Address      | `$A001` read bit | `$A001` write bit |
  |------|--------------|------------------|-------------------|
  | 0    | `$7000-$71FF`| bit 5            | bit 4             |
  | 1    | `$7200-$73FF`| bit 7            | bit 6             |
  The whole 1 KB is gated by **bit 5 of `$8000`** (W enable). When that bit is clear,
  reads return 0 and writes are discarded. **No PRG-RAM at `$6000-$6FFF`** — that
  range is open bus on real hardware.
- **Why the IRQ-register read-as-status behaviour is NOT modelled:** the GH #136
  issue text described reads of `$C000/$C001/$E000/$E001` as "returning the IRQ
  counter byte" — Mesen2's `MMC3.h` (the project's reference oracle) does not
  implement this. Its `BaseMapper::ReadRegister` defaults to `0` and the address
  falls through to the mapped PRG bank. Per the project's "Mesen wins" oracle
  policy (proven on RAMBO-1, VRC4, Mapper 65, Mapper 68, Mapper 18), we mirror
  Mesen's behaviour. A real game reading those addresses sees whatever PRG bank is
  mapped at `$C000-$DFFF`/`$E000-$FFFF`.
- **Verification:** `Mapper119Test` (15 cases) covers WRAM enable/disable, the four
  per-bank R/W control bits independently, the `$6000-$6FFF` open-bus, the inherited
  MMC3 PRG/CHR banking, the inherited scanline IRQ, save-state round-trip, and the
  `MapperStateSnapshot` exposing `wrRamEnabled`/`wrRamControl`. Real-ROM gate:
  `./gradlew bootcheck` on *Pin-Bot (USA)* and *High Speed (USA)* both return
  **PASS** (rendering enabled by frames 15–20, ~20–22 % non-blank, PRG+CHR banks
  moving, NMIs firing ~108 times in 120 frames). *Metal Storm (USA)* (which is
  mapper 4 plain in this dump) continues to boot via `Mapper4` unchanged.
- **Known limitation — ~60-frame boot-phase offset.** Both Pin-Bot and High Speed
  show a ~60-frame (≈1 second NTSC) animation-phase offset between Nestlin and
  Mesen2: at any given frame number, one emulator is at one phase of the title-
  screen animation cycle and the other is at the next phase. The mapper is
  correct — the text DOES render in Nestlin, just on a different frame than
  Mesen2. IRQ/NMI fire counts are normal in both emulators (NMI ~1/frame,
  irqCount=0 in both — these games don't use the scanline IRQ), so the
  offset is from the project-wide CPU-cycle-accounting difference (Nestlin's
  `getInstructionCount()` vs Mesen2's M2 cycle count, scanline-261 vs
  scanline-240 capture offset). Same class of divergence documented for
  Mapper 18 / Jaleco SS880006. Doesn't block real-game playability, but
  reviewers eyeballing the per-frame PNGs will see "Nestlin frame N = Mesen2
  frame N±60" for the title animation cycle.

## Mapper 228 (Action 52 / Active Enterprises)
**Status:** Working — boots to the game-selection menu (Added 2026-06-30, GH #140)

- **Games:** *Action 52* (the infamous 52-game multicart) and *Cheetahmen II*.
- **What makes it unique in Nestlin:** the written *value* contributes only 2 bits;
  the *address* carries the PRG bank, CHR high nibble, PRG mode and mirroring. It is the
  only mapper here where the bank-select is encoded in `addr` rather than `value`.
- **Oracle:** Mesen2's `Core/NES/Mappers/Unlicensed/ActionEnterprises.h` (the nesdev wiki
  page agrees on the bit layout). The GitHub issue's "chip-3 reads return open bus" prose
  was self-contradictory — see the PRG note below.
- **Register decode (`cpuWrite`, $8000-$FFFF):**
  - `chipSelect = (addr >> 11) & 3` (A11-A12)
  - `prgPage = ((addr >> 6) & 0x1F) | (chipForIndex << 5)` — 7-bit bank index 0-95
  - `addr & 0x20` (A5): set → 32KB mode (same 16KB page in both windows); clear →
    adjacent 16KB pair (`prgBank0 = page & 0xFE`, `prgBank1 = (page & 0xFE) + 1`)
  - `chrBank = ((addr & 0x0F) << 2) | (value & 0x03)` — 4 bits from A0-A3 + 2 bits from value
  - mirroring: `addr & 0x2000` (A13) → 1 = Horizontal, 0 = Vertical
- **PRG — three chips, one hole.** Physically chip 0/1/3 exist (each 512KB); chip 2 is
  absent. The .nes dump packs the three chips contiguously into 96 × 16KB banks (chip 3's
  data at banks 64-95, file offset $100010). Mesen remaps `chipSelect 3 → 2` *for indexing*
  so the third chip is reachable as banks 64-95 (the value real games write). Nestlin keeps
  that remap **and** flags a literal `chipSelect == 2` write as open bus (a chip-2 *read*
  returns the data bus, per the wiki/hardware). No real game selects chip 2, so this is
  invisible to the Mesen2 state-diff yet honours both the wiki and the issue's open-bus
  requirement — and, unlike the issue's prose, keeps every third-chip game playable.
- **CHR:** single 8KB window at PPU $0000-$1FFF, 6-bit bank (0-63).
- **Initial state:** Mesen's `InitMapper`/`Reset` both call `WriteRegister($8000, 0)`
  (→ prgBank0=0, prgBank1=1, chrBank=0, vertical). Without it the menu hangs on black.
  Replayed in `Mapper228`'s `init`; Nestlin has no per-mapper reset hook, so the soft-reset
  replay isn't modelled (unnecessary for cold boot, which is where games depend on it).
- **No PRG-RAM, no IRQ, no audio expansion.** Reads below $8000 are open bus. The disputed
  16-bit "RAM" at $4020-$4023 noted on the wiki is not emulated (Nestopia omits it too).
- **Verification:** `Mapper228Test` (17 cases) covers dispatch, address-only PRG banking
  across all 96 banks in both modes, the chip-3→third-chip path, chip-2 open bus, address+value
  CHR banking across all 64 banks, value-bit isolation (only bits 0-1 matter), mirroring bit 13,
  initial state, sub-$8000 open bus, ignored sub-$8000 writes, and save/load round-trip.
  `Mapper228RegressionTest` asserts a banking register moves during boot and (`@RequiresMesen2`)
  byte-compares render output vs Mesen2 at the menu frame. Real-ROM gate: `./gradlew bootcheck`
  on *Action 52 (USA) (Rev A)* returns **PASS** (rendering on by frame 34, 27.5% non-blank,
  PRG+CHR banks moving, NMIs firing) — the game-selection menu renders.

## Adding New Mappers

1. Implement in `gamepak/` directory
2. Extend `Mapper` base class or trait
3. Update `GamePak.kt` to instantiate new mapper
4. Add entry to `MAPPER_SUPPORT.md`
5. Test with known-good ROM
6. If the mapper has `$6000-$7FFF` PRG-RAM, override `batteryBackedRam()` and set `batteryDirty = true` on successful writes (see `Mapper1.kt`, `Mapper4.kt`).
