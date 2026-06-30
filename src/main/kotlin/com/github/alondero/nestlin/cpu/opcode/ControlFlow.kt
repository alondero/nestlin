package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt

/**
 * JMP absolute (0x4C) — jump to a 16-bit address. 3 cycles.
 * Original Opcodes.kt:224.
 */
class Jump(
    override val mnemonic: String,
) : Opcode(cycles = 3) {
    override fun evaluate(cpu: Cpu) {
        val pcBefore = cpu.registers.programCounter
        cpu.registers.programCounter = cpu.readShortAtPC()

        // Detect `JMP *` (jump-to-self) spin loop. PC now points 1 past
        // the address bytes (which were at pcBefore and pcBefore+1); if it
        // equals (pcBefore - 1), we're jumping back to the JMP opcode
        // itself — park the CPU so tick() doesn't re-execute forever.
        if (cpu.registers.programCounter == (pcBefore - 1).toSignedShort()) {
            cpu.idle = true
        }
        cpu.workCyclesLeft = 3
    }
}

/**
 * JMP indirect (0x6C) — jump to the 16-bit address stored at a 16-bit
 * pointer. 5 cycles on real 6502.
 *
 * **Issue #207 quirk fixed.** Earlier dispatcher used 3 cycles (the
 * `jump` helper hardcoded it). Now threads the real-6502 count of 5.
 *
 * **The 6502 page-boundary bug.** When the indirect pointer straddles a
 * page boundary (e.g., `$10FF` -> `$10FF, $1100`), the 6502 reads the
 * low byte from `$10FF` and the high byte from `$1000` instead of
 * `$1100` — a famous hardware quirk. The original implementation models
 * this by reading the high byte from `(addr and 0xff00) or ((addr+1) and
 * 0x00ff)` instead of `addr+1`. Preserved here.
 * Original Opcodes.kt:225-230.
 */
class JumpIndirect(
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val pcBefore = cpu.registers.programCounter
        val ptr = cpu.readShortAtPC().toUnsignedInt()
        // 6502 indirect-jump page-boundary bug: if the pointer straddles
        // a page, the high byte wraps back to the start of the same page.
        val hiByte = cpu.memory[(ptr and 0xff00) or ((ptr + 1) and 0x00ff)]
        cpu.registers.programCounter =
            ((hiByte.toUnsignedInt() shl 8) or cpu.memory[ptr].toUnsignedInt()).toSignedShort()

        if (cpu.registers.programCounter == (pcBefore - 1).toSignedShort()) {
            cpu.idle = true
        }
        cpu.workCyclesLeft = 5
    }
}

/**
 * JSR (0x20) — Jump to Subroutine. Pushes PC-1 onto the stack, sets PC
 * to the 16-bit target. 6 cycles.
 * Original Opcodes.kt:209-221.
 */
class JumpToSubroutine : Opcode(cycles = 6) {
    override val mnemonic = "JSR"
    override fun evaluate(cpu: Cpu) {
        val target = cpu.readShortAtPC()
        // PC is now 2 bytes past the JSR opcode (it consumed the target
        // bytes). We push PC-1 as the return address.
        val returnAddr = cpu.registers.programCounter.dec().toUnsignedInt()
        cpu.push((returnAddr shr 8).toSignedByte())
        cpu.push((returnAddr and 0xFF).toSignedByte())
        cpu.registers.programCounter = target
        cpu.workCyclesLeft = 6
    }
}

/**
 * RTS (0x60) — Return from Subroutine. Pops a 16-bit return address off
 * the stack, increments it (because JSR pushed PC-1, not PC), and stores
 * in PC. 6 cycles.
 * Original Opcodes.kt:250-260.
 */
class ReturnFromSubroutine : Opcode(cycles = 6) {
    override val mnemonic = "RTS"
    override fun evaluate(cpu: Cpu) {
        val lowByte = cpu.pop().toUnsignedInt()
        val highByte = cpu.pop().toUnsignedInt()
        cpu.registers.programCounter = (((lowByte and 0xff) or (highByte shl 8)) + 1).toSignedShort()
        cpu.workCyclesLeft = 6
    }
}

/**
 * RTI (0x40) — Return from Interrupt. Pops status, then 16-bit PC.
 * 6 cycles.
 * Original Opcodes.kt:323-330.
 */
class ReturnFromInterrupt : Opcode(cycles = 6) {
    override val mnemonic = "RTI"
    override fun evaluate(cpu: Cpu) {
        cpu.processorStatus.toFlags(cpu.pop())
        cpu.registers.programCounter =
            (cpu.pop().toUnsignedInt() or (cpu.pop().toUnsignedInt() shl 8)).toSignedShort()
        cpu.workCyclesLeft = 6
    }
}

/**
 * PLA / PLP — Pull from Stack.
 *
 * 2 opcodes: PLA (0x68) pulls into A and sets Z/N flags, 4 cycles.
 *            PLP (0x28) pulls into processor status, 4 cycles. Note: PLP
 *            does NOT set Z/N flags (they're whatever the byte says).
 * Original Opcodes.kt:263-270 (PLA), 314-320 (PLP).
 */
class Pull(
    val setter: (Cpu, Byte) -> Unit,
    /** Whether to set Z/N flags from the pulled byte. PLA yes, PLP no. */
    val resolvesFlags: Boolean,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        val value = cpu.pop()
        setter(cpu, value)
        if (resolvesFlags) cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        cpu.workCyclesLeft = 4
    }
}