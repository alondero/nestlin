package com.github.alondero.nestlin

import com.github.alondero.nestlin.file.RomLoader
import com.github.alondero.nestlin.gamepak.GamePak
import java.nio.file.Path

class Nestlin {

    private val cpu: Cpu = Cpu()
    private val ppu: Ppu = Ppu()
    private val apu: Apu = Apu()

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

    fun powerReset() {
        cpu.reset()
    }

    fun start() {
        while (true) {
            cpu.tick()
        }
    }
}

class BadHeaderException(message: String) : RuntimeException(message)