package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.PpuAddressedMemory

class Memory {
    private val internalRam = ByteArray(0x800)
    val ppuAddressedMemory = PpuAddressedMemory()
    private val apuIoRegisters = ByteArray(0x20)
    private val cartridgeSpace = ByteArray(0xBFE0)

    fun readCartridge(data: GamePak) {
        // Load PRG ROM into CPU address space
        data.programRom.copyOfRange(0, 16384).withIndex().forEach {
            (idx, value) -> cartridgeSpace[0x8000+idx - 0x4020] = value
        }

        data.programRom.copyOfRange(data.programRom.size - 16384, data.programRom.size).withIndex().forEach {
            (idx, value) -> cartridgeSpace[0xC000+idx - 0x4020] = value
        }

        // Load CHR ROM into PPU pattern tables
        ppuAddressedMemory.ppuInternalMemory.loadChrRom(data.chrRom)

        // Set nametable mirroring mode from iNES header
        val pmuMirroring = if (data.header.mirroring == com.github.alondero.nestlin.gamepak.Header.Mirroring.HORIZONTAL) {
            com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.HORIZONTAL
        } else {
            com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.VERTICAL
        }
        ppuAddressedMemory.ppuInternalMemory.mirroring = pmuMirroring
    }

    operator fun set(address: Int, value: Byte) {
        when (address) {
            in 0x0000..0x1FFF -> internalRam[address%0x800] = value
            in 0x2000..0x3FFF -> ppuAddressedMemory[address%8] = value
            in 0x4000..0x401F -> apuIoRegisters[address-0x4000] = value
            else -> cartridgeSpace[address-0x4020] = value
        }
    }

    operator fun get(address: Int) = when (address) {
        in 0x0000..0x1FFF -> internalRam[address % 0x800]
        in 0x2000..0x3FFF -> ppuAddressedMemory[address % 8]
        in 0x4000..0x401F -> apuIoRegisters[address - 0x4000]
        else /* in 0x4020..0xFFFF */ -> cartridgeSpace[address - 0x4020]
    }

    operator fun get(address1: Int, address2: Int): Short {
        val addr1 = this[address1].toUnsignedInt()
        val addr2 = this[address2].toUnsignedInt() shl 8

        return (addr2 + addr1).toSignedShort()
    }

    fun clear() {
        internalRam.fill(0xFF.toSignedByte())
        apuIoRegisters.fill(0) // TODO: Do something better with APU Registers (when implementing audio and input)
        ppuAddressedMemory.reset()
    }

    fun resetVector() = this[0xFFFC, 0xFFFD]
}