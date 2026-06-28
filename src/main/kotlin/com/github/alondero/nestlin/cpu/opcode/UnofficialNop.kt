package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte

/**
 * Unofficial NOP variants — 28 opcodes total that consume bytes/cycles
 * but do nothing else. Original Opcodes.kt:368-405, 731-768.
 *
 * Cycle counts (preserved):
 *  - NOP zp (3 cycles): 0x04, 0x44, 0x64
 *  - NOP abs (4): 0x0C
 *  - NOP abs,X (4): 0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC
 *  - NOP zp,X (4): 0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4
 *  - NOP imm (2): 0x80, 0x82, 0x89, 0xC2, 0xE2, 0xEB
 *  - NOP implied (2): 0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA
 */
class NopZp : Opcode(cycles = 3) {
    override val mnemonic = "NOP zp"
    override fun evaluate(cpu: Cpu) {
        ZeroPage().value(cpu)
        cpu.workCyclesLeft = 3
    }
}

class NopAbs : Opcode(cycles = 4) {
    override val mnemonic = "NOP abs"
    override fun evaluate(cpu: Cpu) {
        Absolute().value(cpu)
        cpu.workCyclesLeft = 4
    }
}

class NopAbsX : Opcode(cycles = 4) {
    override val mnemonic = "NOP abs,X"
    override fun evaluate(cpu: Cpu) {
        Absolute(x = true).value(cpu)
        cpu.workCyclesLeft = 4
    }
}

class NopZpX : Opcode(cycles = 4) {
    override val mnemonic = "NOP zp,X"
    override fun evaluate(cpu: Cpu) {
        ZeroPage(x = true).value(cpu)
        cpu.workCyclesLeft = 4
    }
}

class NopImm : Opcode(cycles = 2) {
    override val mnemonic = "NOP imm"
    override fun evaluate(cpu: Cpu) {
        cpu.readByteAtPC()
        cpu.workCyclesLeft = 2
    }
}

/**
 * KIL — unofficial "halt" opcode (13 variants).
 *
 * **Preserved quirk:** the original `kil` helper (Opcodes.kt:828-833) does
 * NOT actually halt the CPU — it just consumes 2 cycles and advances PC
 * by one byte (the KIL opcode itself). So a stream of KIL bytes walks
 * through them one per 2 cycles indefinitely. Preserving here; making a
 * true halt is out of scope for #192.
 */
class Kil : Opcode(cycles = 2) {
    override val mnemonic = "KIL"
    override fun evaluate(cpu: Cpu) {
        // No-op: don't advance PC, but set workCyclesLeft so the scheduler
        // decrements normally. Mirrors the original behaviour.
        cpu.workCyclesLeft = 2
    }
}