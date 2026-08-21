package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 2 (UxROM: UNROM/UOROM) - 16KB PRG bank switching.
 *
 * - `$8000-$BFFF`: switchable 16KB PRG bank.
 * - `$C000-$FFFF`: fixed to the last 16KB PRG bank.
 * - UNROM boards expose up to 3 bank bits (128KB); UOROM exposes 4 (256KB).
 * - Bank values wrap to the available PRG size, matching Mesen2's page mapping.
 * - Mirroring is fixed by the cartridge header.
 *
 * Standard UxROM boards provide 8KB of CHR-RAM. The optional banked CHR-ROM
 * path is retained for compatibility with existing nonstandard dumps.
 */
class Mapper2(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    // UxROM exposes 8KB of CHR-RAM when the cart ships no CHR ROM.
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)
    private val prgBankCount = programRom.size / 0x4000
    private var chrBank = 0
    // $8000-$BFFF switchable, $C000-$FFFF fixed to last bank.
    private var prgBank = (prgBankCount - 1).coerceAtLeast(1)

    private fun prgByte(bankIndex: Int, windowBase: Int, address: Int): Byte {
        return programRom[((bankIndex * 0x4000) + (address - windowBase)) % programRom.size]
    }

    /**
     * Bus-conflict AND mask (GH #236). The discrete-logic UxROM board shares
     * its data bus between the PRG ROM and the 74HC161 bank-select latch, so
     * a CPU write to the bank register is bitwise-ANDed with whatever PRG byte
     * lives at the write address. The window split matters: writes to
     * `$8000-$BFFF` see the switchable bank; writes to `$C000-$FFFF` see the
     * fixed-last bank. Commercial games almost always write a value that
     * matches the PRG byte at the write address (so the mask is a no-op), but
     * a few titles can bank differently as a result.
     */
    private fun applyBusConflict(address: Int, value: Byte): Int {
        val (bankIndex, windowBase) = if (address < 0xC000) {
            prgBank to 0x8000
        } else {
            (prgBankCount - 1) to 0xC000
        }
        val romByte = prgByte(bankIndex, windowBase, address).toUnsignedInt()
        return value.toUnsignedInt() and romByte
    }

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        return if (address < 0xC000) {
            prgByte(prgBank, 0x8000, address)
        } else {
            prgByte(prgBankCount - 1, 0xC000, address)
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x8000..0xFFFF) {
            val v = applyBusConflict(address, value)
            prgBank = v
            chrBank = (v shr 3) and 0x03
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF
        return if (chrRom.isEmpty()) {
            chrMemory.read(maskedAddress)
        } else {
            chrRom[(chrBank * 0x2000 + maskedAddress) % chrRom.size]
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            chrMemory.write(address and 0x1FFF, value)
        }
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override fun snapshot(): MapperStateSnapshot = MapperStateSnapshot(
        mapperId = 2,
        type = "UxROM",
        banks = mapOf("prgBank" to prgBank, "chrBank" to chrBank),
        registers = emptyMap(),
        irqState = null,
        chrRam = chrMemory.snapshotBytes()
    )

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank = input.readInt()
        chrBank = input.readInt()
        chrMemory.deserialize(input)
    }
}
