package com.github.alondero.nestlin.gamepak

class GamePak(data: ByteArray) {

    val header: Header
    val programRom: ByteArray
    val chrRom: ByteArray

    init {
        header = Header(data.copyOfRange(0, 16))
        programRom = data.copyOfRange(16, 16 + 16384 * header.programRomSize)
        chrRom = data.copyOfRange(16 + programRom.size, 16 + programRom.size + 8192 * header.chrRomSize)
    }

    override fun toString(): String {
        return """GamePak information:
ROM Size: ${programRom.size}, VROM Size: ${chrRom.size}
Mapper: ${header.mapper}"""
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
        mapper = headerData[6].toInt() shr(4) or (headerData[7].toInt() and 0xF0)
    }
}
