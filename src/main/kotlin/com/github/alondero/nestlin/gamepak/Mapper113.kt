package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 113 (HES NTD-8 / PT-554A) — used by the two HES Australia
 * unlicensed multicarts *Mind Blower Pak* and *Total Funpak*. The chip is
 * the silicon behind the HES 6-in-1 multicart and is unrelated to the
 * NINA-003/006 family even though the register shape overlaps.
 *
 * **Register decode** — *the only Nestlin mapper with its banking
 * register outside the normal PRG space*. The chip-select gate is
 * `(addr & 0xE100) == 0x4100`, i.e. of the four masked bits A8/A13/A14/A15
 * only A14 and A8 are set (A13 and A15 clear). A14 set + A13/A15 clear pins
 * the window to `$4000-$5FFF`; A8 set picks the odd 256-byte pages within
 * it. A9-A12 and the low 8 bits (A0-A7) are *don't-care* — they alias. That
 * gives 16 distinct 256-byte pages — `$4100-$41FF`, `$4300-$43FF`, ..., up
 * to `$5F00-$5FFF` — and every address in any of those pages latches the
 * same control register.
 *
 * **Bit layout of the register byte:**
 * ```
 *   bit  7  6  5  4  3 | 2  1  0
 *       M  C  P  P  P | C  C  C
 * ```
 * - Bit 7 (`M`): mirroring — 1 = vertical, 0 = horizontal.
 * - Bits 3-5 (`PPP`): 32 KB PRG bank select (8 banks → 256 KB max PRG).
 * - Bit 6 (`C`): high bit of the CHR bank (bit 3 of the CHR index).
 * - Bits 0-2 (`CCC`): low 3 bits of CHR bank.
 * Net: 8 PRG banks × 16 CHR banks (128 KB max CHR).
 *
 * The PRG field is bits **3-5** and the CHR high bit is bit **6** — getting
 * this wrong (e.g. PRG = bits 4-6) sends the first boot write (`$59` -> PRG
 * bank 3, the last bank, where Mind Blower Pak's init code lives) to the
 * wrong bank, and the whole boot diverges. This was issue #163.
 *
 * **PRG geometry:** 32 KB window at `$8000-$FFFF`, fully banked. There
 * is no fixed last bank (the chip latches the whole window from one
 * register, unlike UNROM-style mappers).
 *
 * **CHR geometry:** 8 KB window at `$0000-$1FFF`, banked.
 *
 * **No PRG-RAM, no IRQ.** All writes outside the chip-select window are
 * silently ignored.
 */
class Mapper113(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    // CHR RAM fallback for 0 KB-CHR dumps. The two HES Australia ROMs both
    // ship CHR ROM, but a defensive fallback costs nothing and keeps
    // homebrew or truncated dumps from crashing the PPU read path.
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // 32 KB PRG bank count. `coerceAtLeast(1)` so a malformed 0-PRG ROM
    // can't make the modulo in the read path go negative and crash.
    private val prgBankCount = (programRom.size / 0x8000).coerceAtLeast(1)

    // Power-on default: the LAST PRG bank is mapped, not bank 0.
    //
    // Why: Mind Blower Pak's real init code lives in the LAST 32 KB bank
    // (bank 3 of a 128 KB ROM); its reset vector is $B400 and the init
    // routine at $B44E runs `LDA #$59; STA $4120; SEI; ...; wait vblank;
    // clear RAM; copy the main loop into RAM; enable PPU`. With the
    // canonical register decode (PRG = bits 3-5), the value $59 selects
    // PRG bank 3 — i.e. it KEEPS the last bank mapped — so the init code
    // continues running uninterrupted. The chip must therefore power on
    // with the last bank already mapped; a bank-0 default would start in
    // a different bank's code and never reach the init routine.
    //
    // (Historical note: an earlier decode bug — issue #163 — read PRG
    // from bits 4-6 instead of 3-5, so $59 selected effective bank 1
    // instead of 3 and the boot diverged immediately. The power-on bank
    // was never the problem; the register decode was. See cpuWrite.)
    //
    // The same "last bank holds init" pattern matches Total Funpak (also
    // Mapper 113): its last bank's reset vector points at real init code,
    // not a cross-bank trampoline. The "fixed last bank" power-on is what
    // every HES Australia multicart relies on for boot.
    private var prgBank = prgBankCount - 1
    private var chrBank = 0

    // Mirroring lives in bit 7 of the most recent $4100+ write. The
    // iNES header wires the initial value so a game that *never* writes
    // the register still renders correctly.
    private var verticalMirroring: Boolean =
        gamePak.header.mirroring == Header.Mirroring.VERTICAL

    // CPU data-bus value, set by Memory just before each `cpuRead`.
    // The HES NTD-8 chip has nothing of its own to return for CPU
    // reads in $0000-$7FFF, but a real 6502 returns the residual byte
    // on the data bus (open-bus). Mind Blower Pak's reset-vector
    // trampoline relies on this: a JMP $0400 at $FFE0 in PRG bank 0
    // loads $0401 = 0x59 onto the bus, then JMPs to $59A9. On a real
    // 6502 the opcode fetch at $59A9 returns 0x59 (the open-bus
    // value), not 0; 0 would be BRK, which sends the boot to the IRQ
    // vector and into a self-replicating loop. Returning [dataBus]
    // for the unmapped range reproduces Mesen2's behaviour and lets
    // the boot sequence escape the trampoline. The default Mapper
    // property is 0-on-open-bus; overriding it with a real backing
    // field opts this chip in.
    override var dataBus: Byte = 0

    override fun cpuRead(address: Int): Byte {
        // Open-bus path. The chip has no PRG-RAM and no registers
        // below $8000, so a CPU read in $0000-$7FFF has nothing of
        // its own to return. On real hardware, the data bus holds
        // whatever byte was last driven on it — the value [Memory]
        // pushed into us just before this call.
        if (address < 0x8000) return dataBus
        // 32 KB at $8000-$FFFF, fully banked. Modulo keeps oversized bank
        // numbers (a buggy write) from indexing past the ROM.
        val bankOffset = prgBank * 0x8000
        val offset = address - 0x8000
        return programRom[(bankOffset + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // Decode the chip-select gate. `(addr & 0xE100) == 0x4100` rejects
        // any address outside the $4100-$5FFF chip-select window (e.g.
        // $6100 sets A13, $6100-ish; $4000 clears A8; $6000+ sets A13).
        if ((address and 0xE100) != 0x4100) return
        val v = value.toUnsignedInt()
        // NESdev iNES Mapper 113 / Mesen `Mapper113::WriteRegister`:
        //   PRG = bits 3-5, CHR = (bit 6 << 3) | bits 0-2, mirroring = bit 7.
        prgBank = (v ushr 3) and 0x07
        chrBank = ((v ushr 3) and 0x08) or (v and 0x07)
        verticalMirroring = (v and 0x80) != 0
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRam != null) return chrRam[a]
        // 8 KB CHR bank. `% chrRom.size` so an out-of-range bank number
        // (a buggy write, or a dump with fewer CHR banks than the game's
        // code requests) just mirrors into the existing data instead of
        // crashing on a bounds error.
        return chrRom[(chrBank * 0x2000 + a) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRam != null) chrRam[address and 0x1FFF] = value
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return if (verticalMirroring) Mapper.MirroringMode.VERTICAL
        else Mapper.MirroringMode.HORIZONTAL
    }

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        out.writeBoolean(verticalMirroring)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank = input.readInt()
        chrBank = input.readInt()
        verticalMirroring = input.readBoolean()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 113,
            type = "HES NTD-8 / PT-554A",
            banks = mapOf(
                "prgBank" to prgBank,
                "chrBank" to chrBank
            ),
            registers = mapOf(
                "verticalMirroring" to if (verticalMirroring) 1 else 0
            ),
            irqState = null,
            chrRam = chrRam?.copyOf()
        )
    }
}
