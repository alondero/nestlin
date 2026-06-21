package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [Memory.poke] — the Memory Editor's write path (issue #170). Unlike [Memory.peek]
 * (which is deliberately side-effect-free), poke is a *real* write that the running
 * game must see on its next read. It delegates to [Memory.set] for full side effects
 * — including mapper bank switches — EXCEPT two catastrophic I/O registers which are
 * silently dropped:
 *
 *  - `$4014` (OAM DMA): would halt the CPU for 513 cycles and overwrite all of OAM
 *    from an arbitrary RAM page — a hand-poked value here is never what the user means.
 *  - `$4016` (controller strobe): would reset the controller shift registers and
 *    desync the game's input polling.
 *
 * Everything else — RAM, PPU registers, APU registers, mapper registers — passes
 * through so the user can cheat or experiment freely.
 */
class MemoryPokeTest {

    @Test
    fun `poke writes through to RAM and the game's read path sees it`() {
        val memory = Memory()

        memory.poke(0x0076, 0x09) // e.g. the lives counter from the issue

        // get() is the path the running game uses; it must observe the poked value.
        assertThat(memory[0x0076], equalTo(0x09.toByte()))
        // Internal RAM mirrors every $0800; the poke went to backing storage so the
        // mirror reflects it too.
        assertThat(memory[0x0876], equalTo(0x09.toByte()))
    }

    @Test
    fun `poke of 4014 does not trigger OAM DMA`() {
        // Factory (issue #22): keeps the Cpu construction pattern consistent with the
        // other Memory tests; the IRQ-check path on cpu.tick() now needs apu non-null.
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory)
        memory.cpu = cpu
        // Seed a recognisable page so we'd notice if a DMA copied it into OAM.
        for (i in 0 until 256) memory[0x0100 + i] = i.toSignedByte()

        memory.poke(0x4014, 0x01) // page $01 — would DMA into OAM if honoured

        // A real $4014 write halts the CPU for 513 cycles; the blacklist must skip it.
        assertThat(cpu.workCyclesLeft, equalTo(0))
        // And OAM must be untouched (still all zero), not the page we seeded.
        for (i in 0 until 256) {
            assertThat(
                "OAM[$i] must be untouched by a blacklisted \$4014 poke",
                memory.ppuAddressedMemory.objectAttributeMemory[i].toUnsignedInt(),
                equalTo(0)
            )
        }
    }

    @Test
    fun `poke of 4016 does not strobe the controllers`() {
        val memory = Memory()
        memory.controller1.buttons = 0xFF

        // Latch the 0xFF button state into the shift register: strobe high reloads,
        // strobe low latches.
        memory[0x4016] = 1
        memory[0x4016] = 0

        // Change the live buttons. An errant $4016 write with bit 0 high would set
        // strobe high again and reload the shift register from this NEW value,
        // destroying the latched 0xFF.
        memory.controller1.buttons = 0x00

        memory.poke(0x4016, 1) // blacklisted — must NOT reach Controller.write

        // Because strobe stayed low and the latch survived, the first read returns
        // the latched LSB (1 from 0xFF). If the poke had strobed, read() would see
        // strobe-high and return buttons&A == 0 instead.
        assertThat(memory.controller1.read().toInt() and 1, equalTo(1))
    }

    @Test
    fun `poke of a mapper register triggers the bank switch`() {
        // Factory (issue #22): readCartridge now dispatches mapper audio channels
        // through Apu, so memory.apu must be wired before any readCartridge call.
        val (memory, _) = Memory.createWithApu()
        // Mapper 2 (UNROM): $8000-$BFFF is the switchable 16KB PRG window, selected
        // by the low 3 bits of any $8000-$FFFF write. 128KB = 8 banks; stamp each
        // bank's first byte with its index so the mapped bank is directly readable.
        memory.readCartridge(testGamePak {
            mapper = 2
            prgKb = 128
            chrKb = 8
            stampPrgBanks(windowKb = 16)
        })

        memory.poke(0x8000, 0x03) // select PRG bank 3 into $8000-$BFFF

        // The game's read path (and the editor's peek) now see bank 3's stamp.
        assertThat(memory[0x8000], equalTo(0x03.toByte()))
        assertThat(memory.peek(0x8000), equalTo(0x03.toByte()))
    }
}
