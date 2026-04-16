package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.gamepak.Mapper
import com.github.alondero.nestlin.ppu.PpuAddressedMemory
import com.github.alondero.nestlin.apu.ApuAddressedMemory

class Memory {
    private val internalRam = ByteArray(0x800)
    val ppuAddressedMemory = PpuAddressedMemory()
    val apuAddressedMemory = ApuAddressedMemory()

    val controller1 = Controller()
    val controller2 = Controller()

    // Will be set by Nestlin after APU creation
    var apu: Apu? = null

    // Mapper for cartridge bank switching (set during readCartridge)
    var mapper: Mapper? = null

    fun readCartridge(data: GamePak) {
        val m = data.createMapper()
        mapper = m

        // Wire CHR banking delegates to the mapper
        ppuAddressedMemory.ppuInternalMemory.chrReadDelegate = { addr -> m.ppuRead(addr) }
        ppuAddressedMemory.ppuInternalMemory.chrWriteDelegate = { addr, v -> m.ppuWrite(addr, v) }

        // Load CHR ROM into PPU pattern tables for initial tiles
        ppuAddressedMemory.ppuInternalMemory.loadChrRom(data.chrRom)

        // Apply initial mirroring from mapper
        applyMirroringFromMapper(m)
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

    operator fun get(address: Int) = when (address) {
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
