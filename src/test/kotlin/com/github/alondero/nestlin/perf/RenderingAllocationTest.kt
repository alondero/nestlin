package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        assumeTrue(bean?.isThreadAllocatedMemorySupported == true, "JVM cannot measure thread allocations")
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val emu = RenderingWorkload.create(region)
        repeat(150) {
            RenderingWorkload.runFrame(emu)
            emu.getAudioSamples()
        }
        val threadId = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(30) { RenderingWorkload.runFrame(emu) }
        val bytesPerFrame = (bean.getThreadAllocatedBytes(threadId) - before) / 30
        assertTrue(bytesPerFrame < 256 * 1024, "$region allocated $bytesPerFrame bytes/frame; budget is 256 KiB")
    }
}
