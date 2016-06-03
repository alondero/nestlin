package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Path

class Nestlin {

    private var loadedGame: GamePak? = null
    private val cpu: Cpu = Cpu()

    fun load(rom: Path) {
        loadedGame = GamePak(validate(SevenZFile(rom.toFile()).use {
            ByteArray(it.nextEntry.size.toInt()).apply {it.read(this)}
        }))
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
}

class BadHeaderException(message: String) : RuntimeException(message) {

}