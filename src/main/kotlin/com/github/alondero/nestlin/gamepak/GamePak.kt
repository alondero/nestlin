package com.github.alondero.nestlin.gamepak

class GamePak(data: ByteArray) {

    val header: Header
    val prgRom: ByteArray
    val chrRom: ByteArray

    init {
        header = Header(data.copyOfRange(0, 16))
        prgRom = data.copyOfRange(16, 16 + 16384 * header.prgRomSize)
        chrRom = data.copyOfRange(16 + prgRom.size, 16 + prgRom.size + 8192 * header.chrRomSize)
    }
}

class Header(headerData: ByteArray) {

    val prgRomSize: Byte
    val prgRamSize: Byte
    val chrRomSize: Byte

    init {
        prgRomSize = headerData[4]
        chrRomSize = headerData[5]
        prgRamSize = headerData[8]
    }
}