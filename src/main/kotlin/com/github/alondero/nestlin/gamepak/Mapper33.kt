package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 33 (Taito TC0190 / TC0350).
 *
 * Discrete-logic Taito mapper used by ~12 Famicom titles, including
 * *Don Doko Don*, *Captain Tsubasa Vol. II — Super Striker*, *Akira*,
 * *Power Blazer*, *Operation Wolf*, and the Famicom port of *Bubble Bobble*.
 *
 * **Register decoding.** Every write in `$8000-$BFFF` is decoded as
 * `addr & 0xA003`. Bit 13 picks the `$8000` vs `$A000` page, bits 0-1 pick
 * one of four registers within the page, and every other address bit is
 * ignored. That gives eight registers:
 *
 *  | Reg   | Encoded by             | Effect                                                            |
 *  |-------|------------------------|-------------------------------------------------------------------|
 *  | $8000 | `addr & 0xA003 == 0x8000` | PRG bank 0 (low 6 bits) + mirroring (bit 6: 0=Vert, 1=Horiz) |
 *  | $8001 | `addr & 0xA003 == 0x8001` | PRG bank 1 (low 6 bits)                                        |
 *  | $8002 | `addr & 0xA003 == 0x8002` | CHR bank pair for `$0000-$07FF` — value = 2KB bank number      |
 *  | $8003 | `addr & 0xA003 == 0x8003` | CHR bank pair for `$0800-$0FFF` — value = 2KB bank number      |
 *  | $A000 | `addr & 0xA003 == 0xA000` | CHR bank for `$1000-$13FF` (1KB)                                |
 *  | $A001 | `addr & 0xA003 == 0xA001` | CHR bank for `$1400-$17FF` (1KB)                                |
 *  | $A002 | `addr & 0xA003 == 0xA002` | CHR bank for `$1800-$1BFF` (1KB)                                |
 *  | $A003 | `addr & 0xA003 == 0xA003` | CHR bank for `$1C00-$1FFF` (1KB)                                |
 *
 * **PRG geometry (8KB granularity, 4 banks):**
 *  - `$8000-$9FFF`: switchable, selected by `$8000` register (low 6 bits).
 *  - `$A000-$BFFF`: switchable, selected by `$8001` register (low 6 bits).
 *  - `$C000-$DFFF`: fixed to `prgBankCount - 2` (second-to-last 8KB bank).
 *  - `$E000-$FFFF`: fixed to `prgBankCount - 1` (last 8KB bank).
 *
 * **CHR geometry (mixed 2KB+1KB granularity, 8 banks in 1KB units):**
 *  - `$0000-$03FF`, `$0400-$07FF`: pair set by `$8002`. The byte written is
 *    a *2KB* bank index; Mesen's decode maps it as `(v*2, v*2+1)` over 1KB
 *    pages, so the LSB of the written value is preserved (unlike MMC3
 *    R0/R1, where the LSB is dropped).
 *  - `$0800-$0BFF`, `$0C00-$0FFF`: pair set by `$8003` (same decoding).
 *  - `$1000-$13FF`: set by `$A000` (1KB).
 *  - `$1400-$17FF`: set by `$A001` (1KB).
 *  - `$1800-$1BFF`: set by `$A002` (1KB).
 *  - `$1C00-$1FFF`: set by `$A003` (1KB).
 *
 * **Mirroring:** bit 6 of the value written to `$8000`: 0 = vertical,
 * 1 = horizontal. No 1-screen mode. The header mirroring acts as the
 * initial value until the game writes `$8000`.
 *
 * **No IRQ. No PRG-RAM.** (The TC0350 variant adds a scanline IRQ and is
 * a separate iNES mapper number — 48 — out of scope here.)
 */
