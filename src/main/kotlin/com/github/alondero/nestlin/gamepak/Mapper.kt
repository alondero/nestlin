package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

interface Mapper {
    fun cpuRead(address: Int): Byte
    fun cpuWrite(address: Int, value: Byte)
    fun ppuRead(address: Int): Byte
    fun ppuWrite(address: Int, value: Byte)
    fun currentMirroring(): MirroringMode

    enum class MirroringMode {
        HORIZONTAL,
        VERTICAL,
        ONE_SCREEN_LOWER,
        ONE_SCREEN_UPPER
    }
}
