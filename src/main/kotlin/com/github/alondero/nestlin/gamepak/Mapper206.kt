package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 206 (DxROM / Namcot 108 / Namcot 109 / Namcot 118 / Namcot 119) -
 * the Namco/Tengen simplified-MMC3 chip.
 *
 * The Namco 108 family is a stripped-down MMC3:
 *   - PRG banking mode is fixed: $8000 = R6, $A000 = R7, $C000 = second-to-last bank,
 *     $E000 = last bank (MMC3 PRG mode 0). Bit 6 of the bank-select register is ignored.
 *   - CHR banking mode is fixed: R0/R1 select 2 KB banks at $0000-$0FFF, R2-R5 select
 *     four 1 KB banks at $1000-$1FFF (MMC3 CHR mode 0). Bit 7 of the bank-select
 *     register is ignored.
 *   - Mirroring is hardwired from the iNES header — there is no $A000 mirroring
 *     register. (DRROM Gauntlet uses 4-screen, signalled via the header's 4-screen
 *     bit; the body of Namco 108 boards does not let the program change it.)
 *   - There is no scanline IRQ counter; the $C000/$C001/$E000/$E001 write pair is
 *     accepted but has no effect.
 *
 * The register protocol is otherwise identical to MMC3: $8000 selects R0-R7 and
 * $8001 writes the bank number for the selected register. (Mirroring the Mesen
 * reference: `addr &= 0x8001` and `value &= 0x3F`.)
 *
 * Games: Gauntlet (DRROM, 4-screen), Ring King (DRROM), RBI Baseball (DEROM),
 *        Dragon Buster, Pac-Man (Namco), Mappy-Land, and other Namco/Tengen titles.
 */
