package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioBufferTest {

    @Test
    fun basicWriteRead() {
        val buffer = AudioBuffer(bufferSize = 100)

        buffer.write(100.toShort())
        buffer.write(200.toShort())

        val output = ShortArray(10)
        val read = buffer.read(output, 2)

        assertThat(read, equalTo(2))
        assertThat(output[0], equalTo(100.toShort()))
        assertThat(output[1], equalTo(200.toShort()))
    }

    @Test
    fun availableSamplesUpdates() {
        val buffer = AudioBuffer(bufferSize = 100)

        assertThat(buffer.availableSamples(), equalTo(0))

        buffer.write(1.toShort())
        assertThat(buffer.availableSamples(), equalTo(1))

        buffer.write(2.toShort())
        assertThat(buffer.availableSamples(), equalTo(2))

        val output = ShortArray(10)
        buffer.read(output, 1)
        assertThat(buffer.availableSamples(), equalTo(1))
    }

    @Test
    fun bufferOverrunDropsOldestSample() {
        val buffer = AudioBuffer(bufferSize = 4)

        // Fill buffer: [1, 2, 3, 4]
        buffer.write(1.toShort())
        buffer.write(2.toShort())
        buffer.write(3.toShort())
        buffer.write(4.toShort())
        assertThat(buffer.availableSamples(), equalTo(4))

        // Add 5 - should drop oldest (1)
        buffer.write(5.toShort())
        assertThat(buffer.availableSamples(), equalTo(4))

        val output = ShortArray(4)
        buffer.read(output, 4)

        // Should read 2, 3, 4, 5 (1 was dropped)
        assertThat(output[0], equalTo(2.toShort()))
        assertThat(output[1], equalTo(3.toShort()))
        assertThat(output[2], equalTo(4.toShort()))
        assertThat(output[3], equalTo(5.toShort()))
    }

    @Test
    fun bufferUnderrunReturnsLessThanRequested() {
        val buffer = AudioBuffer(bufferSize = 100)

        buffer.write(1.toShort())
        buffer.write(2.toShort())

        val output = ShortArray(10)
        val read = buffer.read(output, 5)  // Request 5, only 2 available

        assertThat(read, equalTo(2))
        assertThat(output[0], equalTo(1.toShort()))
        assertThat(output[1], equalTo(2.toShort()))
        assertThat(buffer.availableSamples(), equalTo(0))
    }

    @Test
    fun bufferClearResetsState() {
        val buffer = AudioBuffer(bufferSize = 100)

        buffer.write(1.toShort())
        buffer.write(2.toShort())
        assertThat(buffer.availableSamples(), equalTo(2))

        buffer.clear()

        assertThat(buffer.availableSamples(), equalTo(0))
        val output = ShortArray(10)
        val read = buffer.read(output, 10)
        assertThat(read, equalTo(0))
    }

    @Test
    fun wrapAroundWriteRead() {
        val buffer = AudioBuffer(bufferSize = 8)

        // Write 8 samples
        for (i in 0 until 8) {
            buffer.write(i.toShort())
        }

        // Read 4 samples (positions wrap around)
        val output1 = ShortArray(4)
        buffer.read(output1, 4)
        assertThat(output1[0], equalTo(0.toShort()))
        assertThat(output1[1], equalTo(1.toShort()))
        assertThat(output1[2], equalTo(2.toShort()))
        assertThat(output1[3], equalTo(3.toShort()))

        // Write 4 more samples
        for (i in 0 until 4) {
            buffer.write((10 + i).toShort())
        }

        // Read all 8 - should get 4,5,6,7,10,11,12,13
        val output2 = ShortArray(8)
        buffer.read(output2, 8)
        assertThat(output2[0], equalTo(4.toShort()))
        assertThat(output2[1], equalTo(5.toShort()))
        assertThat(output2[2], equalTo(6.toShort()))
        assertThat(output2[3], equalTo(7.toShort()))
        assertThat(output2[4], equalTo(10.toShort()))
        assertThat(output2[5], equalTo(11.toShort()))
        assertThat(output2[6], equalTo(12.toShort()))
        assertThat(output2[7], equalTo(13.toShort()))
    }

    @Test
    fun concurrentWriteRead() {
        val buffer = AudioBuffer(bufferSize = 1000)
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(2)
        val samplesWritten = AtomicInteger(0)
        val samplesRead = AtomicInteger(0)

        // Writer thread
        executor.submit {
            for (i in 0 until 500) {
                buffer.write(i.toShort())
                samplesWritten.incrementAndGet()
            }
            latch.countDown()
        }

        // Reader thread
        executor.submit {
            var totalRead = 0
            while (totalRead < 500) {
                val output = ShortArray(10)
                val read = buffer.read(output, 10)
                totalRead += read
            }
            samplesRead.set(totalRead)
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(samplesWritten.get(), equalTo(500))
        assertThat(samplesRead.get(), equalTo(500))
    }
}
