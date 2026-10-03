package com.github.alondero.nestlin.apu

import java.util.concurrent.locks.ReentrantLock

class AudioBuffer(val sampleRate: Int = 44100, bufferSize: Int = 4096) {
    private val buffer = ShortArray(bufferSize)
    private var writePos = 0
    private var readPos = 0
    private var available = 0
    private val lock = ReentrantLock()

    fun write(sample: Short) {
        lock.lock()
        try {
            if (available < buffer.size) {
                buffer[writePos] = sample
                writePos = (writePos + 1) % buffer.size
                available++
            } else {
                // Buffer overrun - drop oldest sample
                readPos = (readPos + 1) % buffer.size
                buffer[writePos] = sample
                writePos = (writePos + 1) % buffer.size
            }
        } finally {
            lock.unlock()
        }
    }

    /** Drain into caller-owned storage, returning the valid prefix length under one lock. */
    fun read(output: ShortArray, length: Int = output.size): Int {
        require(length >= 0)
        lock.lock()
        try {
            val toRead = minOf(length, output.size, available)
            if (toRead == 0) return 0
            val first = minOf(toRead, buffer.size - readPos)
            buffer.copyInto(output, 0, readPos, readPos + first)
            if (first < toRead) buffer.copyInto(output, first, 0, toRead - first)
            // Subtract instead of masking: configured capacities need not be powers of two.
            // This form also avoids overflowing readPos + toRead for very large arrays.
            readPos = if (toRead >= buffer.size - readPos) toRead - (buffer.size - readPos) else readPos + toRead
            available -= toRead
            return toRead
        } finally {
            lock.unlock()
        }
    }

    fun availableSamples(): Int {
        lock.lock()
        try {
            return available
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            readPos = 0
            writePos = 0
            available = 0
        } finally {
            lock.unlock()
        }
    }
}
