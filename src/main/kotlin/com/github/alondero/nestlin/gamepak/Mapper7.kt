package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 7 (AxROM) - Simple 32KB PRG bank switching with single-screen mirroring.
 *
 * AxROM cartridges have:
 * - 32KB PRG ROM bank switchable at $8000-$FFFF (ALL PRG is switchable, no fixed bank)
 * - 8KB CHR RAM (not ROM)
 * - Single-screen mirroring controlled by bit 3 of bank write
 *
 * Games: Battletoads, Arch Rivals, Beetlejuice, Cobra Triangle, etc.
 */
class Mapper7(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    // AxROM uses CHR RAM, not ROM
    private val chrRam = ByteArray(0x2000)
    private val prgBankCount = programRom.size / 0x8000
    private var prgBank = 0
    private var mirroringBit = 0

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        // All 32KB at $8000-$FFFF is switchable (no fixed bank like UNROM)
        val bankOffset = prgBank * 0x8000
        val offset = address - 0x8000
        return programRom[(bankOffset + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // Bank switch via $8000-$FFFF
        // Bits 0-2: PRG bank select (32KB banks)
        // Bit 3: Mirroring control (0 = lower screen, 1 = upper screen)
        if (address in 0x8000..0xFFFF) {
            val valueInt = value.toUnsignedInt()
            prgBank = valueInt and 0x07
            mirroringBit = (valueInt shr 3) and 0x01
        }
    }

    override fun ppuRead(address: Int): Byte {
        return chrRam[address and 0x1FFF]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // CHR RAM is fully writable
        chrRam[address and 0x1FFF] = value
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        // Single-screen mirroring controlled by bit 3 of last bank write
        return if (mirroringBit == 0) {
            Mapper.MirroringMode.ONE_SCREEN_LOWER
        } else {
            Mapper.MirroringMode.ONE_SCREEN_UPPER
        }
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 7,
            type = "AxROM",
            banks = mapOf(
                "prgBank" to prgBank,
                "mirroringBit" to mirroringBit
            ),
            registers = emptyMap(),
            irqState = null,
            chrRam = chrRam.copyOf()
        )
    }
}
