# Cheat codes

This page is for players entering NES/Famicom cheat codes and contributors
maintaining their decoding and application.

Load a game, open **Emulation > Cheats...**, paste one code per line and click
**Add codes**. Mix formats by choosing a format for each batch. Each entry has an
enable checkbox and a Remove button. **Apply** activates the list and also adds
any codes still in the input box. **Cancel**, Escape and closing the dialog
discard the draft. An invalid line reports its line number and leaves the list
unchanged. Blank lines are ignored.

## Supported formats

| Format | Input | Meaning |
|---|---|---|
| NES Game Genie | Six or eight letters, e.g. `GOSSIP`, `ZEXPYGLA` | Substitute a PRG byte; eight-letter codes also compare the original byte. |
| Raw NES | `AAAA:VV` or `AAAA=VV` | Substitute value `VV` when the CPU reads address `AAAA`. |
| Conditional raw NES | `AAAA:VV:CC` or `AAAA?CC:VV` | Substitute `VV` only if the original byte is `CC`. |
| NES Pro Action Replay (FCEUX) | Eight hex digits, explicit format selection | Compatibility with the legacy FCEUX NES PAR decoder, described below. |
| Famicom Pro Action Rocky | Eight hex digits, e.g. `15C93C0A` | Decode an encrypted address, comparison and replacement byte. |

Letters and hexadecimal digits are case-insensitive. Game Genie optionally
accepts one grouping hyphen (`GOS-SIP`, `ZEXP-YGLA`). Raw addresses use four hex
digits and byte values use two. **Auto** recognizes Game Genie and raw codes;
ungrouped eight-character hexadecimal codes require selecting their format,
including letter-only codes such as `AAAAAAAA`. For a Game Genie code matching
that form, select **NES Game Genie** or use its grouping hyphen (`AAAA-AAAA`).
Replay and Rocky have different encodings. GameShark/Action Replay codes for
Game Boy, SNES, N64, PlayStation, GBA and other consoles are not NES codes and
are not supported.
An unencrypted NES address/value code published under those provider names can
be entered in the raw format.

The **NES Pro Action Replay (FCEUX)** option deliberately reproduces the
implemented legacy `FCEUI_DecodePAR` mapping, not a generic eight-digit
address/value format: from input bytes `B0 B1 B2 B3`, the CPU address is
`(B3 << 8) | (B2 + 0x7F)` and the replacement is zero, without comparison.
The first two bytes are ignored by that decoder. Use raw codes for arbitrary
replacement values, and select **Pro Action Rocky** for Rocky codes. This
compatibility option does not claim to emulate the physical Datel trainer.
Source: [FCEUX NES cheat decoder](https://github.com/TASEmulators/fceux/blob/master/src/cheat.cpp).

Game Genie decoding and the reference vectors `GOSSIP = D1DD:14` and
`ZEXPYGLA = 94A7:02:03` follow the
[TuxNES technical notes](https://tuxnes.sourceforge.net/gamegenie.html).
Rocky follows the NES/Famicom encoding also implemented by
[Mesen2](https://github.com/SourMesen/Mesen2/blob/master/Core/Shared/CheatManager.cpp).
The independent [nescode example](https://github.com/satoshinm/nescode) pairs
`15C93C0A` with `SLXPLOVS` (CPU address `9123`, value `BD`, comparison `DE`).

## Application and lifetime

Codes may target internal RAM and its mirrors (`0000-1FFF`), cartridge RAM
(`6000-7FFF`) and PRG-ROM (`8000-FFFF`). Register and expansion I/O addresses
(`2000-5FFF`) are rejected in this iteration.

All codes use **read substitution**. The game still writes normally; each CPU
read receives the replacement if its comparison matches. RAM mirrors share the
same cheat, and cartridge codes follow the mapper's currently selected bank.
For overlapping codes, the first enabled code whose comparison matches wins.
Comparisons always use the original byte, never another cheat's replacement.
The CPU data-bus latch and diagnostic observer see the replacement. OAM and DMC
DMA use the same read path.

Cheats do not modify the ROM or backing RAM. The Memory Editor and achievement
memory peeks continue to show the original storage. This is not the Memory
Editor's planned freeze/search workflow. Game execution can still change RAM
and battery saves as a consequence of a cheat.

The list belongs to the current game session. It survives soft and hard reset,
including hard reset of ROMs loaded from bytes without an on-disk path. Hard
reset uses the game-session coordinator to boot the current ROM while preserving
cheats; file-backed ROMs are reloaded, and byte-loaded ROMs use their current
cartridge image. Loading a save state keeps the currently configured codes.
Loading a game (including reloading the same file), unloading, or closing
Nestlin clears the list. Codes are not saved to disk or embedded in `.nstl` files.
A changed list clears rewind history so snapshots from a different cheat
configuration cannot be replayed. Applying an unchanged list leaves rewind
history intact.

The editor pauses the emulation thread until it closes. It is unavailable during
movie recording/playback; starting a movie loads a fresh game without cheats.
Cheat databases, provider downloads, import/export, persisted game profiles and
cheat search are deferred.

## Maintainer notes

`cheat/CheatCode.kt` validates and decodes input. `cheat/CheatEngine.kt` owns
the configured list and indexes enabled substitutions by canonical CPU address.
It allocates the index only when codes are enabled; reads allocate nothing and
perform no memory writes. Mutations require a stopped emulation thread.
The immutable list and completed index are published together through a volatile
configuration reference. Emulation-menu availability is refreshed on ROM/movie
transitions and menu opening, independently of Debug and indicator rendering.

`Memory.readBus` holds the shared address decoder for real reads and peeks.
`Memory.get` reads the original byte exactly once, applies cheats, then updates
the bus latch/observer. `Memory.peek` bypasses substitution and side effects.
`Nestlin.setCheats` owns rewind invalidation. Reset re-installs the mapper without
clearing the list; `GameSessionCoordinator.powerReset` restores the list before
reset when a file-backed ROM is reloaded.

The hermetic `CheatCodeTest` and `CheatIntegrationTest` cover published vectors,
strict input validation, bank-dependent comparisons, RAM mirrors, CPU execution,
OAM DMA, disable/removal, save states, reset and ROM lifecycle.
