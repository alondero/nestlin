package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class CpuTest {

    @Test
    fun startsNesTestRomInAutomationByProgramCounter0xC000() {
        val path = Paths.get("testroms/nestest.nes")

        val cpu = Cpu(Memory()).apply {
            this.currentGame = GamePak(Files.readAllBytes(path))
            this.reset()
        }

        assertThat(cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
    }

}
