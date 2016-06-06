package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger

private const val TEST_ROM_CRC = 0x9e179d92

class Cpu(
        var memory: Memory = Memory(),
        var registers: Registers = Registers(),
        var processorStatus: ProcessorStatus = ProcessorStatus(),
        var interrupt: Interrupt? = null,
        var addressingMode: AddressingMode? = null,
        var opcodes: Opcodes = Opcodes()
) {
    var currentGame: GamePak? = null
    private val logger: Logger? = Logger() // TODO: Only log when DEBUG param passed

    fun reset() {
        memory.clear()
        var isTestRom = false
        currentGame?.let {
            memory.readCartridge(it)
            isTestRom = it.crc.value == TEST_ROM_CRC
        }

        processorStatus.reset()
        registers.reset()

        if (isTestRom) {
            // Starts test rom in automation mode
            registers.programCounter = 0xc000.toSignedShort()
        } else {
            registers.programCounter = resetVector()
        }
    }

    private fun resetVector() = memory[0xFFFC, 0xFFFD]



    fun tick() {
        val initialPC = registers.programCounter
        val opcodeVal = memory[registers.programCounter++.toUnsignedInt()].toUnsignedInt()
        val opcode = opcodes[opcodeVal] ?: throw UnhandledOpcodeException(opcodeVal)

        opcode?.apply {
            op(this@Cpu)
            logger?.cpuTick(initialPC, opcodeVal, this@Cpu)
        }
    }

    fun push(value: Byte) {
        memory[(0x100 + (registers.stackPointer.toUnsignedInt() and 0xFF))] = value
        --registers.stackPointer
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
        var stackPointer: Byte = 0,
        var accumulator: Byte = 0,
        var indexX: Byte = 0,
        var indexY: Byte = 0,
        var programCounter: Short = 0
) {
    fun reset() {
        stackPointer = -3 // Skips decrementing three times from init
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

    fun asByte(): Byte {
        return ((if (negative) (1 shl 7) else 0) or
                (if (overflow) (1 shl 6) else 0) or
                1 shl 5 or // Special logic needed for the B flag...
                1 shl 4 or
                (if (decimalMode) (1 shl 3) else 0) or
                (if (interruptDisable) (1 shl 2) else 0) or
                (if (zero) (1 shl 1) else 0) or
                (if (carry) 1 else 0)).toSignedByte()
    }

    fun setFlags(result: Byte) {
        zero = (result.toUnsignedInt() == 0)
        negative = result.isBitSet(7)
    }
}

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
