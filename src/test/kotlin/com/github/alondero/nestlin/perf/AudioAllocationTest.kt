package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

@Tag("performance")
class AudioAllocationTest {
    @Test
    fun `warmed reusable drains do not allocate on nonempty or empty polls`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().threadId()
        val apu = AudioWorkload.create(Region.NTSC)
        val output = ShortArray(8192)
        var bytes = 0L
        repeat(6000) { iteration ->
            repeat(3000) { apu.tick() }
            val before = bean.getThreadAllocatedBytes(id)
            apu.getAudioSamples(output)
            apu.getAudioSamples(output)
            if (iteration >= 1000) bytes += bean.getThreadAllocatedBytes(id) - before
        }
        assertTrue(bytes < 4096, "Reusable drains allocated $bytes bytes across 10000 polls")
    }
}
