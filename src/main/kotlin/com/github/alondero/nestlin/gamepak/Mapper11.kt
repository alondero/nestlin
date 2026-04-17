package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 11 (Color Dreams) - CHR bank switching only.
 *
 * Color Dreams cartridges have:
 * - Fixed PRG ROM at $8000-$FFFF (32KB)
 * - CHR ROM switched in 8KB banks via $8000-$FFFF writes
 * - Fixed mirroring based on header
 *
 * Games: Action 52, Bible Adventures, etc.
 */
class Mapper11(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    // CHR RAM initialized to zeros when no CHR ROM
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null
    private var chrBank = 0
    private var prgBank = 0  // 32KB PRG bank units

    override fun cpuRead(address: Int): Byte {
        // PRG bank switching via $8000-$FFFF
        return programRom[(prgBank * 0x8000 + (address - 0x8000)) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // Bank switch via $8000-$FFFF
        // Format: CCCC LLPP (bits 4-7 = 8KB CHR, bits 0-1 = 32KB PRG bank)
        if (address in 0x8000..0xFFFF) {
            val valueInt = value.toUnsignedInt()
            chrBank = (valueInt shr 4) and 0x0F  // Bits 4-7: 8KB CHR bank (up to 128KB)
            prgBank = valueInt and 0x03          // Bits 0-1: 32KB PRG bank
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
            mapperId = 11,
            type = "Color Dreams",
            banks = mapOf("chrBank" to chrBank),
            registers = emptyMap(),
            irqState = null,
            chrRam = chrRam?.copyOf()
        )
    }
}
