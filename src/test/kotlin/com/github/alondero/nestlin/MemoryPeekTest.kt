package com.github.alondero.nestlin

import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [Memory.peek] — the unified side-effect-free read across the whole CPU bus for
 * the Memory Editor (issue #168). Verifies it dispatches to the correct backing
 * store for each region, fires no read side effects, and — critically — never
 * touches [Memory.dataBus] (which a real [Memory.get] always updates).
 */
class MemoryPeekTest {

    @Test
    fun `peek returns backing RAM value including mirrors`() {
        val memory = Memory()
        memory[0x0005] = 0x42

        assertThat(memory.peek(0x0005), equalTo(0x42.toByte()))
        // Internal RAM mirrors every $0800 through $1FFF.
        assertThat(memory.peek(0x0805), equalTo(0x42.toByte()))
        assertThat(memory.peek(0x1805), equalTo(0x42.toByte()))
    }

    @Test
    fun `peek of PPU status does not clear vblank`() {
        val memory = Memory()
        memory.ppuAddressedMemory.setVBlank()

        val peeked = memory.peek(0x2002)

        assertThat(peeked.toInt() and 0x80, equalTo(0x80))
        // A real read of $2002 clears the flag; peek must not.
        assertThat(memory.ppuAddressedMemory.status.vBlankStarted(), equalTo(true))
        // Mirrors of $2000-$2007 repeat every 8 bytes through $3FFF.
        assertThat(memory.peek(0x3FFA).toInt() and 0x80, equalTo(0x80))
    }

    @Test
    fun `peek does not update the data bus`() {
        // Use the factory (issue #22): $4015 peek dispatches to Apu, and
        // Memory.apu is non-nullable after the factory wires the pair.
        val (memory, _) = Memory.createWithApu()
        memory[0x0005] = 0x42
        memory.dataBus = 0x7E // sentinel

        // Every region's peek must leave dataBus untouched (a real get sets it).
        memory.peek(0x0005)  // RAM
        memory.peek(0x2002)  // PPU
        memory.peek(0x4016)  // controller
        memory.peek(0x4015)  // APU

        assertThat(memory.dataBus, equalTo(0x7E.toByte()))
    }

    @Test
    fun `peek of cartridge space matches the mapper read`() {
        // Factory (issue #22): readCartridge now dispatches mapper audio channels
        // through Apu, so memory.apu must be wired before any readCartridge call.
        val (memory, _) = Memory.createWithApu()
        memory.readCartridge(testGamePak { mapper = 0; prgKb = 16; chrKb = 8 })
        val mapper = memory.mapper!!

        // cpuPeek defaults to cpuRead, so peek must agree with a direct mapper read
        // across the $4020-$FFFF cartridge window (sampled).
        for (addr in listOf(0x8000, 0xA000, 0xC000, 0xFFFC, 0xFFFF)) {
            assertThat(memory.peek(addr), equalTo(mapper.cpuRead(addr)))
        }
    }

    @Test
    fun `peek of cartridge space returns 0 when no ROM is loaded`() {
        val memory = Memory()
        assertThat(memory.peek(0x8000), equalTo(0.toByte()))
    }
}
