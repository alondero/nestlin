package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 3 (NINA-003/006) - CHR bank switching used by games like Paperboy.
 *
 * NINA-003/006 cartridges have:
 * - Fixed PRG ROM at $8000-$FFFF (typically 32KB)
 * - CHR ROM switched in 8KB banks via $8000-$9FFF writes
 * - Fixed mirroring based on header
 */
class Mapper3(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null
    private var chrBank = 0

    override fun cpuRead(address: Int): Byte {
        // PRG fixed at $8000-$FFFF (32KB)
        return programRom[(address - 0x8000) and 0x7FFF]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // CHR bank switch via $8000-$9FFF
        // NINA-003 uses bits 0-1 for CHR bank selection
        if (address in 0x8000..0x9FFF) {
            chrBank = value.toUnsignedInt() and 0x03
        }
    }

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
}
