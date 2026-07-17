package com.github.alondero.nestlin.ppu

class Frame {
    // Primitive IntArray rows: an Array<Array<Int>> would box every pixel write
    // (~61k Integer allocations per frame at 60 fps). Same [y][x] indexing shape.
    val scanlines = Array(size = RESOLUTION_HEIGHT, init = { IntArray(RESOLUTION_WIDTH) })

    operator fun set(x: Int, y: Int, value: Int) {scanlines[y][x] = value}

    operator fun get(x: Int, y: Int): Int = scanlines[y][x]
}
