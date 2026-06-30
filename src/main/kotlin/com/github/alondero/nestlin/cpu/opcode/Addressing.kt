package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Sealed addressing-mode hierarchy (issue #192).
 *
 * Replaces the 90-line `AddressingMode.kt` that exposed eight
 * `(Cpu) -> Byte` / `(Cpu) -> Int` builder functions. Each addressing mode
 * is now a subclass with two methods:
 *
 * - [value] — read the operand byte at the address implied by the
 *   addressing mode. Used by Load / Store / Arithmetic / Logic / Compare /
 *   Bit / unofficial opcodes that READ memory.
 * - [address] — compute the address itself. Used by Read-Modify-Write
 *   opcodes (shift/rotate on memory, INC/DEC on memory) that need both
 *   the value (for the operation) and the address (to write the result
 *   back).
 *
 * **Stateless invariant.** Like [Opcode] instances, Addressing instances
 * are stateless across ticks. They only describe HOW to fetch/write, not
 * WHEN. Cycle counts are NOT here — they live on the Opcode subclass
 * itself, since they depend on the addressing mode + opcode family
 * combination.
 *
 * **Index-register shifts.** For Absolute/ZeroPage variants, the
 * `x`/`y` flag selects which index register (X or Y) is added to the base
 * address. Cross-page +1 cycle accounting is intentionally out of scope
 * here — see issue #172.
 */
sealed class Addressing {
    /** Read the operand byte for this addressing mode. */
    abstract fun value(cpu: Cpu): Byte

    /** Compute the effective address for this addressing mode. Used by
     *  Read-Modify-Write opcodes; throws for [Immediate] which has no
     *  address.
     *
     *  NOTE: implementations MUST consume the operand bytes from PC
     *  exactly once. Calling [value] then [address] (or vice versa) will
     *  double-consume the operand and produce wrong results — subclasses
     *  expose [effectiveAddress] / [effectiveValue] helpers that compute
     *  the address once and then read memory at that address. */
    abstract fun address(cpu: Cpu): Int

    /** True if this addressing mode uses an index register (X or Y). */
    open val isIndexed: Boolean get() = false
}

/**
 * Immediate addressing: operand is the next byte at PC.
 * Mode: `$xx`  Cycles: 2  Reads: 1 byte  No address (not RMW).
 */
object Immediate : Addressing() {
    override fun value(cpu: Cpu): Byte = cpu.readByteAtPC()
    override fun address(cpu: Cpu): Int =
        throw UnsupportedOperationException("Immediate has no address")
}

/**
 * Zero-page addressing: operand is an 8-bit address in `$00-$FF`.
 * Optionally indexed by X or Y (X or Y is added to the base with
 * zero-page wrap).
 * Cycles: zp=3, zp,X=4, zp,Y=4.
 */
data class ZeroPage(val x: Boolean = false, val y: Boolean = false) : Addressing() {
    override val isIndexed: Boolean get() = x || y

    override fun value(cpu: Cpu): Byte = cpu.memory[address(cpu)]
    override fun address(cpu: Cpu): Int {
        val base = cpu.readByteAtPC().toUnsignedInt()
        return if (x) (base + cpu.registers.indexX.toUnsignedInt()) and 0xFF
               else if (y) (base + cpu.registers.indexY.toUnsignedInt()) and 0xFF
               else base
    }
}

/**
 * Absolute addressing: operand is a 16-bit address following the opcode.
 * Optionally indexed by X or Y (added in 16-bit space, then masked to
 * 16 bits — real-6502 behaviour).
 * Cycles: abs=4, abs,X=4 (+1 on page cross), abs,Y=4 (+1 on page cross).
 * Page-cross +1 cycle is out of scope here (issue #172).
 *
 * **Issue #207 quirk fix.** The original `absolute()` (value-returning)
 * masked the indexed address to 16 bits, but `absoluteAdr()`
 * (address-returning) did not. Read-Modify-Write opcodes that needed
 * both the read value and the write address then relied on the unmasked
 * address silently exceeding $FFFF and being dropped by `Memory.get` /
 * `set` (out-of-range returns 0 / no-op). Real 6502 wraps to low
 * memory. Now [address] consistently masks to 16 bits, matching
 * hardware and aligning with [value].
 */
data class Absolute(val x: Boolean = false, val y: Boolean = false) : Addressing() {
    override val isIndexed: Boolean get() = x || y

    override fun value(cpu: Cpu): Byte = cpu.memory[address(cpu)]

    override fun address(cpu: Cpu): Int {
        val base = cpu.readShortAtPC().toUnsignedInt()
        val raw = if (x) base + cpu.registers.indexX.toUnsignedInt()
                  else if (y) base + cpu.registers.indexY.toUnsignedInt()
                  else base
        return raw and 0xFFFF
    }
}

/**
 * Indexed indirect X: operand is a zero-page address, indexed by X with
 * zero-page wrap; the 16-bit pointer at the wrapped address is the target.
 * Mode: `($xx,X)`  Cycles: 6.
 */
object IndirectX : Addressing() {
    override val isIndexed: Boolean get() = true

    /** Pointer + memory at that pointer, computed once. */
    override fun value(cpu: Cpu): Byte = cpu.memory[address(cpu)]
    override fun address(cpu: Cpu): Int {
        val zp = cpu.readByteAtPC().toUnsignedInt()
        val lo = cpu.memory[(zp + cpu.registers.indexX.toUnsignedInt()) and 0xFF]
        val hi = cpu.memory[(zp + cpu.registers.indexX.toUnsignedInt() + 1) and 0xFF]
        return lo.toUnsignedInt() or (hi.toUnsignedInt() shl 8)
    }
}

/**
 * Indirect indexed Y: 16-bit pointer at the operand zero-page address,
 * then add Y in 16-bit space.
 * Mode: `($xx),Y`  Cycles: 5 (+1 on page cross).
 */
object IndirectY : Addressing() {
    override val isIndexed: Boolean get() = true

    override fun value(cpu: Cpu): Byte = cpu.memory[address(cpu)]
    override fun address(cpu: Cpu): Int {
        val zp = cpu.readByteAtPC().toUnsignedInt()
        val lo = cpu.memory[zp].toUnsignedInt()
        val hi = cpu.memory[(zp + 1) and 0xFF].toUnsignedInt()
        val base = lo or (hi shl 8)
        return (base + cpu.registers.indexY.toUnsignedInt()) and 0xFFFF
    }
}