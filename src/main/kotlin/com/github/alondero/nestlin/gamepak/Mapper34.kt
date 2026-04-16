package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 34 (BNROM/NINA-001) - PRG and CHR bank switching.
 *
 * BNROM/NINA-001 cartridges have:
 * - ALL PRG ROM switchable at $8000-$FFFF (no fixed bank unlike UNROM)
 * - CHR ROM switched in 8KB banks via $8000-$FFFF writes
 * - Fixed mirroring based on header
 *
 * Games: Deadly Towers, etc.
 */
class Mapper34(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null
    private val prgBankCount = programRom.size / 0x8000
    private var prgBank = 0
    private var chrBank = 0

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        // ALL PRG is switchable (entire 32KB window at $8000-$FFFF)
        val bankOffset = prgBank * 0x8000
        val offset = address - 0x8000
        return programRom[(bankOffset + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // Bank switch via $8000-$FFFF
        // Low bits select PRG bank and CHR bank
        if (address in 0x8000..0xFFFF) {
            val valueInt = value.toUnsignedInt()
            prgBank = valueInt and 0x07
            chrBank = (valueInt shr 3) and 0x03
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF
        return if (chrRom.isEmpty()) {
            chrRam!![maskedAddress]
        } else {
            // 8KB CHR banks
            chrRom[(chrBank * 0x2000 + maskedAddress) % chrRom.size]
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

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 34,
            type = "BNROM/NINA-001",
            banks = mapOf(
                "prgBank" to prgBank,
                "chrBank" to chrBank
            ),
            registers = emptyMap(),
            irqState = null,
            chrRam = chrRam?.copyOf()
        )
    }
}
