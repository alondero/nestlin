package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 3 (CNROM) - 8KB CHR bank switching. Used by Star Soldier,
 * Solomon's Key, Gradius and many other early commercial games.
 *
 * - Fixed PRG ROM at $8000-$FFFF (16 or 32KB)
 * - CHR ROM switched in 8KB banks via a write to ANY address in the
 *   $8000-$FFFF window (the bank register is mirrored across the whole
 *   PRG space; some games — Star Soldier writes to $D030 — exploit this
 *   for bus-conflict-safe register updates by reading-then-writing a
 *   PRG ROM byte at the same address).
 * - Bank value: low 2 bits select one of 4×8KB banks (32KB CHR max
 *   for officially-licensed games).
 * - Fixed mirroring from iNES header.
 */
class Mapper3(private val gamePak: GamePak) : Mapper {

    /** Recorded (address, value) for every CPU write into $8000-$FFFF when enabled. */
    data class Write(val address: Int, val value: Int)

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null
    private var chrBank = 0

    // Diagnostic: when non-null, every PRG-window write is appended.
    var writeTrace: MutableList<Write>? = null

    override fun cpuRead(address: Int): Byte {
        // PRG fixed at $8000-$FFFF (32KB)
        return programRom[(address - 0x8000) and 0x7FFF]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x8000..0xFFFF) {
            writeTrace?.add(Write(address, value.toUnsignedInt()))
            // CNROM: bank register decodes across the entire $8000-$FFFF window.
            chrBank = value.toUnsignedInt() and 0x03
        }
    }

    override fun snapshot(): MapperStateSnapshot = MapperStateSnapshot(
        mapperId = 3,
        type = "CNROM",
        banks = mapOf("chr" to chrBank),
        registers = emptyMap(),
        irqState = null,
        chrRam = chrRam
    )

    override fun ppuRead(address: Int): Byte {
        return if (chrRom.isEmpty()) {
            chrRam!![address and 0x1FFF]
        } else {
            // 8KB CHR banks
            chrRom[(chrBank * 0x2000 + (address and 0x1FFF)) % chrRom.size]
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            // CHR RAM is writable
            chrRam!![address and 0x1FFF] = value
        }
        // CHR ROM is read-only
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(chrBank)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        chrBank = input.readInt()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }
}
