package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.RomLoader
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path

class Nestlin {

    private var cpu: Cpu
    private var ppu: Ppu
    private val apu: Apu = Apu()

    init {
        val memory = Memory()
        cpu = Cpu(memory)
        ppu = Ppu(memory)
    }

    fun load(rom: Path) {
        GamePak(validate(RomLoader().load(rom)))?.apply {
            println("GamePak information:\n${this.toString()}\n")
            cpu.currentGame = this
        }
    }

    private fun validate(data: ByteArray): ByteArray {
        if (String(data.copyOfRange(0, 4)).equals("NES\n")) {
            throw BadHeaderException("Missing NES Token")
        }

        return data
    }

    fun addFrameListener(listener: FrameListener) {
        ppu.addFrameListener(listener)
    }

    fun powerReset() {
        cpu.reset()
    }

    fun start() {
        while (true) {
            for (i in 1..3) {
                ppu.tick()
            }
            apu.tick()
            cpu.tick()
        }
    }
}

class BadHeaderException(message: String) : RuntimeException(message)