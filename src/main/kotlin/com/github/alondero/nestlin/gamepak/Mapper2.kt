package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 2 (CNROM/UNROM) - CHR and PRG bank switching.
 *
 * CNROM cartridges have:
 * - Fixed PRG ROM at $8000-$FFFF
 * - CHR ROM switched in 8KB banks via $8000-$9FFF writes
 * - Fixed mirroring based on header
 *
 * UNROM (variant used by many commercial games like Castlevania, Contra):
 * - 16KB PRG bank switching at $8000-$BFFF via writes to $8000-$FFFF
 * - $C000-$FFFF fixed to last PRG bank (bank 7 for 128KB, bank 3 for 64KB)
 * - CHR banking: bits 0-2 of written value select bank
 */
class Mapper2(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    // CHR RAM initialized to zeros (same as Mapper 3)
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) {
        ByteArray(0x2000)
    } else null
    private val prgBankCount = programRom.size / 0x4000
    private var chrBank = 0
    // UNROM: $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
    private var prgBank = (prgBankCount - 1).coerceAtLeast(1)

    override fun cpuRead(address: Int): Byte {
        return when {
            address < 0x8000 -> 0
            address < 0xC000 -> {
                val bankOffset = prgBank * 0x4000
                val offset = address - 0x8000
                programRom[bankOffset + offset]
            }
            else -> {
                val bankOffset = (prgBankCount - 1) * 0x4000
                val offset = address - 0xC000
                programRom[bankOffset + offset]
            }
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x8000..0xFFFF) {
            prgBank = value.toUnsignedInt() and 0x07
            chrBank = (value.toUnsignedInt() shr 3) and 0x03
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
}