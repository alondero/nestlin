package com.github.alondero.nestlin.gamepak

/**
 * Mapper 0 (NROM) - No bank switching.
 *
 * NROM cartridges have fixed PRG and CHR ROM:
 * - 16KB PRG ROM: mirrored at $8000 and $C000
 * - 32KB PRG ROM: loaded at $8000-$FFFF
 * - CHR ROM: up to 8KB for pattern tables
 */
class Mapper0(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val prgBankCount = programRom.size / 0x4000

    override fun cpuRead(address: Int): Byte {
        return when {
            // 16KB PRG ROM mode: both banks point to the same 16KB region
            prgBankCount == 1 -> programRom[(address - 0x8000) and 0x3FFF]
            // 32KB PRG ROM mode
            else -> programRom[(address - 0x8000) and 0x7FFF]
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // NROM is ROM - writes are ignored
    }

    override fun ppuRead(address: Int): Byte {
        if (chrRom.isEmpty()) return 0
        return chrRom[address and 0x1FFF]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // NROM has CHR ROM - writes are ignored (or CHG RAM if no CHR ROM)
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }
}
