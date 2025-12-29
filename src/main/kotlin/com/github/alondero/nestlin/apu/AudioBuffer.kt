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

    fun read(output: ShortArray, length: Int): Int {
        lock.lock()
        try {
            val toRead = minOf(length, available)
            for (i in 0 until toRead) {
                output[i] = buffer[readPos]
                readPos = (readPos + 1) % buffer.size
            }
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
