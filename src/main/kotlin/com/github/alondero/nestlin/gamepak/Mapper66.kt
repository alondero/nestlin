package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 66 (GxROM) - 32KB PRG and 8KB CHR bank switching.
 *
 * Used by Super Mario Bros. + Duck Hunt, Dragon Power, Gumshoe, and other
 * simple discrete-logic cartridges.
 *
 * Bank-select register (written anywhere in $8000-$FFFF):
 *
 *     7  bit  0
 *     ---- ----
 *     xxPP xxCC
 *       ||   ||
 *       ||   ++- Select 8KB CHR ROM bank for PPU $0000-$1FFF
 *       ++------ Select 32KB PRG ROM bank for CPU $8000-$FFFF
 *
 * - PRG ROM: 32KB banks switched via bits 4-5 of $8000-$FFFF writes
 * - CHR ROM: 8KB banks switched via bits 0-1 of $8000-$FFFF writes
 * - No PRG RAM
 * - Fixed mirroring from iNES header
 */
class Mapper66(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null
    private var prgBank = 0
    private var chrBank = 0

    private val prgBankCount = programRom.size / 0x8000

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        val bankOffset = (prgBank % prgBankCount) * 0x8000
        return programRom[bankOffset + (address - 0x8000)]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x8000..0xFFFF) {
            val v = value.toUnsignedInt()
            prgBank = (v shr 4) and 0x03
            chrBank = v and 0x03
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF
        return if (chrRom.isEmpty()) {
            chrRam!![maskedAddress]
        } else {
            chrRom[(chrBank * 0x2000 + maskedAddress) % chrRom.size]
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            chrRam!![address and 0x1FFF] = value
        }
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override fun snapshot(): MapperStateSnapshot = MapperStateSnapshot(
        mapperId = 66,
        type = "GxROM",
        banks = mapOf("prg" to prgBank, "chr" to chrBank),
        registers = emptyMap(),
        irqState = null,
        chrRam = chrRam
    )

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank = input.readInt()
        chrBank = input.readInt()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }
}