package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.management.ManagementFactory

@Tag("performance")
class RenderingAllocationTest {
    @ParameterizedTest
    @EnumSource(Region::class)
    fun `warmed rendering stays below the allocation budget`(region: Region) {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
            ?: error("Performance allocation tests require com.sun.management.ThreadMXBean")
        check(bean.isThreadAllocatedMemorySupported) {
            "Performance allocation tests require JVM thread allocation measurement"
        }
        bean.isThreadAllocatedMemoryEnabled = true
        val emu = RenderingWorkload.create(region)
        repeat(150) {
            RenderingWorkload.runFrame(emu)
            emu.getAudioSamples()
        }
        val threadId = Thread.currentThread().threadId()
        var allocatedBytes = 0L
        repeat(30) {
            val before = bean.getThreadAllocatedBytes(threadId)
            RenderingWorkload.runFrame(emu)
            allocatedBytes += bean.getThreadAllocatedBytes(threadId) - before
            emu.getAudioSamples()
        }
        val bytesPerFrame = allocatedBytes / 30
        assertTrue(bytesPerFrame < 256 * 1024, "$region allocated $bytesPerFrame bytes/frame; budget is 256 KiB")
    }
}
