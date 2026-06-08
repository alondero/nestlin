package com.github.alondero.nestlin.gamepak

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression for the Klax "garbage below the split" glitch (mapper 64,
 * Tengen RAMBO-1).
 *
 * Klax builds a two-region attract screen: the NMI arms the scanline IRQ
 * with a small latch (0x3F) to fire once near the top of the picture, and
 * the IRQ handler then *disarms* by reloading a large latch (0xFE) so the
 * down-counter cannot reach zero again before vblank — exactly one IRQ per
 * frame.
 *
 * The original [ScanlineCounter] fired whenever the counter was zero,
 * INCLUDING when a reload set it to zero. A disarm latch of 0xFE reloads to
 * (0xFE + 2) & 0xFF = 0x00, which the old code mistook for "counter reached
 * zero" and raised a spurious *second* IRQ. That extra IRQ ran Klax's split
 * state machine an extra step, corrupting every CHR bank below the split.
 *
 * Per Mesen2's `Rambo1.h`, the RAMBO-1 IRQ fires ONLY when the counter is
 * decremented to zero — never when a reload sets it to zero. These tests
 * pin that down.
 */
class Mapper64IrqDisarmTest {

    private fun mapper64(): Mapper64 {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = 4                               // 8 x 8KB PRG
        header[5] = 4                               // 32 x 1KB CHR
        header[6] = ((64 and 0x0F) shl 4).toByte()
        header[7] = (64 and 0xF0).toByte()
        val prg = ByteArray(8 * 0x2000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = ByteArray(32 * 0x400) { ((it / 0x400) and 0xFF).toByte() }
        return Mapper64(GamePak(header + prg + chr))
    }

    /** Arm latch=N (A12 mode), enable, clock A12 edges until the IRQ fires;
     *  return how many edges that took. */
    private fun armAndCountToFire(m: Mapper64, latch: Int): Int {
        m.cpuWrite(0xC000, latch.toByte())   // latch
        m.cpuWrite(0xC001, 0x00)             // reload, mode 0 = A12/scanline
        m.cpuWrite(0xE001, 0x00)             // enable
        var edges = 0
        while (!m.isIrqPending() && edges < 1000) {
            m.notifyA12Edge(true)
            edges++
        }
        return edges
    }

    @Test
    fun `disarm latch 0xFE does not raise a spurious second irq`() {
        val m = mapper64()

        // 1) Arm the split like Klax's NMI: latch=0x3F, fires once.
        val edgesToFirstFire = armAndCountToFire(m, 0x3F)
        assertThat("split IRQ should fire once armed", m.isIrqPending(), equalTo(true))

        // 2) Klax's IRQ handler disarms: disable (ack), reload, latch=0xFE,
        //    re-enable. The large latch must prevent any further fire this
        //    frame.
        m.cpuWrite(0xE000, 0x00)   // disable -> clears pending
        m.acknowledgeIrq()
        m.cpuWrite(0xC001, 0x00)   // reload
        m.cpuWrite(0xC000, 0xFE.toByte())  // disarm latch
        m.cpuWrite(0xE001, 0x00)   // enable
        assertThat("pending cleared after disable+ack", m.isIrqPending(), equalTo(false))

        // 3) Clock a full picture's worth of A12 edges (240 scanlines). With
        //    the bug, the very first edge reloads 0xFE+2 -> 0x00 and fires.
        //    Correct RAMBO-1 never fires here (counter can't count down to 0
        //    in time).
        for (i in 0 until 240) {
            m.notifyA12Edge(true)
            assertThat(
                "disarm latch 0xFE must not fire within 240 scanlines (edge $i)",
                m.isIrqPending(), equalTo(false)
            )
        }

        // Sanity: the first fire really did take ~latch+1 edges, not 0.
        assertThat(edgesToFirstFire > 1, equalTo(true))
    }

    @Test
    fun `reload to zero never fires on its own - only decrement to zero does`() {
        val m = mapper64()
        // latch large enough that reload alone could wrap to 0 (0xFE -> +2 -> 0)
        m.cpuWrite(0xC000, 0xFE.toByte())
        m.cpuWrite(0xC001, 0x00)   // reload (mode 0)
        m.cpuWrite(0xE001, 0x00)   // enable
        // First edge performs the reload (counter = 0xFE+2 = 0x00). Must NOT fire.
        m.notifyA12Edge(true)
        assertThat("reload-to-zero must not raise IRQ", m.isIrqPending(), equalTo(false))
    }
}
