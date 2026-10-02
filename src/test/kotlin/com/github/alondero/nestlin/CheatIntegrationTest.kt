package com.github.alondero.nestlin

import com.github.alondero.nestlin.cheat.Cheat
import com.github.alondero.nestlin.cheat.CheatCode
import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.session.GameSessionCoordinator
import com.github.alondero.nestlin.session.NoOpRetroAchievementsService
import com.github.alondero.nestlin.testutil.TestRoms
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.testutil.testRom
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CheatIntegrationTest {
    private fun cheat(text: String, enabled: Boolean = true) = Cheat(CheatCode.parse(text), enabled)

    @Test
    fun `RAM substitution covers mirrors without changing storage or writes`() {
        val memory = Memory()
        memory[0x075A] = 4
        memory.cheatEngine.replace(listOf(cheat("0F5A:FF")))
        for (address in listOf(0x075A, 0x0F5A, 0x175A, 0x1F5A)) {
            assertThat(memory[address], equalTo(0xFF.toByte()))
            assertThat(memory.peek(address), equalTo(4.toByte()))
        }
        memory[0x175A] = 2
        assertThat(memory[0x075A], equalTo(0xFF.toByte()))
        memory.cheatEngine.replace(emptyList())
        assertThat(memory[0x075A], equalTo(2.toByte()))
    }

    @Test
    fun `conditional ROM cheat follows banks without patching cartridge data`() {
        val (memory, _) = Memory.createWithApu()
        val game = testGamePak {
            mapper = 2
            prgKb = 64
            fillPrg(0xFF.toByte()) // Leave bus-conflict AND masking transparent at bank-select writes.
            prg[0x14A7] = 3
            prg[0x4000 + 0x14A7] = 4
        }
        memory.readCartridge(game)
        memory[0x8000] = 0 // UxROM starts in the last bank; select the fixture's comparison bank.
        memory.cheatEngine.replace(listOf(cheat("ZEXPYGLA")))
        assertThat(memory[0x94A7], equalTo(2.toByte()))
        assertThat(memory.peek(0x94A7), equalTo(3.toByte()))
        memory[0x8000] = 1
        assertThat(memory[0x94A7], equalTo(4.toByte()))
        memory[0x8000] = 0
        assertThat(memory[0x94A7], equalTo(2.toByte()))
        memory.cheatEngine.replace(emptyList())
        assertThat(memory[0x94A7], equalTo(3.toByte()))
    }

    @Test
    fun `first matching enabled code wins and comparisons use original byte`() {
        val memory = Memory()
        memory[0x10] = 0x80.toByte()
        memory.cheatEngine.replace(listOf(cheat("0010:FF", false), cheat("0010:01:02"),
            cheat("0010:02:80"), cheat("0010:03:02")))
        assertThat(memory[0x10], equalTo(2.toByte()))
        memory.cheatEngine.replace(listOf(cheat("0010:03:02")))
        assertThat(memory[0x10], equalTo(0x80.toByte()))
    }

    @Test
    fun `CPU executes a substituted operand and observer sees the delivered byte`() {
        val emu = Nestlin()
        emu.loadBytes(testRom {
            prg[0] = 0xA9.toByte() // LDA #$03
            prg[1] = 3
            prg[2] = 0x85.toByte() // STA $10
            prg[3] = 0x10
            prg[4] = 0x4C // JMP $8004
            prg[5] = 4
            prg[6] = 0x80.toByte()
            resetVector(0x8000)
        })
        emu.powerReset()
        emu.setCheats(listOf(cheat("8001:09:03")))
        val reads = mutableListOf<Memory.CpuBusAccess>()
        emu.memory.cpuBusObserver = { if (it.operation == Memory.CpuBusOperation.READ && it.address == 0x8001) reads.add(it) }
        repeat(50) { emu.stepCpuCycle() }
        assertThat(emu.peekMemory(0x10), equalTo(9.toByte()))
        assertThat(emu.peekMemory(0x8001), equalTo(3.toByte()))
        assertThat(reads.map { it.value }, equalTo(listOf(9.toByte())))
    }

    @Test
    fun `OAM DMA reads substitutions and peek preserves the bus latch`() {
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory)
        memory[0x0100] = 3
        memory.cheatEngine.replace(listOf(cheat("0100:09")))
        memory[0x4014] = 1
        repeat(513) { cpu.tick() }
        assertThat(memory.ppuAddressedMemory.objectAttributeMemory[0], equalTo(9.toByte()))
        memory.dataBus = 0x7E
        assertThat(memory.peek(0x0100), equalTo(3.toByte()))
        assertThat(memory.dataBus, equalTo(0x7E.toByte()))
        assertThat(memory[0x0100], equalTo(9.toByte()))
        assertThat(memory.dataBus, equalTo(9.toByte()))
    }

    @Test
    fun `save states preserve original RAM and keep the current cheat configuration`() {
        val emu = Nestlin()
        emu.loadBytes(testRom { resetVector(0x8000) })
        emu.powerReset()
        emu.memory[0x10] = 4
        emu.setCheats(listOf(cheat("0010:09")))
        val saved = ByteArrayOutputStream().also { emu.saveState(it) }.toByteArray()
        emu.setCheats(emptyList())
        emu.memory[0x10] = 2
        emu.loadState(ByteArrayInputStream(saved))
        assertThat(emu.cheats.size, equalTo(0))
        assertThat(emu.peekMemory(0x10), equalTo(4.toByte()))
        emu.setCheats(listOf(cheat("0010:07")))
        emu.loadState(ByteArrayInputStream(saved))
        assertThat(emu.memory[0x10], equalTo(7.toByte()))
        assertThat(emu.peekMemory(0x10), equalTo(4.toByte()))
    }

    @Test
    fun `changed configuration clears rewind and loading or unloading clears cheats`() {
        val emu = Nestlin()
        assertThrowsWithMessage<IllegalStateException>("Load a game") { emu.setCheats(listOf(cheat("0010:09"))) }
        val rom = testRom { resetVector(0x8000) }
        emu.loadBytes(rom)
        emu.powerReset()
        emu.rewindBuffer.capture(byteArrayOf(1))
        val draft = mutableListOf(cheat("0010:09"))
        emu.setCheats(draft)
        draft.clear()
        assertThat(emu.cheats.size, equalTo(1))
        assertThat(emu.rewindBufferSize(), equalTo(0))
        emu.rewindBuffer.capture(byteArrayOf(2))
        emu.setCheats(emu.cheats)
        assertThat(emu.rewindBufferSize(), equalTo(1))
        emu.powerReset()
        assertThat(emu.cheats.size, equalTo(1))
        emu.softReset()
        assertThat(emu.cheats.size, equalTo(1))
        emu.loadBytes(rom, "another game")
        assertThat(emu.cheats.size, equalTo(0))
        emu.setCheats(listOf(cheat("0010:09")))
        emu.unload()
        assertThat(emu.cheats.size, equalTo(0))
    }

    @Test
    fun `coordinator hard reset preserves cheats for a file-backed game`() {
        val emu = Nestlin()
        val session = GameSessionCoordinator(emu, NoOpRetroAchievementsService)
        session.loadRom(TestRoms.nestestPath())
        val cheats = listOf(cheat("0010:09"))
        emu.setCheats(cheats)
        session.powerReset()
        assertThat(emu.cheats, equalTo(cheats))
        session.loadRom(TestRoms.nestestPath())
        assertThat(emu.cheats.size, equalTo(0))
    }
}
