package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 9 (MMC2 / PxROM) - Punch-Out!!
 *
 * PRG layout (8KB granularity):
 *  - $8000-$9FFF: switchable 8KB bank (selected by writes to $A000-$AFFF, low 4 bits)
 *  - $A000-$FFFF: fixed to the last three 8KB banks (in order)
 *
 * CHR layout: two 4KB windows. Each window has a 2-state latch (FD/FE) selecting
 * one of two CHR bank registers:
 *  - $0000-$0FFF: latch0 picks chrBank0FD or chrBank0FE
 *  - $1000-$1FFF: latch1 picks chrBank1FD or chrBank1FE
 *
 * Latch transitions fire on PPU pattern-table fetches at specific tile rows:
 *  - $0FD8-$0FDF -> latch0 = FD
 *  - $0FE8-$0FEF -> latch0 = FE
 *  - $1FD8-$1FDF -> latch1 = FD
 *  - $1FE8-$1FEF -> latch1 = FE
 *
 * The triggering read itself returns data from the CURRENT bank; the latch
 * flip affects the NEXT read in that window.
 *
 * Register writes (CPU $8000-$FFFF):
 *  - $A000-$AFFF: PRG bank (low 4 bits) for $8000-$9FFF window
 *  - $B000-$BFFF: chrBank0FD (low 5 bits)
 *  - $C000-$CFFF: chrBank0FE (low 5 bits)
 *  - $D000-$DFFF: chrBank1FD (low 5 bits)
 *  - $E000-$EFFF: chrBank1FE (low 5 bits)
 *  - $F000-$FFFF: mirroring (bit 0: 0=vertical, 1=horizontal)
 *
 * No IRQ.
 */
class Mapper9(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val prgBankCount = programRom.size / 0x2000  // 8KB units
    private val prgMask = programRom.size - 1           // power-of-2 mask; replaces % on PRG reads
    private val chrMask = if (chrRom.isEmpty()) 0 else chrRom.size - 1

    private var prgBank = 0          // 8KB, $8000-$9FFF
    private var chrBank0FD = 0       // 4KB, $0000-$0FFF when latch0 = FD
    private var chrBank0FE = 0       // 4KB, $0000-$0FFF when latch0 = FE
    private var chrBank1FD = 0       // 4KB, $1000-$1FFF when latch1 = FD
    private var chrBank1FE = 0       // 4KB, $1000-$1FFF when latch1 = FE

    // Latch state: false = FD, true = FE. Real hardware powers up in an
    // indeterminate state; FE is the conventional default used by most emulators.
    private var latch0Fe = true
    private var latch1Fe = true

    private var mirroringMode: Mapper.MirroringMode = when (gamePak.header.mirroring) {
        Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
        Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
    }

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        val bank = when (address and 0xE000) {
            0x8000 -> prgBank
            0xA000 -> prgBankCount - 3
            0xC000 -> prgBankCount - 2
            0xE000 -> prgBankCount - 1
            else -> return 0
        }
        val offset = address and 0x1FFF
        return programRom[(bank * 0x2000 + offset) and prgMask]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address < 0x8000) return
        val v = value.toUnsignedInt()
        when (address and 0xF000) {
            0xA000 -> prgBank = v and 0x0F
            0xB000 -> chrBank0FD = v and 0x1F
            0xC000 -> chrBank0FE = v and 0x1F
            0xD000 -> chrBank1FD = v and 0x1F
            0xE000 -> chrBank1FE = v and 0x1F
            0xF000 -> mirroringMode = if ((v and 0x01) == 0) {
                Mapper.MirroringMode.VERTICAL
            } else {
                Mapper.MirroringMode.HORIZONTAL
            }
            // $8000-$9FFF writes are ignored on MMC2
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF

        // Resolve current bank BEFORE the latch may flip.
        val bank = if (maskedAddress < 0x1000) {
            if (latch0Fe) chrBank0FE else chrBank0FD
        } else {
            if (latch1Fe) chrBank1FE else chrBank1FD
        }
        val offset = maskedAddress and 0x0FFF
        val byte = if (chrRom.isEmpty()) 0 else chrRom[(bank * 0x1000 + offset) and chrMask]

        // Latch transitions for the next read in the same window.
        when (maskedAddress and 0x1FF8) {
            0x0FD8 -> latch0Fe = false
            0x0FE8 -> latch0Fe = true
            0x1FD8 -> latch1Fe = false
            0x1FE8 -> latch1Fe = true
        }

        return byte
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // MMC2 has CHR ROM; no write path needed.
    }

    override fun currentMirroring(): Mapper.MirroringMode = mirroringMode

    override fun saveState(out: DataOutput) {
        out.writeInt(prgBank)
        out.writeInt(chrBank0FD)
        out.writeInt(chrBank0FE)
        out.writeInt(chrBank1FD)
        out.writeInt(chrBank1FE)
        out.writeBoolean(latch0Fe)
        out.writeBoolean(latch1Fe)
        out.writeInt(mirroringMode.ordinal)
    }

    override fun loadState(input: DataInput) {
        prgBank = input.readInt()
        chrBank0FD = input.readInt()
        chrBank0FE = input.readInt()
        chrBank1FD = input.readInt()
        chrBank1FE = input.readInt()
        latch0Fe = input.readBoolean()
        latch1Fe = input.readBoolean()
        mirroringMode = Mapper.MirroringMode.values()[input.readInt()]
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 9,
            type = "MMC2/PxROM",
            banks = mapOf(
                "prgBank" to prgBank,
                "chrBank0FD" to chrBank0FD,
                "chrBank0FE" to chrBank0FE,
                "chrBank1FD" to chrBank1FD,
                "chrBank1FE" to chrBank1FE
            ),
            registers = mapOf(
                "latch0" to if (latch0Fe) 0xFE else 0xFD,
                "latch1" to if (latch1Fe) 0xFE else 0xFD,
                "mirroring" to mirroringMode.ordinal
            ),
            irqState = null,
            chrRam = null
        )
    }
}
