package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class OpcodeTest {

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
    fun testCompareWhereAccumulatorGreater() {
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
    fun testCompareWhereAccumulatorLessThan() {
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