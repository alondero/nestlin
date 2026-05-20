package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class PpuSpriteXPositionTest {

    @Test
    fun `sprite renders at OAM X coordinate, not one pixel to the left`() {
        val memory = Memory()
        val ppu = Ppu(memory)

        val chr = ByteArray(0x2000)
        chr[0x0000] = 0xFF.toSignedByte()
        chr[0x0008] = 0x00.toSignedByte()
        memory.ppuAddressedMemory.ppuInternalMemory.loadChrRom(chr)

        memory.ppuAddressedMemory.ppuInternalMemory[0x3F11] = 0x30.toSignedByte()

        // OAM Y semantics: sprite at Y=99 renders on scanline 100.
        with(memory.ppuAddressedMemory.objectAttributeMemory) {
            this[0] = 99.toSignedByte()
            this[1] = 0x00.toSignedByte()
            this[2] = 0x00.toSignedByte()
            this[3] = 100.toSignedByte()
        }

        memory.ppuAddressedMemory.mask.register = 0b00010000

        var captured: Frame? = null
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                if (captured == null) captured = frame
            }
        })

        // One NTSC frame is 89342 PPU cycles; cap at 2x to fail fast if frame completion regresses.
        val maxTicks = 2 * 89342
        var ticks = 0
        while (captured == null && ticks < maxTicks) {
            ppu.tick()
            ticks++
        }
        val frame = requireNotNull(captured) { "PPU did not complete a frame within $maxTicks ticks" }

        val whiteRgb = NesPalette.getRgb(0x30)
        val firstWhiteColumn = frame.scanlines[100].indexOfFirst { it == whiteRgb }

        assertThat(firstWhiteColumn, equalTo(100))
    }
}
