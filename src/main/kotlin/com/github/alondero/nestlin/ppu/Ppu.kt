package com.github.alondero.nestlin.ppu


const val RESOLUTION_WIDTH = 256
const val RESOLUTION_HEIGHT = 224

class Ppu {
    val memory = Memory()

    fun tick() {
        //  TODO: Implement
    }

}

class Memory {
    val vrom = PatternTables()
    val nameTables = NameTables()
    val colourPalettes = ColourPalette()
    val spriteRam = SpriteRam()
}

class SpriteRam {
    val sprites = Array(init = {SpriteInformation()}, size = 64)
}

data class SpriteInformation (
    val posX: Byte = 0,
    val posY: Byte = 0
    //  Other stuff to be stored?
)

class ColourPalette {
    //  Lookup tables for a 256 colour palette (somewhere...)
    val background = ByteArray(16)
    val sprites = ByteArray(16)
}

class NameTables {
    //  Each name table is 960 bytes
    val tables = Array(init = {ByteArray(960)}, size = 4)
    val attributes = Array(init = {ByteArray(64)}, size = 4)
}

class PatternTables {
    //  8kb in size, split in half between background and sprites
    val background = ByteArray(4096)
    val sprites = ByteArray(4096)
}
