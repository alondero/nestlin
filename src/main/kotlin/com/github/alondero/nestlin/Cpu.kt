package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak

class Cpu {
    private var memory: Memory = Memory()
    private var registers: Registers = Registers()
    private var processorStatus: ProcessorStatus = ProcessorStatus()
    private var interrupt: Interrupt = Interrupt.IRQ_BRK
    private var addressingMode: AddressingMode = AddressingMode.IMPLICIT

    var currentGame: GamePak? = null

    fun reset() {
        memory.clear()
        currentGame?.let {memory.readCartridge(it)}

        processorStatus.reset()
        registers.reset()

        registers.programCounter = memory[0xFFFC, 0xFFFD]
    }

    fun tick() {
        println("Opcode ${memory[registers.programCounter + 128]}")
    }
}

enum class AddressingMode {
    //  Standard 6502 addressing modes
    ZERO_PAGE_INDEXED_X,
    ZERO_PAGE_INDEXED_Y,
    ABSOLUTE_INDEXED_X,
    ABSOLUTE_INDEXED_Y,
    INDEXED_INDIRECT_X,
    INDIRECT_INDEXED_Y,

    // Other addressing
    IMPLICIT,
    ACCUMULATOR,
    IMMEDIATE,
    ZERO_PAGE,
    ABSOLUTE,
    RELATIVE,
    INDIRECT
}

enum class Interrupt {
    IRQ_BRK,
    NMI,
    RESET
}

data class Registers(
        var programCounter: Short = 0,
        var stackPointer: Byte = 0,
        var accumulator: Byte = 0,
        var indexX: Byte = 0,
        var indexY: Byte = 0
) {
    fun reset() {
        stackPointer = 125 // unsigned 0xFD
        accumulator = 0
        indexX = 0
        indexY = 0
    }
}

data class ProcessorStatus(
        var carry: Boolean = false,
        var zero: Boolean = true,
        var interruptDisable: Boolean = true,
        var decimalMode: Boolean = false,
        var breakCommand: Boolean = false,
        var overflow: Boolean = false,
        var negative: Boolean = false
) {
    fun reset() {
        carry = false
        zero = false
        interruptDisable = true
        decimalMode = false
        breakCommand = false
        overflow = false
        negative = false
    }
}

class Memory {
    private var internalRam: ByteArray = ByteArray(0x800)
    private var ppuRegisters: PpuRegisters = PpuRegisters()
    private var apuIoRegisters: ByteArray = ByteArray(0x20)
    private var cartridgeSpace: ByteArray = ByteArray(0xBFE0)

    fun readCartridge(data: GamePak) {
        // Mapper 0 defaults
        for ((idx, value) in data.programRom.copyOfRange(0, 16384).withIndex()) {
            this[0x8000+idx] = value
        }

        for ((idx, value) in data.programRom.copyOfRange(data.programRom.size - 16384, data.programRom.size).withIndex()) {
            this[0xC000+idx] = value
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
        println("Looking up $address")

        when (address) {
            in 0x0000..0x1FFF -> return internalRam[address%0x800]
            in 0x2000..0x3FFF -> return ppuRegisters[address%8]
            in 0x4000..0x401F -> return apuIoRegisters[address - 0x4000]
            else /* in 0x4020..0xFFFF */ -> return cartridgeSpace[address - 0x4020]
        }
    }

    operator fun get(address1: Int, address2: Int): Short = ((get(address2).toInt() or get(address1).toInt() shl(8)) - 32767).toShort()

    fun clear() {
        internalRam.fill(Byte.MAX_VALUE)
        ppuRegisters.reset()
    }
}

data class PpuRegisters (
        var controller: Byte = 0,
        var mask: Byte = 0,
        var status: Byte = 0,
        var oamAddress: Byte = 0,
        var oamData: Byte = 0,
        var scroll: Byte = 0,
        var address: Byte = 0,
        var data: Byte = 0
) {
    fun reset() {
        controller = 0
        mask = 0
        status = 0
        oamAddress = 0
        oamData = 0
        scroll = 0
        address = 0
        data = 0
    }

    operator fun get(addr: Int): Byte {
        when (addr) {
            0 -> return controller
            1 -> return mask
            2 -> return status
            3 -> return oamAddress
            4 -> return oamData
            5 -> return scroll
            6 -> return address
            else /*7*/ -> return data
        }
    }

    operator fun set(addr: Int, value: Byte) {
        when (addr) {
            0 -> controller = value
            1 -> mask = value
            2 -> status = value
            3 -> oamAddress = value
            4 -> oamData = value
            5 -> scroll = value
            6 -> address = value
            else /*7*/ -> data = value
        }
    }
}
