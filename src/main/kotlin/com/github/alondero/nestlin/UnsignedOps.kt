package com.github.alondero.nestlin

/**
 * Extension functions required to handle dealing with signed Bytes and Shorts
 */
fun Int.toSignedByte(): Byte {
    if (this > 127) return (this-256).toByte()
    else return this.toByte()
}

fun Byte.toUnsignedInt(): Int {
    if (this < 0) return this+256
    else return this.toInt()
}

fun Int.toSignedShort(): Short {
    if (this > 32767) return (this-65536).toShort()
    else return this.toShort()
}

fun Short.toUnsignedInt(): Int {
    if (this < 0) return this+65536
    else return this.toInt()
}

fun Short.toHexString(): String = Integer.toHexString(this.toUnsignedInt()).toUpperCase()
fun Byte.toHexString(): String = Integer.toHexString(this.toUnsignedInt()).toUpperCase()
fun Int.toHexString(): String = Integer.toHexString(this).toUpperCase()

fun Byte.isBitSet(i: Int): Boolean = (this.toUnsignedInt() shr i and 1) == 1

