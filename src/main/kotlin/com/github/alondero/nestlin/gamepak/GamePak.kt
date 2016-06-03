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
}

class Header(headerData: ByteArray) {

    val programRomSize: Byte
    val programRamSize: Byte
    val chrRomSize: Byte

    init {
        programRomSize = headerData[4]
        chrRomSize = headerData[5]
        programRamSize = headerData[8]
    }
}