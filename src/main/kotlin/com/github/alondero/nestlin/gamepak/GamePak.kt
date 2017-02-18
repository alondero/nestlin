package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.util.zip.CRC32

private const val TEST_ROM_CRC = 0x9e179d92

class GamePak(data: ByteArray) {

    val header = Header(data.copyOfRange(0, 16))
    val programRom: ByteArray
    val chrRom: ByteArray
    val crc: CRC32

    init {
        programRom = data.copyOfRange(16, 16 + 16384 * header.programRomSize)
        chrRom = data.copyOfRange(16 + programRom.size, 16 + programRom.size + 8192 * header.chrRomSize)
        crc = CRC32().apply { update(data)}
    }

    fun isTestRom() = crc.value == TEST_ROM_CRC

    override fun toString(): String {
        return "ROM Size: ${programRom.size}, VROM Size: ${chrRom.size}\nMapper: ${header.mapper}\nCRC32: ${crc.value}"
    }
}

class Header(headerData: ByteArray) {

    val programRomSize = headerData[4]
    val programRamSize = headerData[8]
    val chrRomSize = headerData[5]
    val mapper: Int = headerData[6].toUnsignedInt() shr(4) or (headerData[7].toUnsignedInt() and 0xF0)

}
