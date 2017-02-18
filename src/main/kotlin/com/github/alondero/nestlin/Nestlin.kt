package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path

class Nestlin {

    private var cpu: Cpu
    private var ppu: Ppu
    private val apu: Apu
    private var running = false

    init {
        val memory = Memory()
        cpu = Cpu(memory)
        ppu = Ppu(memory)
        apu = Apu()
    }

    fun load(romPath: Path) {
        cpu.currentGame = romPath.load()?.let(::GamePak)
    }

    fun addFrameListener(listener: FrameListener) {
        ppu.addFrameListener(listener)
    }

    fun enableLogging() {
        cpu.enableLogging()
    }

    fun powerReset() {
        cpu.reset()
    }

    fun start() {
        running = true

        while (running) {
            (1..3).forEach { ppu.tick() }
            apu.tick()
            cpu.tick()
        }
    }

    fun stop() {running = false}
}

class BadHeaderException(message: String) : RuntimeException(message)