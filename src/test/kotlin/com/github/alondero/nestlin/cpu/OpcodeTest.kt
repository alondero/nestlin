package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class OpcodeCMPTest {

    val cpu = Cpu(Memory())

    init {
        with(cpu) {
            reset()
            //  Set up for a CMP to be the next instruction
            registers.programCounter = 0x0000.toSignedShort()
            memory[0x0000] = 0xC9.toSignedByte()
        }
    }

    @Test
    fun compareWhereAccumulatorGreater() {
        with(cpu) {
            //  Set up the comparator byte to be lower than the accumulator
            memory[0x0001] = -25
            registers.accumulator = 57
            tick()

            with(processorStatus) {
                assertThat(negative, equalTo(false))
                assertThat(carry, equalTo(false))
                assertThat(zero, equalTo(false))
            }
        }
    }

    @Test
    fun compareWhereAccumulatorLessThan() {
        with(cpu) {
            //  Set up the comparator byte to be higher than the accumulator
            memory[0x0001] = 0
            registers.accumulator = 0x80.toSignedByte()
            tick()

            with(processorStatus) {
                assertThat(negative, equalTo(true))
                assertThat(carry, equalTo(true))
                assertThat(zero, equalTo(false))
            }
        }
    }

}

class OpcodePLATest() {
    val cpu = Cpu(Memory())

    init {
        with(cpu) {
            reset()
            //  Set up for a CMP to be the next instruction
            registers.programCounter = 0x0000.toSignedShort()
            memory[0x0000] = 0x68.toSignedByte()
        }
    }

    @Test
    fun pullingAValueFromStackSetsStatus() {
        with(cpu) {
            memory[0x0101] = 0xED.toSignedByte()
            registers.stackPointer = 0x00.toSignedByte()
            tick()

            with(registers) {
                assertThat(accumulator, equalTo(0xED.toSignedByte()))
                assertThat(stackPointer, equalTo(1.toSignedByte()))
            }

            with(processorStatus) {
                assertThat(zero, equalTo(false))
                assertThat(negative, equalTo(true))
            }
        }
    }



    @Test
    fun pullingAPositiveValueFromStackUnsetsNegativeFlag() {
        with(cpu) {
            memory[0x017F] = 0x68.toSignedByte()
            registers.stackPointer = 0x7E.toSignedByte()
            tick()

            with(registers) {
                assertThat(accumulator, equalTo(0x39.toSignedByte()))
                assertThat(stackPointer, equalTo(0x7F.toSignedByte()))
            }

            with(processorStatus) {
                assertThat(zero, equalTo(false))
                assertThat(negative, equalTo(false))
            }
        }
    }
}

class OpcodeJSRTest() {
    val cpu = Cpu(Memory())

    init {
        with(cpu) {
            reset()
            //  Set up for a JSR to be the next instruction
            registers.programCounter = 0xCE37.toSignedShort()
            memory[0xCE37] = 0x20.toSignedByte()
            registers.stackPointer = 0x80.toSignedByte()
        }
    }

    @Test
    fun setsCorrectValuesInMemoryOnJSR() {
        with (cpu) {
            tick()

            // 0xCE39
            assertThat(memory[0x180], equalTo(0xCE.toSignedByte()))
            assertThat(memory[0x17F], equalTo(0x39.toSignedByte()))
        }
    }
}