package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.util.zip.CRC32

class GamePak(data: ByteArray) {

    val header: Header
    val programRom: ByteArray
    val chrRom: ByteArray
    val crc: CRC32

    init {
        header = Header(data.copyOfRange(0, 16))
        programRom = data.copyOfRange(16, 16 + 16384 * header.programRomSize)
        chrRom = data.copyOfRange(16 + programRom.size, 16 + programRom.size + 8192 * header.chrRomSize)
        crc = CRC32().apply { this.update(data)}
    }

    override fun toString(): String {
        return "ROM Size: ${programRom.size}, VROM Size: ${chrRom.size}\nMapper: ${header.mapper}\nCRC32: ${crc.value}"
    }
}

class Header(headerData: ByteArray) {

    val programRomSize: Byte
    val programRamSize: Byte
    val chrRomSize: Byte
    val mapper: Int

    init {
        programRomSize = headerData[4]
        chrRomSize = headerData[5]
        programRamSize = headerData[8]
        mapper = headerData[6].toUnsignedInt() shr(4) or (headerData[7].toUnsignedInt() and 0xF0)
    }
}
