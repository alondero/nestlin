package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Sealed base class for all 6502 opcodes.
 *
 * **Why a sealed class (issue #192).** Replaces the previous
 * `HashMap<Int, Opcode>` keyed by opcode byte, where every `Opcode` was an
 * `Opcode(val op: (Cpu) -> Unit)` wrapper around an inline lambda. Each
 * family now has its own concrete subclass, the cycle count and
 * addressing-mode choice live co-located in the constructor, and dispatch
 * is polymorphic via [evaluate].
 *
 * **Sealed-class dispatch contract.**
 *  - Subclasses MUST be declared in this package (`com.github.alondero.nestlin.cpu.opcode`).
 *  - Subclasses MUST override [evaluate] and MUST NOT mutate per-CPU state
 *    on themselves — Opcode instances are stateless across ticks. The only
 *    state each subclass holds is construction-time: cycle count,
 *    addressing mode, and helper lambdas.
 *  - Subclasses MAY expose additional fields for test introspection, but
 *    those fields MUST be `val` and set in the constructor.
 *
 * **Cycle-count threading.** [cycles] is the real-6502 base cycle count for
 * the opcode's addressing mode. For [Branch] this is the NOT-TAKEN count
 * (taken is computed inside `evaluate` per the 6502 spec — 3 cycles, +1
 * on page cross). For all other opcodes it is the exact count. The Cpu's
 * `tick()` decrements [Cpu.workCyclesLeft] after the opcode runs, so a
 * 2-cycle instruction leaves `workCyclesLeft == 1` after one tick — see
 * [OpcodeCycleTableTest] for the regression bar.
 *
 * **Preserved quirks (issue #207).** Several opcodes still carry
 * intentional deviations from real 6502 behaviour; the ones that survived
 * the #207 cleanup are documented inline in the relevant subclass.
 * Fixed in #207: LAX/SAX per-mode cycle counts, JMP indirect cycle
 * count, AHX mask, 0x9B dispatch (now TAS), 0xE3 dispatch (now DCP),
 * SHY/SHX registration, KIL halt behaviour, Absolute address masking.
 */
sealed class Opcode(val cycles: Int) {
    /**
     * Run this opcode against [cpu]. Reads operands from
     * `cpu.registers.programCounter` / `cpu.memory[...]`, mutates registers,
     * flags, and PC, and sets `cpu.workCyclesLeft` to the real-6502 cycle
     * count (which the Cpu scheduler then decrements per tick).
     */
    abstract fun evaluate(cpu: Cpu)

    /** A short mnemonic for diagnostic / logging purposes. */
    abstract val mnemonic: String
}

// ============================================================================
// Branch family — 8 opcodes: BCC, BCS, BEQ, BNE, BMI, BPL, BVC, BVS
// ============================================================================

/**
 * Branch on flag condition. 8 official opcodes. The cycle count is the
 * not-taken count (2); taken branches compute 3 (+1 on page cross)
 * inside [evaluate] per the 6502 spec — preserving the original
 * `Opcodes.kt:513-536` behaviour including the page-boundary check and
 * idle-loop detection.
 */
class Branch(
    val condition: (Cpu) -> Boolean,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        if (condition(cpu)) {
            // Original semantics from Opcodes.kt:516 — captures
            // `registers.programCounter.inc()` (PC + 1) BEFORE the operand
            // read. After the operand read, PC will be at PC + 1 (i.e.,
            // equal to previousCounter). This "previousCounter" value is
            // then used to:
            //  1. Detect page-cross via hasCrossedPageBoundary.
            //  2. Detect branch-to-self spin loops.
            // The arithmetic `previousCounter - 2 == newPC` reduces to
            // `PC_at_opcode_fetch - 1 == newPC`. For BEQ -2 at PC=0x0000,
            // the branch lands at PC=0x0000 and idle is set.
            val previousCounter: Short = cpu.registers.programCounter.inc()

            // Apply the relative offset (signed). This advances PC past
            // the offset byte in the process.
            cpu.registers.programCounter =
                (cpu.readByteAtPC() + cpu.registers.programCounter).toSignedShort()

            // Issue #176: taken relative branch costs 3 cycles; +1 on
            // page boundary. Compute cycles directly in the taken-arm so
            // the +1 reaches the scheduler — pageBoundaryFlag alone is
            // write-only.
            cpu.workCyclesLeft = 3
            if (cpu.hasCrossedPageBoundary(previousCounter, cpu.registers.programCounter)) {
                cpu.pageBoundaryFlag = true
                cpu.workCyclesLeft = 4
            }
            // Detect branch-to-self spin loop (see comment above).
            if ((previousCounter - 2).toSignedShort() == cpu.registers.programCounter) {
                cpu.idle = true
            }
        } else {
            cpu.registers.programCounter++  // Skip the offset byte
            cpu.workCyclesLeft = 2
        }
    }
}

// ============================================================================
// Implied family — Flag ops, Transfer, RegisterIncDec, AccShiftRotate, Push
// ============================================================================

/** Set/clear a single status flag. 2 cycles. Original Opcodes.kt:545-554, 559-564. */
class Flag(
    val setter: (Cpu) -> Unit,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        setter(cpu)
        cpu.workCyclesLeft = 2
    }
}

