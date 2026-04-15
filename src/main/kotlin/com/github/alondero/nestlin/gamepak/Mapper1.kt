package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 1 (MMC1) - Nintendo's first bank-switching mapper.
 *
 * Uses a 5-bit shift register for bank configuration.
 * Supports PRG ROM bank switching (16KB or 32KB modes) and CHR bank switching.
 */
class Mapper1(private val gamePak: GamePak) : Mapper {

    private var shiftReg = 0x10   // marker bit at bit 4
    // Control register initialized from header mirroring at power-on:
    // bits 0-1 = mirroring, bit 2-3 = PRG mode (3 = fixed at 0xC000), bit 4 = CHR mode (0 = 8KB)
    private var controlReg = (when (gamePak.header.mirroring) {
        Header.Mirroring.HORIZONTAL -> 0x0D   // 0b01101 = horizontal + PRG mode 3 + CHR mode 0
        Header.Mirroring.VERTICAL -> 0x0E     // 0b01110 = vertical + PRG mode 3 + CHR mode 0
    }).toByte()
    private var chrBank0 = 0
    private var chrBank1 = 0
    private var prgBank = 0

    // CHR RAM is used when CHR ROM is empty
    private val chrRam: ByteArray? = if (gamePak.chrRom.isEmpty()) ByteArray(0x2000) else null

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val prgBankCount = programRom.size / 0x4000

    override fun cpuRead(address: Int): Byte {
        val prgMode = controlReg.toUnsignedInt() shr 2 and 0x03
        val bank = when (prgMode) {
            0, 1 -> {
                // 32KB mode: both 16KB banks use the same bank number (lower's bit 0 ignored)
                val bankBase = (prgBank and 0xFE)
                val offset = address - 0x8000
                if (offset < 0x4000) {
                    programRom[(bankBase * 0x4000 + offset) % programRom.size]
                } else {
                    programRom[((bankBase + 1) * 0x4000 + (offset - 0x4000)) % programRom.size]
                }
            }
            2 -> {
                // Mode 2: $8000-$BFFF fixed to bank 0, $C000-$FFFF switchable
                if (address < 0xC000) {
                    programRom[(address - 0x8000) % minOf(0x4000, programRom.size)]
                } else {
                    programRom[(prgBank * 0x4000 + (address - 0xC000)) % programRom.size]
                }
            }
            else /* 3 */ -> {
                // Mode 3 (default): $8000-$BFFF switchable, $C000-$FFFF fixed to last bank
                if (address < 0xC000) {
                    programRom[(prgBank * 0x4000 + (address - 0x8000)) % programRom.size]
                } else {
                    programRom[((prgBankCount - 1) * 0x4000 + (address - 0xC000)) % programRom.size]
                }
            }
        }
        return bank
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // MMC1 uses a 5-bit shift register protocol
        // Bit 7 of the value controls whether this is a "reset" write
        if (value.toUnsignedInt() and 0x80 != 0) {
            // Bit 7 set: reset shift register and force PRG mode 3
            shiftReg = 0x10
            controlReg = (controlReg.toUnsignedInt() or 0x0C).toByte()
            return
        }

        // Check if we're completing a 5-write sequence
        val completing = (shiftReg and 0x01) != 0

        // Shift right and insert new bit at bit 4
        shiftReg = (shiftReg shr 1) or ((value.toUnsignedInt() and 0x01) shl 4)

        if (completing) {
            // Write completed - update the appropriate register
            when ((address shr 13) and 0x03) {
                0 -> controlReg = (shiftReg and 0x1F).toByte()       // $8000-$9FFF: Control register
                1 -> chrBank0 = shiftReg and 0x1F        // $A000-$BFFF: CHR bank 0
                2 -> chrBank1 = shiftReg and 0x1F        // $C000-$DFFF: CHR bank 1
                3 -> prgBank = shiftReg and 0x1F         // $E000-$FFFF: PRG bank
            }
            shiftReg = 0x10  // Reset to marker value
        }
    }

    override fun ppuRead(address: Int): Byte {
        if (chrRam != null) {
            return chrRam[address and 0x1FFF]
        }
        // CHR banking mode
        val chrMode = if (controlReg.isBitSet(4)) 1 else 0
        return if (chrMode == 0) {
            // 8KB CHR mode: both 4KB banks point to the same 8KB region
            val bankBase = (chrBank0 and 0x1E)
            val offset = address and 0x1FFF
            if (offset < 0x1000) {
                chrRom[(bankBase * 0x1000 + offset) % chrRom.size]
            } else {
                chrRom[(((bankBase + 1) * 0x1000) + (offset - 0x1000)) % chrRom.size]
            }
        } else {
            // 4KB CHR mode: each bank is independent
            if (address < 0x1000) {
                chrRom[(chrBank0 * 0x1000 + address) % chrRom.size]
            } else {
                chrRom[(chrBank1 * 0x1000 + (address - 0x1000)) % chrRom.size]
            }
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRam != null) {
            chrRam[address and 0x1FFF] = value
        }
        // CHR ROM is read-only
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (controlReg.toUnsignedInt() and 0x03) {
            0 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
            1 -> Mapper.MirroringMode.ONE_SCREEN_UPPER
            2 -> Mapper.MirroringMode.VERTICAL
            else -> Mapper.MirroringMode.HORIZONTAL
        }
    }
}
