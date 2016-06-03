package com.github.alondero.nestlin

class Cpu {
    private var memory: Memory = Memory()
    private var registers: Registers = Registers()
    private var processorStatus: ProcessorStatus = ProcessorStatus()
    private var interrupt: Interrupt = Interrupt.IRQ_BRK
    private var addressingMode: AddressingMode = AddressingMode.IMPLICIT

    fun reset() {
        memory.clear()
        processorStatus.reset()
        registers.reset()
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
        programCounter = 0 // TODO: Get Reset Vector??
        stackPointer = 0xFD.toByte()
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

    fun clear() {
        internalRam.fill(0xFF.toByte())
        // Set everything else to 0
    }
}