/**
 * Transfer between registers. TAX/TAY/TXA/TYA/TSX set Z and N flags based
 * on the byte being transferred. 2 cycles. Original Opcodes.kt:602-610.
 *
 * **TXS is separate** — see [TransferNoFlags] below. It does NOT set
 * flags (TXS is the only Transfer that doesn't).
 */
class Transfer(
    val from: (Cpu) -> Byte,
    val to: (Cpu, Byte) -> Unit,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        val value = from(cpu)
        cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        to(cpu, value)
        cpu.workCyclesLeft = 2
    }
}

/**
 * TXS — Transfer X to Stack Pointer. Does NOT set Z/N flags (the only
 * Transfer that doesn't). 2 cycles. Original Opcodes.kt:181-186.
 */
class TransferNoFlags(
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        cpu.registers.stackPointer = cpu.registers.indexX
        cpu.workCyclesLeft = 2
    }
}

/** INX / INY / DEX / DEY — register increment/decrement. 2 cycles.
 *  Original Opcodes.kt:274-311. */
class RegisterIncDec(
    val register: (Cpu) -> Byte,
    val setter: (Cpu, Byte) -> Unit,
    val delta: Int,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        val result = ((register(cpu).toUnsignedInt() + delta) and 0xFF).toSignedByte()
        setter(cpu, result)
        cpu.processorStatus.resolveZeroAndNegativeFlags(result)
        cpu.workCyclesLeft = 2
    }
}

/**
 * ASL/ROL/LSR/ROR on the accumulator (the A variants, 0x0A/0x2A/0x4A/0x6A).
 * 2 cycles each. Original Opcodes.kt:238-247, 333-341, 344-352, 355-364.
 */
class AccShiftRotate(
    val operation: (Cpu) -> Unit,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        operation(cpu)
        cpu.workCyclesLeft = 2
    }
}

/**
 * PHA / PHP — push a byte onto the stack. 3 cycles.
 * Original Opcodes.kt:552-557.
 */
class Push(
    val source: (Cpu) -> Byte,
    override val mnemonic: String,
) : Opcode(cycles = 3) {
    override fun evaluate(cpu: Cpu) {
        cpu.push(source(cpu))
        cpu.workCyclesLeft = 3
    }
}

// ============================================================================
// Implied — NOP, BRK
// ============================================================================

/** Official NOP (0xEA) — implied, 2 cycles. Original Opcodes.kt:233-235. */
class NopImplied : Opcode(cycles = 2) {
    override val mnemonic = "NOP"
    override fun evaluate(cpu: Cpu) {
        cpu.workCyclesLeft = 2
    }
}

/**
 * BRK (0x00) — Force Break. Pushes PC+2 (past the padding byte), pushes
 * status WITH B flag set, loads IRQ vector, sets I flag. 7 cycles.
 * Original Opcodes.kt:192-206.
 *
 * Note: BRK duplicates most of `Cpu.dispatchInterrupt` (issue #190) but
 * with two differences — it pushes status WITH the B flag set (the
 * BRK/IRQ discriminator) and increments PC past BRK's padding byte
 * before pushing. Deduplicating would require parameterising both,
 * which adds more complexity than the ~10 lines save.
 */
class Break : Opcode(cycles = 7) {
    override val mnemonic = "BRK"
    override fun evaluate(cpu: Cpu) {
        cpu.processorStatus.breakCommand = true
        cpu.registers.programCounter++
        cpu.registers.programCounter.toUnsignedInt().apply {
            cpu.push((this shr 8).toSignedByte())
            cpu.push((this and 0xFF).toSignedByte())
        }
        cpu.push(cpu.processorStatus.asByte())
        cpu.registers.programCounter = cpu.memory[0xFFFE, 0xFFFF]
        cpu.processorStatus.interruptDisable = true
        cpu.workCyclesLeft = 7
    }
}