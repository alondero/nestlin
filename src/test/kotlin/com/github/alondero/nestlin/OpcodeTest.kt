package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class OpcodeCMPTest {

    val cpu = Cpu()

    init {
        cpu.apply {
            reset()
            //  Set up for a CMP to be the next instruction
            registers.programCounter = 0x0000.toSignedShort()
            memory[0x0000] = 0xC9.toSignedByte()
        }
    }

    @Test
    fun compareWhereAccumulatorGreater() {
        //  Set up the comparator byte to be lower than the accumulator
        cpu.memory[0x0001] = -25
        cpu.registers.accumulator = 57

        cpu.tick()

        cpu.processorStatus.apply {
            assertThat(negative, equalTo(false))
            assertThat(carry, equalTo(true))
            assertThat(zero, equalTo(false))
        }

        println(cpu.processorStatus.asByte().toHexString())
    }

    @Test
    fun compareWhereAccumulatorLessThan() {
        //  Set up the comparator byte to be higher than the accumulator
        cpu.memory[0x0001] = 0
        cpu.registers.accumulator = 0x80.toSignedByte()

        cpu.tick()

        cpu.processorStatus.apply {
            assertThat(negative, equalTo(true))
            assertThat(carry, equalTo(true))
            assertThat(zero, equalTo(false))
        }

        println(cpu.processorStatus.asByte().toHexString())
    }

}

class OpcodePLATest() {
    val cpu = Cpu()

    init {
        cpu.apply {
            reset()
            //  Set up for a CMP to be the next instruction
            registers.programCounter = 0x0000.toSignedShort()
            memory[0x0000] = 0x68.toSignedByte()
        }
    }

    @Test
    fun pullingAValueFromStackSetsStatus() {
        cpu.memory[0x0101] = 0xED.toSignedByte()
        cpu.registers.stackPointer = 0x00.toSignedByte()

        cpu.tick()
        assertThat(cpu.registers.accumulator, equalTo(0xED.toSignedByte()))
        assertThat(cpu.registers.stackPointer, equalTo(1.toSignedByte()))
        assertThat(cpu.processorStatus.zero, equalTo(false))
        assertThat(cpu.processorStatus.negative, equalTo(true))
    }



    @Test
    fun pullingAPositiveValueFromStackUnsetsNegativeFlag() {
        cpu.memory[0x017F] = 0x68.toSignedByte()
        cpu.registers.stackPointer = 0x7E.toSignedByte()

        cpu.tick()
        assertThat(cpu.registers.accumulator, equalTo(0x39.toSignedByte()))
        assertThat(cpu.registers.stackPointer, equalTo(0x7F.toSignedByte()))
        assertThat(cpu.processorStatus.zero, equalTo(false))
        assertThat(cpu.processorStatus.negative, equalTo(false))
    }
}

class OpcodeJSRTest() {
    val cpu = Cpu()

    init {
        cpu.apply {
            reset()
            //  Set up for a JSR to be the next instruction
            registers.programCounter = 0xCE37.toSignedShort()
            memory[0xCE37] = 0x20.toSignedByte()
            registers.stackPointer = 0x80.toSignedByte()
        }
    }

    @Test
    fun setsCorrectValuesInMemoryOnJSR() {
        cpu.tick()

        // 0xCE39

        assertThat(cpu.memory[0x180], equalTo(0xCE.toSignedByte()))
        assertThat(cpu.memory[0x17F], equalTo(0x39.toSignedByte()))
    }
}