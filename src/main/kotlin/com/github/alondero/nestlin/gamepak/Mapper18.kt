package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 18 — Jaleco SS880006 (a.k.a. "Jaleco SS 88006"). The chip behind
 * the Japanese release *Jajamaru Gekimaden — Maboroshi no Kinmajou* (the
 * localized name is *Magical Kid's Adventure* / *Nephrite*) and several other
 * Jaleco Famicom titles. It is sometimes described as "a scrambled VRC4":
 * the register window has the same shape (`$8000-$FFFF` aliased across the
 * address space) but with a chaotic scattered-decode layout and 8KB PRG /
 * 1KB CHR granularity instead of VRC's 16KB / 8KB + 2KB scheme.
 *
 * The GitHub issue #135 spec ("16KB PRG, 8KB CHR, no IRQ, no PRG-RAM")
 * describes a **simplified** register layout and is incorrect against both
 * the [nesdev wiki page](https://www.nesdev.org/wiki/INES_Mapper_018) and
 * Mesen2's `Core/NES/Mappers/Jaleco/JalecoSs88006.h` oracle. This
 * implementation follows the Mesen2/nesdev register model precisely —
 * RAMBO-1 (issue #79 / commit `f6f207a`) shipped four broken versions when
 * prose-level specifications were trusted over the reference source.
 *
 * ## Register map
 *
 * The chip uses an `addr & 0xF003` decode for the PRG/CHR bank registers —
 * even address writes update low 4 bits of the bank, odd address writes
 * update high 4 bits. Only `D0-D3` of the data byte are used.
 *
 * | CPU Address  | Effect                                                       |
 * |--------------|--------------------------------------------------------------|
 * | `$8000`/$8001| PRG bank 0 (8KB at `$8000-$9FFF`)                            |
 * | `$8002`/$8003| PRG bank 1 (8KB at `$A000-$BFFF`)                            |
 * | `$9000`/$9001| PRG bank 2 (8KB at `$C000-$DFFF`)                            |
 * | `$A000`/$A001| CHR bank 0 (1KB at `$0000-$03FF`)                            |
 * | `$A002`/$A003| CHR bank 1 (1KB at `$0400-$07FF`)                            |
 * | `$B000`/$B001| CHR bank 2 (1KB at `$0800-$0BFF`)                            |
 * | `$B002`/$B003| CHR bank 3 (1KB at `$0C00-$0FFF`)                            |
 * | `$C000`/$C001| CHR bank 4 (1KB at `$1000-$13FF`)                            |
 * | `$C002`/$C003| CHR bank 5 (1KB at `$1400-$17FF`)                            |
 * | `$D000`/$D001| CHR bank 6 (1KB at `$1800-$1BFF`)                            |
 * | `$D002`/$D003| CHR bank 7 (1KB at `$1C00-$1FFF`)                            |
 * | `$E000..E003` | IRQ reload nibbles `[3:0]` / `[7:4]` / `[11:8]` / `[15:12]`  |
 * | `$F000`      | Reload IRQ counter from latch, ACK pending IRQ               |
 * | `$F001`      | Bit 0: IRQ enable; Bits 1-3: counter width (see below)       |
 * | `$F002`      | Mirroring (0=Horiz, 1=Vert, 2=1ScA, 3=1ScB)                   |
 * | `$F003`      | Expansion audio (µPD7755C/µPD7756C — NOT implemented)         |
 *
 * ## IRQ
 *
 * Counter widths are encoded in `$F001` bits 1-3:
 *  - `%000` = 16-bit (mask `$FFFF`),
 *  - `%001` (bit 2) = 12-bit (mask `$0FFF`),
 *  - `%01x` (bit 1) = 8-bit (mask `$00FF`),
 *  - `%1xx` (bit 3) = 4-bit (mask `$000F`).
 *
 * The chip counts down every CPU cycle when the enable bit is set; when the
 * low N bits (N = masked width) reach zero the IRQ fires. `$F000` reloads
 * the counter from the 4-nibble latch AND acknowledges any pending IRQ.
 * `$F001` writes also acknowledge the pending IRQ (Mesen's
 * `ClearIrqSource(External)` on the same write). [$E000-$E003] are the four
 * reload nibbles that compose the 16-bit reload value.
 *
 * The counter is **clocked every CPU cycle** — like the FME-7 / Sunsoft
 * mappers, NOT PPU A12 edges.
 *
 * ## PRG geometry (8KB granularity)
 *
 *  - `$8000-$9FFF` — switchable 8KB bank 0 (register `$8000`/`$8001`)
 *  - `$A000-$BFFF` — switchable 8KB bank 1 (register `$8002`/`$8003`)
 *  - `$C000-$DFFF` — switchable 8KB bank 2 (register `$9000`/`$9001`)
 *  - `$E000-$FFFF` — fixed last 8KB bank (`SelectPrgPage(3, -1)` in Mesen)
 *
 * ## CHR geometry (1KB granularity × 8)
 *
 * Each CHR register holds an 8-bit bank number built from two 4-bit
 * nibble-writes at consecutive addresses (even = low, odd = high). Maximum
 * 256 1KB banks = 256KB of CHR ROM — well within iNES byte 5's 8KB-units
 * max of 0xFF = 2032KB. The Magical Kid's Adventure cart uses 16 CHR
 * banks (128KB) per the rom_info scan; only the first 16 bank-numbers are
 * ever selected by the game.
 *
 * ## IRQ & counter width — be careful
 *
 * The masked-counter-down semantics (NOT a fire-on-zero-up-counter) make
 * this mapper a cousin of the [com.github.alondero.nestlin.gamepak.Mapper16]
 * 16-bit "decrement from the latch" reload on every `$A` write. The key
 * differences from Mapper 16:
 *
 *  - Counter is *clocked every memory read/write cycle* (`EnableCpuClockHook`),
 *    not selectively as in Mapper 16.
 *  - Counter width is configurable, gated by a 2-of-3 bit pattern on `$F001`
 *    bits 1-3 (not a single "16-bit / 8-bit" mode select).
 *  - ACK happens automatically on `$F001` writes too — every modification of
 *    the IRQ control register also clears the pending IRQ, mirroring the
 *    Mesen2 `ClearIrqSource` call.
 *
 * If a future cartridge's IRQ counter size differs from the documented
 * default of 16-bit (e.g. via `[mapper].irqCounterSize` per the issue
 * spec), it should be set BEFORE the first `$F001` write that enables
 * counting — otherwise the IRQ may never fire at the expected cadence.
 */
class Mapper18(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    private val prgBank8Count = (programRom.size / 0x2000).coerceAtLeast(1)

    // 3 × 8KB PRG banks. The 4th bank at $E000-$FFFF is fixed to the last 8KB of PRG.
    private val prgBanks = IntArray(3)

    // 8 × 1KB CHR banks. Bank number is 8 bits; built from two 4-bit writes.
    private val chrBanks = IntArray(8)

    // IRQ state. _irqMask selects which bits count down; the 4-nibble reload
    // value lives in `_irqReloadValue[0..3]` (only `_irqReloadValue[0]` low 4
    // bits actually matter for any rendered width, but we keep all 4 to
    // round-trip Mesen's reload-composition math).
    private val irqReloadValue = IntArray(4)
    private val irqMask = intArrayOf(0xFFFF, 0x0FFF, 0x00FF, 0x000F)
    private var irqCounter = 0
    private var irqCounterSize = 0    // index into irqMask[]; 0 = 16-bit
    private var irqEnabled = false
    private var irqPending = false

    // Mirroring register value (0..3). Defaults to the header so the value
    // is stable before any `$F002` write.
    private var mirroringControl = when (gamePak.header.mirroring) {
        Header.Mirroring.HORIZONTAL -> 0
        Header.Mirroring.VERTICAL -> 1
    }

    override fun cpuRead(address: Int): Byte {
        return when (address) {
            in 0x8000..0x9FFF -> readPrgBank(0, address - 0x8000)
            in 0xA000..0xBFFF -> readPrgBank(1, address - 0xA000)
            in 0xC000..0xDFFF -> readPrgBank(2, address - 0xC000)
            in 0xE000..0xFFFF -> readPrgBank(3, address - 0xE000)
            else -> dataBus
        }
    }

    /**
     * Internal: returns byte from the 8KB PRG bank at index [bank] (0..3).
     * Bank 3 is the fixed last bank; banks 0-2 use [prgBanks]. Modulo
     * defends against a 0-length PRG (truncated dump).
     */
    private fun readPrgBank(bank: Int, offset: Int): Byte {
        val actualBank = when (bank) {
            3 -> prgBank8Count - 1
            else -> prgBanks[bank] % prgBank8Count
        }
        return programRom[actualBank * 0x2000 + (offset and 0x1FFF)]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt() and 0x0F
        val addr = address and 0xFFFF
        val highBit = (addr and 0x01) != 0
        when (addr and 0xF003) {
            // PRG bank registers: even address writes low 4 bits, odd writes
            // high 4 bits. Each register's two addresses are fall-through
            // case labels in Mesen2's switch — both list the same handler
            // and the `(addr & 0x01)` bit selects low vs high nibble.
            0x8000, 0x8001 -> prgBanks[0] = updateBankByte(prgBanks[0], v, highBit)
            0x8002, 0x8003 -> prgBanks[1] = updateBankByte(prgBanks[1], v, highBit)
            0x9000, 0x9001 -> prgBanks[2] = updateBankByte(prgBanks[2], v, highBit)

            // CHR bank registers (8 × 1KB).
            0xA000, 0xA001 -> chrBanks[0] = updateBankByte(chrBanks[0], v, highBit)
            0xA002, 0xA003 -> chrBanks[1] = updateBankByte(chrBanks[1], v, highBit)
            0xB000, 0xB001 -> chrBanks[2] = updateBankByte(chrBanks[2], v, highBit)
            0xB002, 0xB003 -> chrBanks[3] = updateBankByte(chrBanks[3], v, highBit)
            0xC000, 0xC001 -> chrBanks[4] = updateBankByte(chrBanks[4], v, highBit)
            0xC002, 0xC003 -> chrBanks[5] = updateBankByte(chrBanks[5], v, highBit)
            0xD000, 0xD001 -> chrBanks[6] = updateBankByte(chrBanks[6], v, highBit)
            0xD002, 0xD003 -> chrBanks[7] = updateBankByte(chrBanks[7], v, highBit)

            // IRQ reload nibbles at $E000-$E003. Each write sets one of the
            // four 4-bit chunks that compose the 16-bit reload value; only
            // the low nibble of the byte is used (we masked above).
            0xE000 -> irqReloadValue[0] = v
            0xE001 -> irqReloadValue[1] = v
            0xE002 -> irqReloadValue[2] = v
            0xE003 -> irqReloadValue[3] = v

            // Reload IRQ counter from latch + ACK pending IRQ (Mesen's
            // `ReloadIrqCounter` followed by `ClearIrqSource`). No register
            // value to capture — the write is the trigger.
            0xF000 -> {
                irqPending = false
                irqCounter = (irqReloadValue[0] and 0x0F) or
                    ((irqReloadValue[1] and 0x0F) shl 4) or
                    ((irqReloadValue[2] and 0x0F) shl 8) or
                    ((irqReloadValue[3] and 0x0F) shl 12)
            }

            // IRQ enable + counter-size select. Mirrors Mesen's
            // `ClearIrqSource(External)` (always) and enable-bit handling.
            0xF001 -> {
                irqPending = false
                irqEnabled = (v and 0x01) != 0
                irqCounterSize = when {
                    // Bit 3 wins (4-bit > 8-bit > 12-bit in priority per Mesen
                    // source: `%1xx` -> 4-bit, else `%01x` -> 8-bit, else
                    // `%001` -> 12-bit, else 16-bit. The decrementing only
                    // counts the chosen N bits as Mesen does.
                    (v and 0x08) != 0 -> 3     // 4-bit
                    (v and 0x04) != 0 -> 2     // 8-bit
                    (v and 0x02) != 0 -> 1     // 12-bit
                    else -> 0                  // 16-bit
                }
            }

            // Mirroring control ($F002-$FFFE, the wiki notes "only $F002
            // actually decodes" in the Mesen switch).
            0xF002 -> mirroringControl = v and 0x03

            // $F003 = expansion audio µPD7755C/µPD7756C, not modelled.
            0xF003 -> { /* no-op: expansion audio not implemented */ }

            // Writes outside the register window are ignored (mapper has no
            // PRG-RAM by default; see class KDoc).
            else -> { }
        }
    }

    /**
     * Internal: combines a 4-bit half-write into the upper/lower byte of an
     * 8-bit bank number. Mesen's `UpdatePrgBank` / `UpdateChrBank` apply the
     * same pattern: when [high] is set, the value becomes bits 4-7 of the
     * byte; otherwise it becomes bits 0-3. Returns the new packed byte.
     */
    private fun updateBankByte(current: Int, nibble: Int, high: Boolean): Int {
        return if (high) (current and 0x0F) or ((nibble and 0x0F) shl 4)
        else (current and 0xF0) or (nibble and 0x0F)
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        val window = a ushr 10      // 0..7 — each CHR register covers a 1KB slice
        val bank = chrBanks[window]
        if (chrRom.isEmpty()) return 0
        // chrRom index = bank * 0x0400 + offset within 1KB window. Modulo
        // defends against the bank count exceeding rom.size.
        return chrRom[((bank and 0xFF) * 0x0400 + (a and 0x03FF)) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // SS880006 boards are CHR-ROM only; PPU writes to $0000-$1FFF go
        // to open bus. Some homebrew/clone boards paired the chip with
        // CHR-RAM — out of scope; flag with a Todo so we know where to
        // extend later if a game demands it.
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (mirroringControl and 0x03) {
            0 -> Mapper.MirroringMode.HORIZONTAL
            1 -> Mapper.MirroringMode.VERTICAL
            2 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
            3 -> Mapper.MirroringMode.ONE_SCREEN_UPPER
            else -> Mapper.MirroringMode.HORIZONTAL
        }
    }

    // ---- IRQ ----------------------------------------------------------------

    override fun tickCpuCycle() {
        if (!irqEnabled) return
        // Per Mesen2: the masked N bits decrement every CPU cycle and fire
        // the IRQ on the cycle the counter TRANSITIONS from 1 to 0. The
        // high bits outside the mask are preserved (split-and-recompose),
        // matching Mesen's `(irqCounter & ~_irqMask[_irqCounterSize]) |
        // (counter & _irqMask[_irqCounterSize])` write-back idiom.
        val mask = irqMask[irqCounterSize]
        val counter = irqCounter and mask
        val next = (counter - 1) and mask
        irqCounter = (irqCounter and mask.inv()) or next
        if (next == 0) irqPending = true
    }

    override fun isIrqPending(): Boolean = irqPending

    // ---- Snapshot -----------------------------------------------------------

    override fun snapshot(): MapperStateSnapshot {
        val banks = mutableMapOf(
            "prgBank0" to prgBanks[0],
            "prgBank1" to prgBanks[1],
            "prgBank2" to prgBanks[2],
            "chrBank0" to chrBanks[0],
            "chrBank1" to chrBanks[1],
            "chrBank2" to chrBanks[2],
            "chrBank3" to chrBanks[3],
            "chrBank4" to chrBanks[4],
            "chrBank5" to chrBanks[5],
            "chrBank6" to chrBanks[6],
            "chrBank7" to chrBanks[7]
        )
        return MapperStateSnapshot(
            mapperId = 18,
            type = "Jaleco SS880006",
            banks = banks,
            registers = mapOf("mirroring" to (mirroringControl and 0x03)),
            irqState = mapOf(
                "irqCounter" to irqCounter,
                "irqCounterSize" to irqCounterSize,
                "irqEnabled" to if (irqEnabled) 1 else 0,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = null
        )
    }

    // ---- Save state --------------------------------------------------------

    override val saveStateVersion: Int = 1

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        for (b in prgBanks) out.writeInt(b)
        for (b in chrBanks) out.writeInt(b)
        for (n in irqReloadValue) out.writeInt(n)
        out.writeInt(irqCounter)
        out.writeInt(irqCounterSize)
        out.writeBoolean(irqEnabled)
        out.writeBoolean(irqPending)
        out.writeInt(mirroringControl)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in prgBanks.indices) prgBanks[i] = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        for (i in irqReloadValue.indices) irqReloadValue[i] = input.readInt()
        irqCounter = input.readInt()
        irqCounterSize = input.readInt()
        irqEnabled = input.readBoolean()
        irqPending = input.readBoolean()
        mirroringControl = input.readInt()
    }
}