class Mapper206(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // Number of 8 KB PRG banks available.
    private val prgBankCount = programRom.size / 0x2000

    // 8 KB PRG banks (R6 at $8000, R7 at $A000). $C000 / $E000 are fixed.
    private var prgBank6 = 0  // $8000-$9FFF
    private var prgBankA = 1  // $A000-$BFFF (R7)

    // CHR registers: R0, R1 = 2 KB at $0000-$0FFF. R2-R5 = 1 KB at $1000-$1FFF.
    private val chrBanks = IntArray(6) { 0 }

    // 8 KB PRG-RAM at $6000-$7FFF. The Namco 108 chips expose this; games like
    // Gauntlet and RBI Baseball use it for save data when the battery flag is set.
    private val prgRam = ByteArray(0x2000)
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    // Currently selected bank-register index (0-7), set by writes to $8000.
    private var bankSelect = 0

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            // Namco 108 PRG-RAM is always enabled (no enable/protect bits in the
            // simplified register file). Open-bus on disabled reads is N/A here.
            return prgRam[address - 0x6000]
        }
        if (address < 0x8000) return 0

        // PRG mode 0 is hardwired:
        //   $8000-$9FFF: R6 (prgBank6, switchable)
        //   $A000-$BFFF: R7 (prgBankA, switchable)
        //   $C000-$DFFF: second-to-last bank (fixed)
        //   $E000-$FFFF: last bank (fixed)
        return when (address and 0xE000) {
            0x8000 -> programRom[(prgBank6 * 0x2000 + (address - 0x8000)) % programRom.size]
            0xA000 -> programRom[(prgBankA * 0x2000 + (address - 0xA000)) % programRom.size]
            0xC000 -> {
                val bank = (prgBankCount - 2).coerceAtLeast(0)
                programRom[(bank * 0x2000 + (address - 0xC000)) % programRom.size]
            }
            0xE000 -> {
                val bank = (prgBankCount - 1).coerceAtLeast(0)
                programRom[(bank * 0x2000 + (address - 0xE000)) % programRom.size]
            }
            else -> 0
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x6000..0x7FFF) {
            prgRam[address - 0x6000] = value
            batteryDirty = true
            return
        }
        if (address < 0x8000) return

        val valueInt = value.toUnsignedInt()
        // Per Mesen Namco108: collapse the full $8000-$FFFF decode down to just
        // the $8000/$8001 register pair. This matches the simplified hardware —
        // the program can still write to anywhere in the PRG window, but only
        // the bank-select/data addresses do anything.
        when (address and 0x8001) {
            0x8000 -> {
                // Bank select: bits 0-2 pick the target register. Bits 6-7 are
                // masked off because PRG mode and CHR invert are hardwired.
                bankSelect = valueInt and 0x07
            }
            0x8001 -> {
                // Bank data: pass the full 8-bit value through, matching Mapper4's
                // setBank(). The hardwired-mode masking only applies to the bank
                // *select* byte at $8000 (which is where PRG mode and CHR invert
                // live in MMC3 — they don't exist on Namco 108, so we clear bits
                // 6-7 of that byte). The bank-data byte is unmasked.
                setBank(bankSelect, valueInt)
            }
            // $A000/$A001 mirroring + PRG-RAM protect: no-op, mirroring is
            //   hardwired from the header and PRG-RAM is always enabled.
            // $C000/$C001 IRQ latch + reload: no-op, no scanline IRQ.
            // $E000/$E001 IRQ enable + disable: no-op, no scanline IRQ.
        }
    }

    private fun setBank(bankIndex: Int, value: Int) {
        when (bankIndex) {
            0, 1 -> chrBanks[bankIndex] = value          // 2 KB CHR banks
            in 2..5 -> chrBanks[bankIndex] = value        // 1 KB CHR banks
            6 -> prgBank6 = value and 0x3F                // 8 KB PRG bank at $8000
            7 -> prgBankA = value and 0x3F                // 8 KB PRG bank at $A000
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF

        if (chrRom.isEmpty()) {
            return chrMemory.read(maskedAddress)
        }

        // CHR mode 0 is hardwired. R0/R1 are 2 KB banks with the low bit ignored
        // (so adjacent 1 KB pages pair up). R2-R5 are 1 KB banks directly.
        return when {
            maskedAddress < 0x0800 -> chrRom[((chrBanks[0] and 0xFE) * 0x0400 + maskedAddress) % chrRom.size]
            maskedAddress < 0x1000 -> chrRom[((chrBanks[1] and 0xFE) * 0x0400 + (maskedAddress - 0x0800)) % chrRom.size]
            maskedAddress < 0x1400 -> chrRom[(chrBanks[2] * 0x0400 + (maskedAddress - 0x1000)) % chrRom.size]
            maskedAddress < 0x1800 -> chrRom[(chrBanks[3] * 0x0400 + (maskedAddress - 0x1400)) % chrRom.size]
            maskedAddress < 0x1C00 -> chrRom[(chrBanks[4] * 0x0400 + (maskedAddress - 0x1800)) % chrRom.size]
            maskedAddress < 0x2000 -> chrRom[(chrBanks[5] * 0x0400 + (maskedAddress - 0x1C00)) % chrRom.size]
            else -> chrRom[maskedAddress % chrRom.size]
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            chrMemory.write(address and 0x1FFF, value)
        }
        // CHR ROM is read-only
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        // No $A000 mirroring register — driven entirely by the iNES header.
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank6)
        out.writeInt(prgBankA)
        for (b in chrBanks) out.writeInt(b)
        out.writeInt(bankSelect)
        out.write(prgRam)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank6 = input.readInt()
        prgBankA = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        bankSelect = input.readInt()
        input.readFully(prgRam)
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 206,
            type = "DxROM / Namcot 108",
            banks = mapOf(
                "prgBank6" to prgBank6,
                "prgBankA" to prgBankA,
                "chrBankR0" to chrBanks[0],
                "chrBankR1" to chrBanks[1],
                "chrBankR2" to chrBanks[2],
                "chrBankR3" to chrBanks[3],
                "chrBankR4" to chrBanks[4],
                "chrBankR5" to chrBanks[5]
            ),
            registers = mapOf(
                "bankSelect" to bankSelect
            ),
            irqState = null,
            // Snapshot chrRam for debug display: extract via the peek seam.
            chrRam = chrMemory.snapshotBytes(),
            prgRam = prgRam.copyOf()
        )
    }
}
