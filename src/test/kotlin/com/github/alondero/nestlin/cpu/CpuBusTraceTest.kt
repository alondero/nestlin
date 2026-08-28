package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Memory.CpuBusAccess
import com.github.alondero.nestlin.Memory.CpuBusOperation.READ
import com.github.alondero.nestlin.Memory.CpuBusOperation.WRITE
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class CpuBusTraceTest {
    @Test
    fun `LDA absolute changes registers on the operand bus cycle`() {
        val fixture = fixture(0xAD, 0x10, 0x00)
        fixture.cpu.registers.accumulator = 0x7F
        fixture.memory[0x0010] = 0x42

        fixture.tick(3)
        assertThat(fixture.cpu.registers.accumulator, equalTo(0x7F.toSignedByte()))

        fixture.tick(4)
        assertThat(fixture.cpu.registers.accumulator, equalTo(0x42.toSignedByte()))
    }

    @Test
    fun `STA absolute writes on its fourth cycle`() {
        val fixture = fixture(0x8D, 0x01, 0x20)
        fixture.cpu.registers.accumulator = 0x5A

        fixture.tick(1)
        assertThat(fixture.trace, equalTo(listOf(access(READ, 0x0200, 0x8D))))

        fixture.tick(2)
        assertThat(fixture.trace.last(), equalTo(access(READ, 0x0201, 0x01)))

        fixture.tick(3)
        assertThat(fixture.trace.last(), equalTo(access(READ, 0x0202, 0x20)))

        fixture.tick(4)
        assertThat(fixture.trace.last(), equalTo(access(WRITE, 0x2001, 0x5A)))
        assertThat(fixture.trace.size, equalTo(4))
    }

    @Test
    fun `LDA absolute X page cross performs wrong-page dummy read`() {
        val fixture = fixture(0xBD, 0xFF, 0x00)
        fixture.cpu.registers.indexX = 1
        fixture.memory[0x0100] = 0x42
        fixture.trace.clear()

        fixture.tick(5)

        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(READ, 0x0200, 0xBD),
                    access(READ, 0x0201, 0xFF),
                    access(READ, 0x0202, 0x00),
                    access(READ, 0x0000, 0x00),
                    access(READ, 0x0100, 0x42),
                )
            )
        )
        assertThat(fixture.cpu.registers.accumulator.toInt() and 0xFF, equalTo(0x42))
    }

    @Test
    fun `ASL zero page writes old value before new value`() {
        val fixture = fixture(0x06, 0x10)
        fixture.memory[0x0010] = 0x81.toSignedByte()
        fixture.trace.clear()

        fixture.tick(5)

        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(READ, 0x0200, 0x06),
                    access(READ, 0x0201, 0x10),
                    access(READ, 0x0010, 0x81),
                    access(WRITE, 0x0010, 0x81),
                    access(WRITE, 0x0010, 0x02),
                )
            )
        )
    }

    @Test
    fun `unofficial TAS indexed store keeps its dummy read before the final write`() {
        val fixture = fixture(0x9B, 0xFF, 0x00) // TAS $00FF,Y
        fixture.cpu.registers.accumulator = 0xFF.toSignedByte()
        fixture.cpu.registers.indexX = 0x0F.toSignedByte()
        fixture.cpu.registers.indexY = 1

        fixture.tick(5)

        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(READ, 0x0200, 0x9B),
                    access(READ, 0x0201, 0xFF),
                    access(READ, 0x0202, 0x00),
                    access(READ, 0x0000, 0x00),
                    access(WRITE, 0x0100, 0x02),
                )
            )
        )
    }

    @Test
    fun `BRK stack and vector accesses occupy all seven cycles`() {
        val fixture = fixture(0x00, 0xEA)
        fixture.cpu.registers.stackPointer = 0xFD.toSignedByte()

        fixture.tick(7)

        assertThat(
            fixture.trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0201,
                    WRITE to 0x01FD,
                    WRITE to 0x01FC,
                    WRITE to 0x01FB,
                    READ to 0xFFFE,
                    READ to 0xFFFF,
                )
            )
        )
        assertThat(fixture.cpu.registers.stackPointer.toInt() and 0xFF, equalTo(0xFA))
    }

    @Test
    fun `taken branch performs its sequential dummy read before changing PC`() {
        val fixture = fixture(0xD0, 0x02) // BNE $0204
        fixture.cpu.processorStatus.zero = false

        fixture.tick(3)

        assertThat(
            fixture.trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0201,
                    READ to 0x0202,
                )
            )
        )
        assertThat(fixture.cpu.registers.programCounter.toUnsignedInt(), equalTo(0x0204))
    }

    @Test
    fun `JSR uses a stack dummy read and pushes the return address across six cycles`() {
        val fixture = fixture(0x20, 0x34, 0x12) // JSR $1234
        fixture.cpu.registers.stackPointer = 0xFD.toSignedByte()

        fixture.tick(6)

        assertThat(
            fixture.trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0201,
                    READ to 0x01FD,
                    WRITE to 0x01FD,
                    WRITE to 0x01FC,
                    READ to 0x0202,
                )
            )
        )
        assertThat(fixture.cpu.registers.programCounter.toUnsignedInt(), equalTo(0x1234))
    }

    @Test
    fun `RTS pops the return address and performs its final read`() {
        val fixture = fixture(0x60)
        fixture.cpu.registers.stackPointer = 0xFB.toSignedByte()
        fixture.memory[0x01FC] = 0x02
        fixture.memory[0x01FD] = 0x02
        fixture.trace.clear()

        fixture.tick(6)

        assertThat(
            fixture.trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0201,
                    READ to 0x01FB,
                    READ to 0x01FC,
                    READ to 0x01FD,
                    READ to 0x0203,
                )
            )
        )
        assertThat(fixture.cpu.registers.programCounter.toUnsignedInt(), equalTo(0x0203))
    }

    @Test
    fun `RTI restores status and vector across six bus cycles`() {
        val fixture = fixture(0x40)
        fixture.cpu.registers.stackPointer = 0xFB.toSignedByte()
        fixture.memory[0x01FC] = 0x01 // carry
        fixture.memory[0x01FD] = 0x78
        fixture.memory[0x01FE] = 0x56
        fixture.trace.clear()

        fixture.tick(6)

        assertThat(
            fixture.trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0201,
                    READ to 0x01FB,
                    READ to 0x01FC,
                    READ to 0x01FD,
                    READ to 0x01FE,
                )
            )
        )
        assertThat(fixture.cpu.registers.programCounter.toUnsignedInt(), equalTo(0x5678))
        assertThat(fixture.cpu.processorStatus.carry, equalTo(true))
    }

    @Test
    fun `NMI entry is a resumable seven-cycle bus sequence`() {
        val (memory, _) = Memory.createWithApu()
        val interrupts = FakeInterruptController()
        val cpu = Cpu(memory, interrupts)
        cpu.registers.programCounter = 0x0200.toShort()
        cpu.registers.stackPointer = 0xFD.toSignedByte()
        memory[0x0200] = 0xEA.toSignedByte()
        val trace = mutableListOf<CpuBusAccess>()
        memory.cpuBusObserver = { trace += it }
        cpu.idle = true // parked CPUs dispatch an NMI without the one-instruction arm latency
        interrupts.armNmi()

        repeat(7) { cpu.tick() }

        assertThat(
            trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0200,
                    WRITE to 0x01FD,
                    WRITE to 0x01FC,
                    WRITE to 0x01FB,
                    READ to 0xFFFA,
                    READ to 0xFFFB,
                )
            )
        )
        assertThat(cpu.nmiCount, equalTo(1))
    }

    @Test
    fun `IRQ entry is a resumable seven-cycle bus sequence`() {
        val (memory, _) = Memory.createWithApu()
        val interrupts = FakeInterruptController()
        val cpu = Cpu(memory, interrupts)
        cpu.registers.programCounter = 0x0200.toShort()
        cpu.registers.stackPointer = 0xFD.toSignedByte()
        cpu.processorStatus.interruptDisable = false
        memory[0x0200] = 0xEA.toSignedByte()
        val trace = mutableListOf<CpuBusAccess>()
        memory.cpuBusObserver = { trace += it }
        cpu.idle = true
        interrupts.armIrq()

        repeat(7) { cpu.tick() }

        assertThat(
            trace.map { it.operation to it.address },
            equalTo(
                listOf(
                    READ to 0x0200,
                    READ to 0x0200,
                    WRITE to 0x01FD,
                    WRITE to 0x01FC,
                    WRITE to 0x01FB,
                    READ to 0xFFFE,
                    READ to 0xFFFF,
                )
            )
        )
        assertThat(cpu.irqCount, equalTo(1))
    }

    @Test
    fun `OAM DMA alternates one bus access per cycle and resumes after 513 cycles`() {
        val fixture = fixture(0xEA)
        repeat(256) { fixture.memory[0x0300 + it] = it.toSignedByte() }
        fixture.memory.ppuAddressedMemory.oamAddress = 0x20.toSignedByte()
        fixture.memory[0x4014] = 0x03.toByte()
        fixture.trace.clear()

        repeat(513) { fixture.cpu.tick() }

        assertThat(fixture.trace.size, equalTo(513))
        assertThat(fixture.trace[0].operation to fixture.trace[0].address, equalTo(READ to 0x0200))
        assertThat(fixture.trace[1].operation to fixture.trace[1].address, equalTo(READ to 0x0300))
        assertThat(fixture.trace[2].operation to fixture.trace[2].address, equalTo(WRITE to 0x2004))
        assertThat(fixture.trace[511].operation to fixture.trace[511].address, equalTo(READ to 0x03FF))
        assertThat(fixture.trace[512].operation to fixture.trace[512].address, equalTo(WRITE to 0x2004))
        assertThat(fixture.cpu.workCyclesLeft, equalTo(0))
        assertThat(fixture.memory.ppuAddressedMemory.objectAttributeMemory[0].toUnsignedInt(), equalTo(0))
        assertThat(fixture.memory.ppuAddressedMemory.objectAttributeMemory[1].toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `CPU initiated OAM DMA is not cleared when the STA instruction completes`() {
        val fixture = fixture(0x8D, 0x14, 0x40)
        fixture.cpu.registers.accumulator = 0x03
        repeat(256) { fixture.memory[0x0300 + it] = it.toSignedByte() }
        fixture.trace.clear()

        fixture.tick(4) // the $4014 write is the final STA cycle

        assertThat(fixture.cpu.workCyclesLeft, equalTo(513))
        repeat(513) { fixture.cpu.tick() }
        assertThat(fixture.cpu.workCyclesLeft, equalTo(0))
        assertThat(fixture.trace.size, equalTo(4 + 513))
    }

    @Test
    fun `mid-instruction save restores the same remaining bus cycles`() {
        val fixture = fixture(0x8D, 0x10, 0x00)
        fixture.cpu.registers.accumulator = 0x66.toByte()
        fixture.tick(2) // opcode + low operand fetched; high operand and write remain
        val state = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(fixture.cpu::saveState)
        }.toByteArray()

        val restored = fixture(0x8D, 0x10, 0x00)
        restored.cpu.registers.accumulator = 0x66.toByte()
        DataInputStream(ByteArrayInputStream(state)).use(restored.cpu::loadState)
        restored.trace.clear()
        repeat(2) { restored.cpu.tick() }

        assertThat(
            restored.trace,
            equalTo(
                listOf(
                    access(READ, 0x0202, 0x00),
                    access(WRITE, 0x0010, 0x66),
                )
            )
        )
    }

    @Test
    fun `mid-instruction save preserves the parity used by a following DMA`() {
        val fixture = fixture(0x8D, 0x14, 0x40)
        fixture.cpu.registers.accumulator = 0x03
        fixture.tick(1) // Save after the opcode fetch, on an odd cycle count.
        val state = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(fixture.cpu::saveState)
        }.toByteArray()

        val restored = fixture(0x8D, 0x14, 0x40)
        restored.cpu.registers.accumulator = 0x03
        DataInputStream(ByteArrayInputStream(state)).use(restored.cpu::loadState)
        restored.trace.clear()
        repeat(3) { restored.cpu.tick() }

        // The final STA cycle starts on the same odd parity as the original;
        // after that write, one alignment cycle plus 512 transfer cycles remain.
        assertThat(restored.cpu.workCyclesLeft, equalTo(513))
        assertThat(restored.trace.last(), equalTo(access(WRITE, 0x4014, 0x03)))
    }

    private fun fixture(vararg program: Int): Fixture {
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory)
        cpu.registers.programCounter = 0x0200.toShort()
        program.forEachIndexed { index, value -> memory[0x0200 + index] = value.toSignedByte() }
        val trace = mutableListOf<CpuBusAccess>()
        memory.cpuBusObserver = { trace.add(it) }
        return Fixture(cpu, memory, trace)
    }

    private fun access(operation: Memory.CpuBusOperation, address: Int, value: Int) =
        CpuBusAccess(operation, address, value.toSignedByte())

    private data class Fixture(
        val cpu: Cpu,
        val memory: Memory,
        val trace: MutableList<CpuBusAccess>,
    ) {
        fun tick(targetCycle: Int) {
            while (cpu.cycleCount < targetCycle) cpu.tick()
        }
    }
}
