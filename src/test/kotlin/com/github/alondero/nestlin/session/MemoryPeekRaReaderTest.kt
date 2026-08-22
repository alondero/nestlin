package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Memory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Bounds-safe + side-effect-free tests for [peekReader] (issue #270 AC).
 *
 * The reader wraps `Memory.peek` with three defensive guards (negative /
 * out-of-range address, non-positive count, over-sized buffer). These
 * tests pin the bounds contract; the side-effect-free guarantee is
 * already pinned by [com.github.alondero.nestlin.MemoryPeekTest] at the
 * Memory layer.
 */
class MemoryPeekRaReaderTest {

    @Test
    fun `peek returns the value at a known RAM address`() {
        val (memory, _) = Memory.createWithApu()
        memory[0x0042] = 0x99.toByte()
        val reader = peekReader(memory)
        val buf = ByteArray(1)
        val n = reader.read(0x0042, buf, 1)
        assertEquals(1, n)
        assertEquals(0x99.toByte(), buf[0])
    }

    @Test
    fun `peek returns 0 for negative address without crashing`() {
        val (memory, _) = Memory.createWithApu()
        val reader = peekReader(memory)
        val buf = ByteArray(1)
        val n = reader.read(-1, buf, 1)
        assertEquals(0, n)
        assertEquals(0.toByte(), buf[0])
    }

    @Test
    fun `peek returns 0 for address above 0xFFFF without crashing`() {
        val (memory, _) = Memory.createWithApu()
        val reader = peekReader(memory)
        val buf = ByteArray(1)
        val n = reader.read(0x10000, buf, 1)
        assertEquals(0, n)
        assertEquals(0.toByte(), buf[0])
    }

    @Test
    fun `peek returns 0 for zero-byte read request`() {
        val (memory, _) = Memory.createWithApu()
        memory[0x0010] = 0x42.toByte()
        val reader = peekReader(memory)
        val buf = ByteArray(1)
        val n = reader.read(0x0010, buf, 0)
        assertEquals(0, n)
        assertEquals(0.toByte(), buf[0])
    }

    @Test
    fun `peek returns 0 for negative read count`() {
        val (memory, _) = Memory.createWithApu()
        memory[0x0010] = 0x42.toByte()
        val reader = peekReader(memory)
        val buf = ByteArray(1)
        val n = reader.read(0x0010, buf, -5)
        assertEquals(0, n)
        assertEquals(0.toByte(), buf[0])
    }

    @Test
    fun `peek clamps over-sized read to buffer size`() {
        val (memory, _) = Memory.createWithApu()
        memory[0x0010] = 0x42.toByte()
        memory[0x0011] = 0x43.toByte()
        memory[0x0012] = 0x44.toByte()
        val reader = peekReader(memory)
        val buf = ByteArray(3)
        val n = reader.read(0x0010, buf, 10)   // request 10, buf is 3
        assertEquals(3, n)
        assertEquals(0x42.toByte(), buf[0])
        assertEquals(0x43.toByte(), buf[1])
        assertEquals(0x44.toByte(), buf[2])
    }

    @Test
    fun `peek multi-byte reads the right consecutive addresses`() {
        val (memory, _) = Memory.createWithApu()
        for (i in 0 until 4) memory[0x0200 + i] = (0xA0 + i).toByte()
        val reader = peekReader(memory)
        val buf = ByteArray(4)
        val n = reader.read(0x0200, buf, 4)
        assertEquals(4, n)
        assertEquals(0xA0.toByte(), buf[0])
        assertEquals(0xA1.toByte(), buf[1])
        assertEquals(0xA2.toByte(), buf[2])
        assertEquals(0xA3.toByte(), buf[3])
    }
}