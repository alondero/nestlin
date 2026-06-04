package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 71 (Camerica / Codemasters, BF909x).
 *
 * Test PRG/CHR are stamped with their bank index so a read asserts exactly
 * which bank is mapped into a window.
 */
class Mapper71Test {

    private fun buildIne(
        prg: ByteArray,
        chr: ByteArray,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): ByteArray {
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            // mapper 71 = 0x47 -> low nibble 7, high nibble 4
            this[6] = ((71 and 0x0F) shl 4 or when (mirroring) {
                Header.Mirroring.HORIZONTAL -> 0x00
                Header.Mirroring.VERTICAL -> 0x01
            }).toByte()
            this[7] = (71 and 0xF0).toByte()
        }
        return header + prg + chr
    }

    private fun makeStampedPrg(banks16k: Int): ByteArray {
        val prg = ByteArray(banks16k * 0x4000)
        for (bank in 0 until banks16k) {
            prg[bank * 0x4000] = bank.toByte()
            prg[bank * 0x4000 + 0x3FFF] = (bank xor 0xFF).toByte()
        }
        return prg
    }

    private fun makeStampedChr(banks8k: Int): ByteArray {
        val chr = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            chr[bank * 0x2000] = bank.toByte()
            chr[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    private fun newMapper71(
        prgBanks: Int = 4,
        chrBanks: Int = 1,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): Mapper71 {
        val prg = makeStampedPrg(prgBanks)
        val chr = makeStampedChr(chrBanks)
        val gp = GamePak(buildIne(prg, chr, mirroring))
        return gp.createMapper() as Mapper71
    }

    @Test
    fun `mapper71 is selected for header mapper 71`() {
        assertThat(newMapper71() is Mapper71, equalTo(true))
    }

    // ---- PRG banking (default / BF9096 mode) ----

    @Test
    fun `defaults to PRG bank 0 in the 8000 window and last bank in the C000 window`() {
        // 4 PRG banks -> last = 3
        val m = newMapper71(prgBanks = 4)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(3 xor 0xFF))
    }

    @Test
    fun `single 16KB PRG ROM is mirrored in both windows`() {
        // 1 PRG bank: window A and window B both look at bank 0.
        val m = newMapper71(prgBanks = 1)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        m.cpuWrite(0x8000, 0xFF.toSignedByte())     // 255 % 1 = 0
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `write to 8000 in default mode selects PRG bank`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x8000, 2.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(2 xor 0xFF))
        // $C000-$FFFF stays fixed to last
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `write to C000 in default mode also selects PRG bank`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0xC000, 1.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `write to FFFF in default mode selects PRG bank`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0xFFFF, 2.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `all 16KB PRG banks are reachable`() {
        val m = newMapper71(prgBanks = 8)
        for (bank in 0..7) {
            m.cpuWrite(0xC000, bank.toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `oversized bank number wraps modulo PRG count`() {
        // 4 banks: any value V%4 should be the effective bank.
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x8000, 5.toSignedByte())      // 5 % 4 = 1
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        m.cpuWrite(0x8000, 0xFF.toSignedByte())    // 255 % 4 = 3
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    // ---- CHR ----

    @Test
    fun `CHR is fixed - a single 8KB page mapped across the whole CHR window`() {
        val m = newMapper71(chrBanks = 4)         // 32 KB CHR
        // No bank-select write, so CHR is the first 8KB regardless of PRG bank.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        // $1000 is still inside bank 0 (the stamped byte is 0 there).
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
        // CHR is fixed even after a PRG bank change.
        m.cpuWrite(0x8000, 3.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `missing CHR ROM falls back to 8KB CHR RAM`() {
        val prg = makeStampedPrg(2)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = 2.toByte()
            this[5] = 0.toByte()                   // 0 KB CHR
            this[6] = ((71 and 0x0F) shl 4).toByte()
            this[7] = (71 and 0xF0).toByte()
        }
        val gp = GamePak(header + prg)
        val m = gp.createMapper() as Mapper71
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x0100, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x0100).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- Mirroring (default = from header) ----

    @Test
    fun `default mirroring follows iNES header (horizontal)`() {
        val m = newMapper71(mirroring = Header.Mirroring.HORIZONTAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `default mirroring follows iNES header (vertical)`() {
        val m = newMapper71(mirroring = Header.Mirroring.VERTICAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    // ---- Firehawk (BF9097) mode ----

    @Test
    fun `writing 9000 latches firehawk mode`() {
        val m = newMapper71()
        m.cpuWrite(0x9000, 0x00.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.registers["bf9097Mode"], equalTo(1))
    }

    @Test
    fun `firehawk write to 9000 does not change PRG bank`() {
        // The latch write must not be mistaken for a bank-select write.
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x9000, 0x42.toSignedByte())   // any value is fine
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))   // still bank 0
    }

    @Test
    fun `in firehawk mode 8000 writes set mirroring not PRG bank`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x9000, 0x00.toSignedByte())   // latch firehawk
        m.cpuWrite(0x8000, 0x10.toSignedByte())   // bit 4 set -> upper screen
        // PRG bank is still 0.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        // Mirroring has switched to upper.
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `firehawk mirroring bit 4 controls lower vs upper`() {
        val m = newMapper71()
        m.cpuWrite(0x9000, 0x00.toSignedByte())   // latch firehawk
        m.cpuWrite(0x8000, 0x00.toSignedByte())   // bit 4 clear -> lower
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        m.cpuWrite(0xA000, 0x10.toSignedByte())   // any $8000-$BFFF write, bit 4 set -> upper
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `firehawk 8000 mirroring write ignores other bits`() {
        val m = newMapper71()
        m.cpuWrite(0x9000, 0x00.toSignedByte())
        m.cpuWrite(0x8000, 0xEF.toSignedByte())   // bit 4 clear, others garbage
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        m.cpuWrite(0x8000, 0x1F.toSignedByte())   // bit 4 set, others garbage
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `in firehawk mode C000 writes still select PRG bank`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x9000, 0x00.toSignedByte())   // latch firehawk
        m.cpuWrite(0xC000, 2.toSignedByte())      // bank select
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(3))   // still fixed to last
    }

    @Test
    fun `firehawk is one-way - mirroring control stays on after latch`() {
        val m = newMapper71()
        m.cpuWrite(0x9000, 0x00.toSignedByte())
        m.cpuWrite(0x8000, 0x10.toSignedByte())   // -> upper
        // Many cycles later, no $9000 reset is required.
        m.cpuWrite(0x8000, 0x00.toSignedByte())   // -> lower
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    // ---- Save/load state ----

    @Test
    fun `saveState then loadState round-trips PRG bank and firehawk state`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x9000, 0x00.toSignedByte())   // latch firehawk
        m.cpuWrite(0xC000, 2.toSignedByte())      // bank 2
        m.cpuWrite(0x8000, 0x10.toSignedByte())   // upper screen

        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newMapper71(prgBanks = 4)
        // Sanity: the fresh mapper is in its defaults.
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }

        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(fresh.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `saveState round-trips CHR RAM when present`() {
        val prg = makeStampedPrg(2)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = 2.toByte()
            this[5] = 0.toByte()
            this[6] = ((71 and 0x0F) shl 4).toByte()
            this[7] = (71 and 0xF0).toByte()
        }
        val m = GamePak(header + prg).createMapper() as Mapper71
        m.ppuWrite(0x0123, 0x77.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = GamePak(header + prg).createMapper() as Mapper71
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.ppuRead(0x0123).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reflects current state`() {
        val m = newMapper71(prgBanks = 4)
        m.cpuWrite(0x9000, 0x00.toSignedByte())
        m.cpuWrite(0xC000, 3.toSignedByte())
        m.cpuWrite(0x8000, 0x10.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(71))
        assertThat(snap.banks["prgBank"], equalTo(3))
        assertThat(snap.registers["bf9097Mode"], equalTo(1))
        assertThat(snap.registers["firehawkMirrorUpper"], equalTo(1))
    }

    @Test
    fun `snapshot reports header mirroring when firehawk is not latched`() {
        val m = newMapper71(mirroring = Header.Mirroring.VERTICAL)
        val snap = m.snapshot()
        assertThat(snap.registers["bf9097Mode"], equalTo(0))
        // -1 = mirroring still driven by header
        assertThat(snap.registers["firehawkMirrorUpper"], equalTo(-1))
    }
}
