package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Characterization of the list-based PPU at 4b426fa56213aeec178affb62a096904a5068056.
 * The golden columns are state, timed bus reads, timed A12 edges, per-dot status,
 * and both complete RGB frames. Regenerating them from the optimized PPU would
 * discard the independent baseline this test is intended to preserve.
 */
class PpuSpriteScratchTest {
    @ParameterizedTest
    @CsvSource(
        "empty, 0, 8, 0, 30",
        "single, 1, 8, 0, 30",
        "eight, 8, 8, 0, 30",
        "overflow, 9, 8, 0, 30",
        "rotated, 9, 8, 241, 30",
        "tall, 8, 40, 0, 30",
        "tall-rotated, 9, 40, 241, 30",
        "clipped, 8, 8, 0, 24",
        "sprites-only, 8, 8, 0, 20",
        "background-only, 8, 8, 0, 10"
    )
    fun `state bus status and pixels match the original implementation`(
        name: String, count: Int, control: Int, oamAddress: Int, mask: Int
    ) {
        val fixture = Fixture(count, control, oamAddress, mask)
        val state = MessageDigest.getInstance("SHA-256")
        val status = MessageDigest.getInstance("SHA-256")
        val pixels = MessageDigest.getInstance("SHA-256")
        repeat(2) {
            do {
                // Change every primary-OAM byte AFTER evaluation. Selected slots must
                // keep their old Y/tile/attributes/X until the next evaluation.
                if (fixture.ppu.currentCycle == 65) fixture.overwriteOam()
                if (fixture.ppu.currentCycle == 0) fixture.seedOam()
                fixture.ppu.tick()
                status.update(fixture.memory.ppuAddressedMemory.status.register)
                if (fixture.ppu.currentCycle in STATE_DOTS) state.update(fixture.save())
            } while (!fixture.ppu.frameJustCompleted())
            for (row in fixture.ppu.publishedFrame.scanlines) for (rgb in row) {
                pixels.putInt(rgb)
            }
        }
        val actual = listOf(state, fixture.bus, fixture.edges, status, pixels)
            .joinToString(" ") { HexFormat.of().formatHex(it.digest()) }
        assertEquals(GOLDENS.getValue(name), actual)
    }

    @ParameterizedTest
    @CsvSource(
        "8, 0", "8, 65", "8, 258", "8, 259", "8, 260", "8, 287", "8, 288", "8, 341", "8, 450", "8, 610",
        "40, 0", "40, 65", "40, 259", "40, 287", "40, 341", "40, 450", "40, 610"
    )
    fun `mid scanline load resumes the same snapshots latches bus and pixels`(control: Int, dot: Int) {
        val original = Fixture(9, control, 241, 30)
        repeat(37 * 341 + dot) { original.ppu.tick() }
        val saved = original.save()
        val restored = Fixture(8, 8, 0, 24)
        // Populate both halves of the reusable buffers before loading a different count.
        repeat(40 * 341 + 280) { restored.ppu.tick() }
        restored.ppu.loadState(DataInputStream(ByteArrayInputStream(saved)))
        assertArrayEquals(saved, restored.save(), "save format must round-trip byte for byte")
        original.overwriteOam()
        restored.overwriteOam()
        original.resetTrace()
        restored.resetTrace()
        repeat(3 * 341) {
            original.ppu.tick()
            restored.ppu.tick()
            assertArrayEquals(original.save(), restored.save(), "state at resumed tick $it")
        }
        assertArrayEquals(original.bus.digest(), restored.bus.digest(), "resumed fetch sequence")
        assertArrayEquals(original.edges.digest(), restored.edges.digest(), "resumed A12 edges")
        // Loading does not restore the framebuffer. After two frame completions
        // both published frames have been drawn entirely from the resumed state.
        repeat(2) {
            do {
                original.ppu.tick()
                restored.ppu.tick()
                assertEquals(original.ppu.currentCycle, restored.ppu.currentCycle)
            } while (!original.ppu.frameJustCompleted())
            assertEquals(true, restored.ppu.frameJustCompleted())
        }
        for (y in 0 until 240) {
            assertArrayEquals(original.ppu.publishedFrame.scanlines[y], restored.ppu.publishedFrame.scanlines[y])
        }
    }

