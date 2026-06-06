package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests that the PPU's frame-completion hooks are multicast (issue #123). The renderer
 * and the movie replayer are both consumers of the same per-frame boundary signal, and
 * neither can afford to displace the other.
 *
 * The single-slot behaviour was the cause of the original pain point: registering a
 * movie latch hook would have silently replaced the renderer's hook. The fix is a
 * list, with a `removeXxx` companion to detach a specific instance.
 */
class PpuMulticastListenerTest {

    private fun newPpu(): Ppu = Ppu(Memory()).apply { region = Region.NTSC }

    @Test
    fun `multiple frame listeners all fire at end of frame`() {
        val ppu = newPpu()
        var aCount = 0
        var bCount = 0
        val a = object : FrameListener { override fun frameUpdated(frame: Frame) { aCount++ } }
        val b = object : FrameListener { override fun frameUpdated(frame: Frame) { bCount++ } }

        ppu.addFrameListener(a)
        ppu.addFrameListener(b)
        driveOneFrame(ppu)

        assertEquals(1, aCount, "first listener fired")
        assertEquals(1, bCount, "second listener fired (multicast, not replace)")
    }

    @Test
    fun `multiple frame completion listeners all fire at end of frame`() {
        val ppu = newPpu()
        var aCount = 0
        var bCount = 0
        val a: () -> Unit = { aCount++ }
        val b: () -> Unit = { bCount++ }

        ppu.addFrameCompletionListener(a)
        ppu.addFrameCompletionListener(b)
        driveOneFrame(ppu)

        assertEquals(1, aCount, "first completion listener fired")
        assertEquals(1, bCount, "second completion listener fired")
    }

    @Test
    fun `removeFrameListener detaches only the named instance`() {
        val ppu = newPpu()
        var aCount = 0
        var bCount = 0
        val a = object : FrameListener { override fun frameUpdated(frame: Frame) { aCount++ } }
        val b = object : FrameListener { override fun frameUpdated(frame: Frame) { bCount++ } }

        ppu.addFrameListener(a)
        ppu.addFrameListener(b)
        ppu.removeFrameListener(a)
        driveOneFrame(ppu)

        assertEquals(0, aCount, "removed listener does not fire")
        assertEquals(1, bCount, "surviving listener still fires")
    }

    @Test
    fun `removeFrameCompletionListener is idempotent`() {
        val ppu = newPpu()
        val a: () -> Unit = { }
        // Removing something that was never registered must not throw.
        ppu.removeFrameCompletionListener(a)
        ppu.removeFrameCompletionListener(a)
    }

    @Test
    fun `listeners fire in registration order`() {
        val ppu = newPpu()
        val order = mutableListOf<String>()
        val a = object : FrameListener { override fun frameUpdated(frame: Frame) { order.add("A") } }
        val b = object : FrameListener { override fun frameUpdated(frame: Frame) { order.add("B") } }
        val c = object : FrameListener { override fun frameUpdated(frame: Frame) { order.add("C") } }
        ppu.addFrameListener(a)
        ppu.addFrameListener(b)
        ppu.addFrameListener(c)
        driveOneFrame(ppu)
        assertEquals(listOf("A", "B", "C"), order, "listeners fire in registration order")
    }

    /**
     * Drive the PPU until a frame-end fires. We don't go through `Nestlin.stepCpuCycle`
     * here because the test is purely about the PPU's listener multicast — the CPU
     * doesn't need to do anything for a frame to complete.
     *
     * Math: 262 scanlines × (341 visible cycles + 1 endLine tick) = 89,604 ticks per
     * frame. 100,000 is safely past that.
     */
    private fun driveOneFrame(ppu: Ppu) {
        repeat(100_000) { ppu.tick() }
    }
}
