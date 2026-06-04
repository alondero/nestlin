package com.github.alondero.nestlin

/**
 * Extension functions for working with unsigned bytes/shorts as their JVM-signed
 * counterparts.
 *
 * These existed before Kotlin's stdlib unsigned types (UByte, UShort, UInt)
 * landed in 1.3, and a full migration of every call site to those types would
 * force the entire memory-bus / PPU / CPU-byte plumbing onto the unsigned
 * hierarchy (Byte is not interchangeable with UByte in Kotlin). That surgery
 * is real but out of scope for an emulator where the rest of the code uses
 * `Byte` for byte storage and `and 0xFF` is the conventional widening.
 *
 * What this file does instead is re-implement the conversions on top of the
 * stdlib unsigned types, so the source of truth is `toUByte()` / `toUShort()` /
 * `toUInt()` rather than hand-rolled `if (x < 0) x + 256 else x.toInt()`.
 *
 * The wide byte/short conversions go through `toUByte()`/`toUShort()` because
 * the wrapping semantics are exactly what stdlib defines. The bit-manipulation
 * helpers (`isBitSet`, `setBit`, `clearBit`, `toggleBit`) go through `toUInt()`
 * rather than `UByte` so we allocate a single UInt per call (vs three UByte
 * objects) — these helpers are called per CPU instruction (CMP/ASL/LSR/ROL/ROR
 * carry/negative decode, ~1.79 MHz NTSC) and the UByte form cost a measurable
 * extra allocation per call.
 *
 * If/when the byte plumbing is migrated wholesale to UByte, this file can be
 * deleted; the call sites that need the bit-manipulation helpers can take
 * the same operators directly on UByte.
 */

/** Wrap an Int (assumed in 0..0xFF or 0..0xFFFF) into a signed [Byte]. */
fun Int.toSignedByte(): Byte = toUByte().toByte()

/** Promote a signed [Byte] to a non-negative [Int] in 0..0xFF. */
fun Byte.toUnsignedInt(): Int = toUByte().toInt()

/** Wrap an Int (assumed in 0..0xFFFF) into a signed [Short]. */
fun Int.toSignedShort(): Short = toUShort().toShort()

/** Promote a signed [Short] to a non-negative [Int] in 0..0xFFFF. */
fun Short.toUnsignedInt(): Int = toUShort().toInt()

fun Short.toHexString() = "%04X".format(toUnsignedInt()).uppercase()
fun Byte.toHexString() = "%02X".format(toUnsignedInt()).uppercase()
fun Int.toHexString() = "%02X".format(this).uppercase()

// The bit-manipulation helpers route through UInt (via toUInt()) rather than
// UByte. toUInt() is the stdlib API specifically called out in the migration
// issue, and using UInt instead of UByte cuts the per-call allocation count
// from 3 to 1 — meaningful for code on the per-CPU-instruction hot path.
fun Byte.isBitSet(i: Int): Boolean = toUInt() and (1u shl i) != 0u
fun Int.isBitSet(i: Int): Boolean = this and (1 shl i) != 0

fun Byte.setBit(i: Int): Byte = (toUInt() or (1u shl i)).toInt().toSignedByte()
fun Byte.clearBit(i: Int): Byte = (toUInt() and (1u shl i).inv()).toInt().toSignedByte()
fun Byte.toggleBit(i: Int): Byte = (toUInt() xor (1u shl i)).toInt().toSignedByte()
fun Byte.letBit(i: Int, on: Boolean): Byte = if (on) setBit(i) else clearBit(i)

/** Arithmetic shift right that preserves the high bit (signed shift). */
fun Byte.shiftRight(): Byte = ((toInt() shr 1) and 0x7F).toSignedByte()