    @ParameterizedTest
    @CsvSource("0, -1", "0, 9", "1, -1", "1, 9", "2, -1", "2, 9")
    fun `load rejects counts outside the bounded sprite buffers`(buffer: Int, count: Int) {
        val fixture = Fixture(0, 8, 0, 30)
        val saved = fixture.save()
        // A cold PPU ends with three empty count-prefixed sprite collections.
        ByteBuffer.wrap(saved).putInt(saved.size - 12 + buffer * 4, count)
        assertThrowsWithMessage<SaveState.IncompatibleSaveStateException>("Invalid PPU sprite count") {
            fixture.ppu.loadState(DataInputStream(ByteArrayInputStream(saved)))
        }
    }

    private class Fixture(val count: Int, control: Int, val oamAddress: Int, mask: Int) {
        val memory = Memory()
        val ppu = Ppu(memory)
        var bus = MessageDigest.getInstance("SHA-256")
        var edges = MessageDigest.getInstance("SHA-256")
        private val chr = ByteArray(0x2000) { ((it * 73) xor (it shr 3) xor 0xA5).toByte() }

        init {
            ppu.region = Region.NTSC
            val regs = memory.ppuAddressedMemory
            val mem = regs.ppuInternalMemory
            for (i in 0 until 0x1000) mem[0x2000 + i] = (i * 13).toByte()
            for (i in 0 until 32) mem[0x3F00 + i] = (i * 7).toByte()
            mem.chrReadDelegate = PpuInternalMemory.ChrRead { address ->
                trace(bus, address)
                chr[address]
            }
            mem.nametableReadDelegate = PpuInternalMemory.NametableRead { address ->
                trace(bus, address)
                null
            }
            mem.a12EdgeListener = { rising -> if (rising) trace(edges, 0x1000) }
            mem.resetA12State()
            regs.controller.register = control.toByte()
            regs.mask.register = mask.toByte()
            seedOam()
        }

        fun resetTrace() {
            bus = MessageDigest.getInstance("SHA-256")
            edges = MessageDigest.getInstance("SHA-256")
        }

        private fun trace(digest: MessageDigest, address: Int) {
            digest.putInt(ppu.currentScanline)
            digest.putInt(ppu.currentCycle)
            digest.putInt(address)
        }

        fun seedOam() {
            val regs = memory.ppuAddressedMemory
            regs.oamAddress = oamAddress.toByte()
            val start = oamAddress ushr 2
            for (i in 0 until 64) regs.objectAttributeMemory[i * 4] = 0xF0.toByte()
            for (n in 0 until count) {
                val i = (start + n) and 63
                val base = i * 4
                regs.objectAttributeMemory[base] = 37
                regs.objectAttributeMemory[base + 1] = (n * 19 + 3).toByte()
                // Alternate both flips, priority and palettes. The rotated case wraps to 0.
                regs.objectAttributeMemory[base + 2] = (n * 37).toByte()
                regs.objectAttributeMemory[base + 3] = intArrayOf(0, 3, 7, 8, 8, 10, 254, 255, 20)[n].toByte()
            }
        }

        fun overwriteOam() {
            val oam = memory.ppuAddressedMemory.objectAttributeMemory
            for (i in 0 until 256) oam[i] = (i * 17 + 91).toByte()
        }

        fun save(): ByteArray = ByteArrayOutputStream().also {
            ppu.saveState(DataOutputStream(it))
        }.toByteArray()
    }

    companion object {
        private val STATE_DOTS = setOf(0, 64, 65, 258, 259, 260, 287, 288, 320)
        private val GOLDENS = requireNotNull(PpuSpriteScratchTest::class.java.getResourceAsStream("/ppu-sprite-scratch.sha256"))
            .bufferedReader().useLines { lines ->
                lines.associate { line -> line.substringBefore(' ') to line.substringAfter(' ') }
            }

        private fun MessageDigest.putInt(value: Int) {
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }
    }
}
