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
 * `(addr & 0xE100) == 0x4100`: A8 must be set, A9-A12 must be clear, and
 * A13-A15 must be clear; the low 8 bits are aliased. That gives 16
 * distinct 256-byte pages — `$4100-$41FF`, `$4300-$43FF`, ..., up to
 * `$5F00-$5FFF` — and every address in any of those pages latches the
 * same control register.
 *
 * **Bit layout of the register byte:**
 * ```
 *   bit  7  6  5  4 | 3  2  1  0
 *       M  P  P  P | C  C  C  C
 * ```
 * - Bit 7 (`M`): mirroring — 1 = vertical, 0 = horizontal.
 * - Bits 4-6 (`PPP`): 32 KB PRG bank select (8 banks → 256 KB max PRG).
 * - Bit 3 (`C`): high bit of CHR bank.
 * - Bits 0-2 (`CCC`): low 3 bits of CHR bank.
 * Net: 8 PRG banks × 16 CHR banks (128 KB max CHR).
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
    // Why: Mind Blower Pak's bank-0 reset vector is $9FE0, a
    // self-replicating trampoline that copies 11 bytes to $0400 then
    // JMPs ($FFFC). The trampoline's purpose is to navigate between PRG
    // banks: bank 0 writes 0x59 (PRG=2), bank 2 writes 0x1D (PRG=1),
    // and bank 1's trampoline writes 0x1D again — looping forever
    // because JMP ($FFFC) re-reads bank 1's reset vector. The only
    // escape is the real init code in the LAST bank (bank 3, at
    // $B44E), which expects the CPU to start with the last bank mapped
    // and immediately runs `LDA #$59; STA $4120; SEI; ...; wait vblank;
    // clear RAM; set up registers; enable PPU`.
    //
    // Confirmed against Mesen2 v2.1.1: a frame-1 trace of Mind Blower
    // Pak shows the CPU executing in bank 3 at $B476 with zero
    // mapper-register writes (so the bank hasn't been switched from
    // its power-on value). A bank-0 default would loop forever in the
    // bank-1 trampoline.
    //
    // The same power-on state matches Total Funpak (also Mapper 113,
    // 4 PRG banks): bank 3's reset vector is $8010, also in the last
    // bank, and the other banks' reset vectors point at real init
    // code, not trampolines. The "fixed last bank" pattern is what
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
        prgBank = (v ushr 4) and 0x07
        chrBank = v and 0x0F
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
