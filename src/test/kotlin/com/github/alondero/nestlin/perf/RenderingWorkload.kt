package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region

/** A repeatable full-core workload with background, sparse sprites and four audio channels. */
internal object RenderingWorkload {
    fun create(region: Region, render: Boolean = true, rewind: Boolean = false): Nestlin {
        val rom = requireNotNull(javaClass.getResourceAsStream("/nestest.nes")).use { it.readBytes() }
        // Keep the fixture's header and CHR; replace its entry point with a stable JMP loop.
        rom[16] = 0x4C
        rom[17] = 0
        rom[18] = 0x80.toByte()
        val prgSize = (rom[4].toInt() and 0xFF) * 16384
        for (vector in prgSize - 6 until prgSize step 2) {
            rom[16 + vector] = 0
            rom[17 + vector] = 0x80.toByte()
        }
        val emu = Nestlin()
        emu.config.rewindEnabled = rewind
        emu.config.regionOverride = region
        emu.loadBytes(rom, "perf-fixture")
        emu.powerReset()
        repeat(7) { emu.stepCpuCycle() }
        val memory = emu.cpu.memory
        val regs = memory.ppuAddressedMemory
        val ppuMemory = regs.ppuInternalMemory
        for (i in 0 until 4096) ppuMemory[0x2000 + i] = (i * 13).toByte()
        for (i in 0 until 32) ppuMemory[0x3F00 + i] = (i * 7).toByte()
        for (i in 0 until 64) {
            val oam = regs.objectAttributeMemory
            oam[i * 4] = (i / 8 * 29).toByte()
            oam[i * 4 + 1] = (i * 3).toByte()
            oam[i * 4 + 2] = (i * 37).toByte()
            oam[i * 4 + 3] = (i % 8 * 30).toByte()
        }
        memory[0x2000] = 0x10
        memory[0x2001] = if (render) 0x1E else 0
        memory[0x4015] = 0x0F
        memory[0x4000] = 0xBF.toByte()
        memory[0x4002] = 0x80.toByte()
        memory[0x4003] = 0x18
        memory[0x4004] = 0x7A
        memory[0x4006] = 0x61
        memory[0x4007] = 0x18
        memory[0x4008] = 0xFF.toByte()
        memory[0x400A] = 0x38
        memory[0x400B] = 0x18
        memory[0x400C] = 0x35
        memory[0x400E] = 5
        memory[0x400F] = 0x18
        return emu
    }

    fun runFrame(emu: Nestlin) {
        var remaining = 40_000
        do {
            check(remaining-- > 0) { "PPU did not complete a frame" }
            emu.stepCpuCycle()
        } while (!emu.ppu.frameJustCompleted())
    }
}
