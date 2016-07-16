package com.github.alondero.nestlin.ppu

class Frame {
    val scanlines = Array(size = RESOLUTION_HEIGHT, init = {Array(size = RESOLUTION_WIDTH, init = {0x000000})})

    operator fun set(x: Int, y: Int, value: Int) {scanlines[x][y] = value}
}