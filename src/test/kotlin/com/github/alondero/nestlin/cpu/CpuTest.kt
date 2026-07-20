package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.testutil.TestRoms
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CpuTest {

    @Test
    fun startsNesTestRomInAutomationByProgramCounter0xC000() {
        // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
        // the IRQ-check path reads it on every tick.
        val cpu = Cpu(Memory.createWithApu().first).apply {
            this.currentGame = GamePak(TestRoms.nestestBytes())
            this.reset()
        }

        assertThat(cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
    }

    @Test
    fun `tick on undocumented opcode does not write to disk but dumpUndocumentedOpcodes does`(
        @TempDir tempDir: Path
    ) {
        // Snapshot the CWD log file's existence so the test is hermetic against
        // a stray undocumented_opcodes.txt from a prior run or dev session.
        val cwdFile = File("undocumented_opcodes.txt")
        val existedBefore = cwdFile.exists()

        // Arrange: Cpu with no currentGame (so the test-ROM guard is off and the
        // logUndocumentedOpcode branch fires), PC=0, opcode 0xCB at address 0.
        // 0xCB is one of only 6 bytes absent from the Opcodes map (250 of 256
        // mapped), so it triggers the `?: run { logUndocumentedOpcode(...) }`
        // branch in tick(). Set the byte AFTER reset() because reset() calls
        // memory.clear() which would wipe it.
        val cpu = Cpu(Memory.createWithApu().first).apply {
            reset()
            this.memory[0] = 0xCB.toSignedByte()
        }
        val dumpFile = tempDir.resolve("undocumented_opcodes.txt").toFile()

        // Sanity: the test-ROM guard is the off-ramp for the logging path.
        assertThat(cpu.currentGame?.isTestRom() ?: false, equalTo(false))

        // Act 1: tick the CPU through the undocumented opcode. Must NOT touch disk.
        repeat(10) { cpu.tick() }
        assertThat(
            "tick() must not create a file in the working directory",
            cwdFile.exists() && !existedBefore,
            equalTo(false)
        )
        assertThat("dump target must not exist before dump()", dumpFile.exists(), equalTo(false))

        // Act 2: dump should write the expected content to the explicit path.
        cpu.dumpUndocumentedOpcodes(dumpFile)

        // Assert: file exists, has both the per-opcode line and the summary line.
        assertThat(dumpFile.exists(), equalTo(true))
        val content = dumpFile.readText()
        assertThat(content, containsSubstring("Undocumented opcode: 0xCB"))
        assertThat(content, containsSubstring("Found 1 unique undocumented opcodes"))
    }

}
