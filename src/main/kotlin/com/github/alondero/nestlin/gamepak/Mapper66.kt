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
 * - PRG ROM: 32KB banks switched via bits 0-2 of $8000-$FFFF writes
 * - CHR ROM: 8KB banks switched via bits 3-4 of $8000-$FFFF writes
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

    // Debug trace
    var cpuReadTrace: MutableList<String>? = null

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) {
            cpuReadTrace?.add("READ $%04X -> 0 (below $8000)".format(address))
            return 0
        }
        val bankOffset = (prgBank % prgBankCount) * 0x8000
        val offset = address - 0x8000
        val actualOffset = bankOffset + offset
        cpuReadTrace?.add("READ $%04X -> prgBank=%d offset=$%04X actual=$%04X".format(
            address, prgBank, offset, actualOffset))
        return programRom[actualOffset]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x8000..0xFFFF) {
            cpuReadTrace?.add("WRITE $%04X <- $%02X".format(address, value.toUnsignedInt()))
            val v = value.toUnsignedInt()
            prgBank = v and 0x07
            chrBank = (v shr 3) and 0x03
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
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        prgBank = input.readInt()
        chrBank = input.readInt()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }
}