package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import java.util.*

class Cpu(
        var memory: Memory = Memory(),
        var registers: Registers = Registers(),
        var processorStatus: ProcessorStatus = ProcessorStatus(),
        var interrupt: Interrupt? = null,
        var addressingMode: AddressingMode? = null,
        var opcodes: Opcodes = Opcodes()
) {
    var currentGame: GamePak? = null
    private val testRomCrc = 0x9e179d92

    fun reset() {
        memory.clear()
        var isTestRom = false
        currentGame?.let {
            memory.readCartridge(it)
            isTestRom = it.crc.value == testRomCrc
        }

        processorStatus.reset()
        registers.reset()

        if (isTestRom) {
            // Starts test rom in automation mode
            registers.programCounter = 0xc000.toSignedShort()
        } else {
            registers.programCounter = resetVector()
        }
        println("Initialised program counter as ${registers.programCounter.toHexString()}")
    }

    private fun resetVector() = memory[0xFFFC, 0xFFFD]

    fun tick() {
        println("\ntick!")
        val opcodeVal = memory[registers.programCounter++.toUnsignedInt()].toUnsignedInt()
        val opcode = opcodes[opcodeVal] ?: throw UnhandledOpcodeException(opcodeVal)

        opcode?.apply {
            println("""Opcode ${Integer.toHexString(opcodeVal)}, $this.
Registers: $registers""")
            this.op(this@Cpu)
        }
    }

    fun push(value: Byte) {
        memory[(0x100 + (registers.stackPointer.toUnsignedInt() and 0xFF))] = value
        --registers.stackPointer
    }
}



class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${Integer.toHexString(opcodeVal)} not implemented")

class Opcodes {
    val map = HashMap<Int, Opcode>()

    init {
        map[0x00] = Opcode(Opcode.OpcodeType.BRK) {
            it.processorStatus.breakCommand = true
            it.registers.programCounter++
            it.registers.programCounter.toUnsignedInt().apply {
                it.push((this shr 8).toSignedByte())
                it.push((this and 0xFF).toSignedByte())
            }
            it.push(it.processorStatus.asByte()) // TODO: Correctly set this
            it.registers.programCounter = it.memory[0xFFFE, 0xFFFF]
            it.processorStatus.interruptDisable = true

            //  Takes 7 cycles
        }
        map[0x20] = Opcode(Opcode.OpcodeType.JSR) {
            val next = it.memory[it.registers.programCounter++.toUnsignedInt(), it.registers.programCounter++.toUnsignedInt()]

            it.registers.programCounter--.toUnsignedInt().apply {
                it.memory[this] // TODO: Do we need this?
                it.push((this shr 8).toSignedByte())
                it.push((this and 0xFF).toSignedByte())
                it.registers.programCounter = next
            }
            //  TODO: Takes 6 cycles
        }
        map[0x78] = Opcode(Opcode.OpcodeType.SEI) {
            it.processorStatus.interruptDisable = true
            //  TODO: Takes 2 cycles
        }
        map[0xd8] = Opcode(Opcode.OpcodeType.SEI) {
            it.processorStatus.decimalMode = false
            //  TODO: Takes 2 cycles
        }
        map[0x4c] = Opcode(Opcode.OpcodeType.JMP) {
            val temp = it.registers.programCounter
            it.registers.programCounter = it.memory[it.registers.programCounter++.toUnsignedInt(), it.registers.programCounter++.toUnsignedInt()]

            if (it.registers.programCounter == (temp-1).toSignedShort()) {
                //  TODO: Set idle
            }
            //  TODO: Takes 3 cycles
        }
        map[0xa2] = Opcode(Opcode.OpcodeType.LDX) {
            it.registers.indexX = it.memory[it.registers.programCounter++.toUnsignedInt()].apply {
                it.processorStatus.setFlags(this)
            }

            //  TODO: Takes 2 cycles
        }
        map[0x86] = Opcode(Opcode.OpcodeType.STX) {
            it.memory[it.memory[it.registers.programCounter++.toUnsignedInt()].toUnsignedInt()] = it.registers.indexX
            // TODO: Takes 3 cycles
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val type: OpcodeType, val op: (Cpu) -> Unit) {

    enum class OpcodeType {
        BRK,
        JSR,
        SEI,
        JMP,
        LDX,
        STX
    }

    override fun toString(): String = type.toString()
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
        var indexY: Byte = 0
) {
    var programCounter: Short = 0
        set(value) {
            println("Setting program counter to ${value.toHexString()}")
            field = value
        }

    fun reset() {
        stackPointer = -3 // Skips decrementing three times from init
        accumulator = 0
        indexX = 0
        indexY = 0
    }

    override fun toString() = "PC: ${programCounter.toHexString()}, SP: ${stackPointer.toHexString()}, A: ${accumulator.toHexString()}, X: ${indexX.toHexString()}, Y: ${indexY.toHexString()}"
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
        // TODO: Honour processor status correctly - currently just returns interruptsDisabled + 4 and 5 set (for break interrupt)
        return 0b00110100
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

        println("Found: ${foundByte.toHexString()} at ${address.toHexString()}")
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
