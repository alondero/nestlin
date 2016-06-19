package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.PpuRegisters

class Memory {
    private var internalRam: ByteArray = ByteArray(0x800)
    private var ppuRegisters: PpuRegisters = PpuRegisters()
    private var apuIoRegisters: ByteArray = ByteArray(0x20)
    private var cartridgeSpace: ByteArray = ByteArray(0xBFE0)

    fun readCartridge(data: GamePak) {
        for ((idx, value) in data.programRom.copyOfRange(0, 16384).withIndex()) {
            cartridgeSpace[0x8000+idx - 0x4020] = value
        }

        for ((idx, value) in data.programRom.copyOfRange(data.programRom.size - 16384, data.programRom.size).withIndex()) {
            cartridgeSpace[0xC000+idx - 0x4020] = value
        }
    }

    operator fun set(address: Int, value: Byte) {
        when (address) {
            in 0x0000..0x1FFF -> internalRam[address%0x800] = value
            in 0x2000..0x3FFF -> ppuRegisters[address%8] = value
            in 0x4000..0x401F -> apuIoRegisters[address-0x4000] = value
            else -> cartridgeSpace[address-0x4020] = value
        }
    }

    operator fun get(address: Int): Byte {
        var foundByte: Byte

        when (address) {
            in 0x0000..0x1FFF -> foundByte = internalRam[address%0x800]
            in 0x2000..0x3FFF -> foundByte = ppuRegisters[address%8]
            in 0x4000..0x401F -> foundByte = apuIoRegisters[address - 0x4000]
            else /* in 0x4020..0xFFFF */ -> foundByte = cartridgeSpace[address - 0x4020]
        }

        return foundByte
    }

    operator fun get(address1: Int, address2: Int): Short {
        val addr1 = this[address1].toUnsignedInt()
        val addr2 = this[address2].toUnsignedInt() shl 8

        return (addr2 + addr1).toSignedShort()
    }

    fun clear() {
        internalRam.fill(0xFF.toSignedByte())
        apuIoRegisters.fill(0) // TODO: Do something better with APU Registers (when implementing audio and input)
        ppuRegisters.reset()
    }
}