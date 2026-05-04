package com.github.alondero.nestlin.cpu.addressing

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Addressing mode for 6502 opcodes.
 * Each mode resolves to an operand byte (the value read from memory).
 */
typealias AddressingMode = (Cpu) -> Byte

/**
 * Addressing mode that resolves to an address (for read-modify-write operations like ASL, ROL).
 */
typealias AddressMode = (Cpu) -> Int

/**
 * Immediate addressing: operand is the next byte in the instruction stream.
 * Opcode: $xx
 * Operands: one byte following the opcode
 * Cycles: 2
 */
fun immediate(): AddressingMode = { it.readByteAtPC() }

/**
 * Absolute addressing: operand is a full 16-bit address following the opcode.
 * Reads the byte at the specified memory location.
 */
fun absolute(shift: (Cpu) -> Byte = { 0.toByte() }): AddressingMode =
    { cpu -> cpu.memory[absoluteAdr(shift)(cpu) and 0xFFFF] }

/**
 * Zero-page addressing: operand is an 8-bit address in page 0 ($0000-$00FF).
 * Faster than absolute as it only needs one byte to fetch and doesn't cross page boundaries.
 */
fun zeroPaged(shift: (Cpu) -> Byte = { 0.toByte() }): AddressingMode =
    { cpu -> cpu.memory[zeroPagedAdr(shift)(cpu) and 0xFFFF] }

/**
 * Indexed indirect X: operand is a zero-page address, indexed by X register.
 * Effective address = byte at (PC + X), with X wraparound in zero page.
 * Used for accessing structs/arrays indexed by X.
 */
fun indirectX(): AddressingMode =
    { cpu -> cpu.memory[indirectXAdr()(cpu)] }

/**
 * Indirect indexed Y: operand is a zero-page address, Y is added after indirection.
 * Effective address = word at PC, then + Y (with 16-bit boundary crossing).
 * Used for accessing Y-indexed arrays/strings.
 */
fun indirectY(): AddressingMode =
    { cpu -> cpu.memory[indirectYAdr()(cpu)] }

/**
 * Absolute address calculation (returns address, not value — for read-modify-write ops).
 */
fun absoluteAdr(shift: (Cpu) -> Byte = { 0.toByte() }): AddressMode =
    { cpu -> cpu.readShortAtPC().toUnsignedInt() + shift(cpu).toUnsignedInt() }

/**
 * Zero-page address calculation with optional X register offset.
 */
fun zeroPagedAdr(shift: (Cpu) -> Byte = { 0.toByte() }): AddressMode =
    { cpu -> (cpu.readByteAtPC().toUnsignedInt() + shift(cpu).toUnsignedInt()) and 0xFF }

/**
 * Indexed indirect address: (ZP + X), where ZP is byte following opcode.
 * The 6502 adds X to the zero-page address WITH page-wrap (only low 8 bits matter).
 * Then reads a 16-bit word from that zero-page address (with the usual zero-page wrap).
 */
fun indirectXAdr(): AddressMode = {
    it.let {
        val mem = it.readByteAtPC()
        it.memory[(mem + it.registers.indexX) and 0xFF, (mem + it.registers.indexX + 1) and 0xFF]
    }.toUnsignedInt()
}

/**
 * Indirect indexed address: reads word from zero-page address, then adds Y.
 * Unlike indirectX, this one DOES cross page boundaries (16-bit addition).
 */
fun indirectYAdr(): AddressMode = {
    it.let {
        val mem = it.readByteAtPC().toUnsignedInt()
        val addr = it.memory[mem].toUnsignedInt() or (it.memory[(mem + 1) and 0xFF].toUnsignedInt() shl 8)
        ((addr + it.registers.indexY.toUnsignedInt()) and 0xFFFF)
    }
}
