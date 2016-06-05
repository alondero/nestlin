package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Path

class Nestlin {

    private val cpu: Cpu = Cpu()
    private val ppu: Ppu = Ppu()
    private val apu: Apu = Apu()

    fun load(rom: Path) {
        GamePak(validate(SevenZFile(rom.toFile()).use {
            ByteArray(it.nextEntry.size.toInt()).apply {it.read(this)}
        }))?.apply {
            println(this.toString())
            cpu.currentGame = this
        }
    }

    private fun validate(data: ByteArray): ByteArray {
        val header = data.copyOfRange(0, 16)
        val nesToken = String(header.copyOfRange(0, 4))

        if (nesToken.equals("NES\n")) {
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

class BadHeaderException(message: String) : RuntimeException(message) {

}