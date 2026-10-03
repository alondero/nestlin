package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.lang.management.ManagementFactory

@Tag("performance")
class SpriteScratchAllocationTest {
    @ParameterizedTest
    @CsvSource("NTSC, 16", "PAL, 16", "NTSC, 40", "PAL, 40")
    fun `sprite rendering reuses bounded scratch storage`(region: Region, control: Int) {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
            ?: error("Sprite allocation tests require com.sun.management.ThreadMXBean")
        check(bean.isThreadAllocatedMemorySupported) { "JVM thread allocation measurement is required" }
        bean.isThreadAllocatedMemoryEnabled = true
        val emu = RenderingWorkload.create(region)
        emu.cpu.memory.ppuAddressedMemory.controller.register = control.toByte()
        val ppu = emu.ppu
        fun runFrame() {
            var remaining = 120_000
            do {
                check(remaining-- > 0) { "PPU did not complete a frame" }
                ppu.tick()
            } while (!ppu.frameJustCompleted())
        }
        repeat(300) { runFrame() }
        val threadId = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(30) { runFrame() }
        val bytesPerFrame = (bean.getThreadAllocatedBytes(threadId) - before) / 30
        // Includes frame-listener housekeeping. One per-sprite object or scanline
        // copy exceeds this budget for the fixture's 512 (8x8) / 1024 (8x16) selections.
        assertTrue(bytesPerFrame < 1024, "$region control=$control allocated $bytesPerFrame PPU bytes/frame")
    }
}
