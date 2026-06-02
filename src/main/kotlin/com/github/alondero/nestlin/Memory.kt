package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.gamepak.Mapper
import com.github.alondero.nestlin.ppu.PpuAddressedMemory
import com.github.alondero.nestlin.apu.ApuAddressedMemory
import com.github.alondero.nestlin.apu.DmaPort
import java.io.DataInput
import java.io.DataOutput

class Memory : DmaPort {
    private val internalRam = ByteArray(0x800)
    val ppuAddressedMemory = PpuAddressedMemory()
    val apuAddressedMemory = ApuAddressedMemory()

    val controller1 = Controller()
    val controller2 = Controller()

    // Will be set by Nestlin after APU creation
    var apu: Apu? = null

    // Will be set by Nestlin after CPU creation. Memory needs this back-reference
    // so it can halt the CPU for the 513 cycles an OAM DMA takes (NESdev: the CPU
    // is suspended for the duration of the transfer). Without it, every DMA would
    // let the CPU "skip ahead" by 513 cycles — a per-frame drift that desyncs games
    // from Mesen2 in as few as ~5 frames of OAM-DMA-heavy sprite work.
    var cpu: com.github.alondero.nestlin.cpu.Cpu? = null

    // Mapper for cartridge bank switching (set during readCartridge)
    var mapper: Mapper? = null

    fun readCartridge(data: GamePak) {
        val m = data.createMapper()
        mapper = m

        // Wire CHR banking delegates to the mapper
        ppuAddressedMemory.ppuInternalMemory.chrReadDelegate = { addr -> m.ppuRead(addr) }
        ppuAddressedMemory.ppuInternalMemory.chrWriteDelegate = { addr, v -> m.ppuWrite(addr, v) }

        // Wire A12 edge detection for MMC3 scanline IRQ
        ppuAddressedMemory.ppuInternalMemory.a12EdgeListener = { rising -> m.notifyA12Edge(rising) }
        ppuAddressedMemory.ppuInternalMemory.resetA12State()

        // Load CHR ROM into PPU pattern tables for initial tiles
        ppuAddressedMemory.ppuInternalMemory.loadChrRom(data.chrRom)

        // Apply initial mirroring from mapper
        applyMirroringFromMapper(m)
    }

    /** Re-applies the current mapper's mirroring to the PPU. Called after save state restore. */
    fun syncMirroringFromMapper() {
        mapper?.let { applyMirroringFromMapper(it) }
    }

    fun saveRamState(out: DataOutput) {
        out.write(internalRam)
    }

    fun loadRamState(input: DataInput) {
        input.readFully(internalRam)
    }

    private fun applyMirroringFromMapper(m: Mapper) {
        ppuAddressedMemory.ppuInternalMemory.mirroring = when (m.currentMirroring()) {
            Mapper.MirroringMode.HORIZONTAL -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.HORIZONTAL
            Mapper.MirroringMode.VERTICAL -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.VERTICAL
            Mapper.MirroringMode.ONE_SCREEN_LOWER -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.ONE_SCREEN_LOWER
            Mapper.MirroringMode.ONE_SCREEN_UPPER -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.ONE_SCREEN_UPPER
        }
    }

    operator fun set(address: Int, value: Byte) {
        when (address) {
            in 0x0000..0x1FFF -> internalRam[address%0x800] = value
            in 0x2000..0x3FFF -> ppuAddressedMemory[address%8] = value
            0x4016 -> {
                controller1.write(value)
                controller2.write(value)
            }
            0x4014 -> {
                val base = value.toUnsignedInt() shl 8
                for (i in 0 until 256) {
                    val data = this[base + i]
                    ppuAddressedMemory.writeOamData(data)
                }
                // OAM DMA halts the CPU for 513 cycles (NESdev: each of the 256
                // byte transfers is 2 PPU cycles, +1 for the align-on-write setup
                // cycle). Without this halt, every DMA "skips" 513 CPU cycles —
                // enough to desync the game from a cycle-accurate reference like
                // Mesen2 within a handful of frames of OAM-heavy sprite updates.
                // Surface diagnosed against Micro Machines (mapper 71) on
                // 2026-06-02: the 2-frame CPU-PC drift at frame 270 was the
                // downstream symptom.
                cpu?.workCyclesLeft = 513
            }
            in 0x4000..0x401F -> {
                apuAddressedMemory[address - 0x4000] = value
                apu?.handleRegisterWrite(address - 0x4000, value)
            }
            in 0x4020..0xFFFF -> {
                mapper?.cpuWrite(address, value)
                mapper?.let { applyMirroringFromMapper(it) }  // sync mirroring after each write
            }
        }
    }

    override operator fun get(address: Int) = when (address) {
        in 0x0000..0x1FFF -> internalRam[address % 0x800]
        in 0x2000..0x3FFF -> ppuAddressedMemory[address % 8]
        0x4016 -> controller1.read()
        0x4017 -> controller2.read()
        in 0x4000..0x401F -> apu?.handleRegisterRead(address - 0x4000) ?: apuAddressedMemory[address - 0x4000]
        in 0x4020..0xFFFF -> mapper?.cpuRead(address) ?: 0
        else -> 0
    }

    operator fun get(address1: Int, address2: Int): Short {
        val addr1 = this[address1].toUnsignedInt()
        val addr2 = this[address2].toUnsignedInt() shl 8

        return (addr2 + addr1).toSignedShort()
    }

    fun clear() {
        internalRam.fill(0xFF.toSignedByte())
        apuAddressedMemory.reset()
        ppuAddressedMemory.reset()
    }

    fun resetVector() = this[0xFFFC, 0xFFFD]
}
