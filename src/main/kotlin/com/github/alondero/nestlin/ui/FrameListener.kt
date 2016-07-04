package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.ppu.Frame

interface FrameListener {

    fun frameUpdated(frame: Frame)
}