class Mapper33(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    // CHR RAM fallback for 0KB-CHR dumps. The original cartridges all ship CHR
    // ROM, but a defensive fallback costs nothing and keeps homebrew dumps
    // from crashing the emulator on PPU read.
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // 8KB PRG bank count. `coerceAtLeast(1)` so a malformed 0-PRG ROM can't
    // make `prgBankCount - 1` go negative and crash the fixed-bank read path.
    private val prgBankCount = (programRom.size / 0x2000).coerceAtLeast(1)

    // PRG registers (6-bit each).
    private var prgBank0 = 0                 // $8000-$9FFF
    private var prgBank1 = 0                 // $A000-$BFFF

    // CHR registers — stored uniformly as 1KB bank indices so `ppuRead` can
    // just look them up. `chrBanks[0..1]` come from the $8002 pair, [2..3]
    // from the $8003 pair, [4..7] from $A000..$A003.
    private val chrBanks = IntArray(8) { 0 }

    // Mirroring lives in bit 6 of the most recent $8000 write. The header
    // wires the initial value so a game that *never* writes $8000 still
    // renders correctly.
    private var horizontalMirroring: Boolean = gamePak.header.mirroring == Header.Mirroring.HORIZONTAL

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        // Four 8KB banks; bits 13-14 of the address pick which window.
        return when (address and 0xE000) {
            0x8000 -> programRom[(prgBank0 * 0x2000 + (address - 0x8000)) % programRom.size]
            0xA000 -> programRom[(prgBank1 * 0x2000 + (address - 0xA000)) % programRom.size]
            0xC000 -> {
                // Second-to-last 8KB bank, fixed by the chip at power-on.
                val bank = (prgBankCount - 2).coerceAtLeast(0)
                programRom[(bank * 0x2000 + (address - 0xC000)) % programRom.size]
            }
            else -> {
                // 0xE000: last 8KB bank, fixed.
                val bank = (prgBankCount - 1).coerceAtLeast(0)
                programRom[(bank * 0x2000 + (address - 0xE000)) % programRom.size]
            }
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address < 0x8000) return
        val v = value.toUnsignedInt()
        // Mesen's TaitoTc0190 decodes registers with `addr & 0xA003`. Every
        // address bit other than bits 13, 1, and 0 is masked out, so e.g.
        // $8042, $80FE, and $9FF2 all hit register $8002.
        when (address and 0xA003) {
            0x8000 -> {
                prgBank0 = v and 0x3F
                // Bit 6 = mirroring (0=Vert, 1=Horiz). The chip latches this
                // every $8000 write — there's no separate mirroring register.
                horizontalMirroring = (v and 0x40) != 0
            }
            0x8001 -> prgBank1 = v and 0x3F
            0x8002 -> {
                // 2KB bank for $0000-$07FF, expanded into two 1KB indices.
                // LSB is preserved per NESdev — *not* dropped like MMC3 R0/R1.
                chrBanks[0] = v * 2
                chrBanks[1] = v * 2 + 1
            }
            0x8003 -> {
                // 2KB bank for $0800-$0FFF.
                chrBanks[2] = v * 2
                chrBanks[3] = v * 2 + 1
            }
            // The four 1KB CHR registers for $1000-$1FFF. `4 + (addr & 3)`
            // collapses the four cases into one branch — exact same shape
            // Mesen uses.
            0xA000, 0xA001, 0xA002, 0xA003 -> chrBanks[4 + (address and 0x03)] = v
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) {
            return chrRam!![a]
        }
        // Eight 1KB CHR windows. Integer division picks the bank, modulo gives
        // the offset inside the bank. `% chrRom.size` keeps an out-of-range
        // bank (game writes a bank number bigger than the ROM) from indexing
        // past the array; on hardware the upper address bits would be open
        // bus, but mirroring is a safer behaviour for a test fixture.
        val bankIndex = chrBanks[a ushr 10]
        val offsetInBank = a and 0x03FF
        return chrRom[(bankIndex * 0x0400 + offsetInBank) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRam != null) chrRam[address and 0x1FFF] = value
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return if (horizontalMirroring) Mapper.MirroringMode.HORIZONTAL
        else Mapper.MirroringMode.VERTICAL
    }

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank0)
        out.writeInt(prgBank1)
        for (b in chrBanks) out.writeInt(b)
        out.writeBoolean(horizontalMirroring)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank0 = input.readInt()
        prgBank1 = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        horizontalMirroring = input.readBoolean()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 33,
            type = "Taito TC0190",
            banks = mapOf(
                "prgBank0" to prgBank0,
                "prgBank1" to prgBank1,
                "chrBank0" to chrBanks[0],
                "chrBank1" to chrBanks[1],
                "chrBank2" to chrBanks[2],
                "chrBank3" to chrBanks[3],
                "chrBank4" to chrBanks[4],
                "chrBank5" to chrBanks[5],
                "chrBank6" to chrBanks[6],
                "chrBank7" to chrBanks[7]
            ),
            registers = mapOf(
                "horizontalMirroring" to if (horizontalMirroring) 1 else 0
            ),
            irqState = null,
            chrRam = chrRam?.copyOf()
        )
    }
}
