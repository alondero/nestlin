package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 228 (Action 52 / Active Enterprises).
 *
 * The defining trait of this board is that the *address* carries the bank
 * selection and the written *value* contributes only 2 bits. The ROM is built
 * at the real cart's geometry — 96 × 16KB PRG (1.5MB) and 64 × 8KB CHR (512KB)
 * — with every PRG/CHR bank's first byte stamped with its own index, so a
 * single read of a window's base address asserts exactly which bank is mapped
 * there. Built via [testGamePak]/TestRomBuilder (raw hand-built iNES headers
 * are forbidden — see HeaderConstructionLintTest).
 */
class Mapper228Test {

    /** Real Action 52 geometry: chip 0/1/3 packed contiguous → 96 PRG banks. */
    private fun newMapper(): Mapper228 {
        val gamePak = testGamePak {
            mapper = 228
            prgKb = 96 * 16          // 96 × 16KB = 1.5MB
            chrKb = 64 * 8           // 64 × 8KB  = 512KB
            stampPrgBanks(windowKb = 16)
            stampChrBanks(windowKb = 8)
        }
        return gamePak.createMapper() as Mapper228
    }

    /**
     * Builds a register-write address from the fields the board decodes. A15 is
     * always set so the write lands in $8000-$FFFF; A14, A4 are don't-cares.
     */
    private fun regAddr(
        chip: Int,
        page: Int,
        mode32: Boolean,
        chrHigh: Int = 0,
        horiz: Boolean = false,
    ): Int {
        var a = 0x8000
        a = a or ((chip and 0x03) shl 11)
        a = a or ((page and 0x1F) shl 6)
        if (mode32) a = a or 0x20
        a = a or (chrHigh and 0x0F)
        if (horiz) a = a or 0x2000
        return a
    }

    private fun Mapper228.prgAt(window: Int) =
        cpuRead(if (window == 0) 0x8000 else 0xC000).toUnsignedInt()

    private fun Mapper228.chrBankByte() = ppuRead(0x0000).toUnsignedInt()

    // ---- Dispatch ----

    @Test
    fun `createMapper returns Mapper228 for iNES mapper 228`() {
        val m = newMapper()
        assertThat(m.snapshot().mapperId, equalTo(228))
        assertThat(m.snapshot().type, equalTo("Action 52 / Active Enterprises"))
    }

    // ---- Initial state (Mesen InitMapper: WriteRegister($8000,0)) ----

    @Test
    fun `initial state maps PRG bank 0 low and bank 1 high (split mode)`() {
        val m = newMapper()
        assertThat(m.prgAt(0), equalTo(0))   // $8000-$BFFF
        assertThat(m.prgAt(1), equalTo(1))   // $C000-$FFFF
    }

    @Test
    fun `initial CHR bank is 0`() {
        assertThat(newMapper().chrBankByte(), equalTo(0))
    }

    @Test
    fun `initial mirroring is vertical`() {
        assertThat(newMapper().currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    // ---- PRG banking: address-only, 32KB mirror mode (bit 5 set) ----

    @Test
    fun `32KB mode maps the same page into both windows for every chip and page`() {
        val m = newMapper()
        for (chip in intArrayOf(0, 1, 3)) {
            for (page in 0..31) {
                m.cpuWrite(regAddr(chip, page, mode32 = true), 0)
                val chipIndex = if (chip == 3) 2 else chip
                val expected = page or (chipIndex shl 5)   // 0..95
                assertThat("chip $chip page $page window0", m.prgAt(0), equalTo(expected))
                assertThat("chip $chip page $page window1", m.prgAt(1), equalTo(expected))
            }
        }
    }

    // ---- PRG banking: adjacent-16KB-pair mode (bit 5 clear) ----

    @Test
    fun `pair mode maps even page low and odd page high for every chip and page`() {
        val m = newMapper()
        for (chip in intArrayOf(0, 1, 3)) {
            for (page in 0..31) {
                m.cpuWrite(regAddr(chip, page, mode32 = false), 0)
                val chipIndex = if (chip == 3) 2 else chip
                val prgPage = page or (chipIndex shl 5)
                assertThat("chip $chip page $page window0", m.prgAt(0), equalTo(prgPage and 0xFE))
                assertThat("chip $chip page $page window1", m.prgAt(1), equalTo((prgPage and 0xFE) + 1))
            }
        }
    }

    @Test
    fun `chip 3 reaches the third 512KB chip (PRG banks 64-95)`() {
        val m = newMapper()
        // chip 3, top page, 32KB mode → bank 95 (last bank of the third chip).
        m.cpuWrite(regAddr(chip = 3, page = 31, mode32 = true), 0)
        assertThat(m.prgAt(0), equalTo(95))
        assertThat(m.prgAt(1), equalTo(95))
    }

    // ---- Chip 2 (physically absent) → open bus ----

    @Test
    fun `selecting chip 2 makes PRG reads return the data bus (open bus)`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 2, page = 0, mode32 = true), 0)
        m.dataBus = 0x5A.toByte()
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0x5A))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0x5A))
        m.dataBus = 0xA5.toByte()
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(0xA5))
    }

    @Test
    fun `selecting a real chip after chip 2 clears the open-bus state`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 2, page = 5, mode32 = true), 0)
        m.cpuWrite(regAddr(chip = 1, page = 5, mode32 = true), 0)  // chip 1 → bank 37
        m.dataBus = 0x5A.toByte()
        assertThat(m.prgAt(0), equalTo((1 shl 5) or 5))            // 37, not open bus
    }

    @Test
    fun `reads below 0x8000 return the data bus (no PRG-RAM)`() {
        val m = newMapper()
        m.dataBus = 0x3C.toByte()
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0x3C))
        assertThat(m.cpuRead(0x4020).toUnsignedInt(), equalTo(0x3C))
    }

    // ---- CHR banking: 4 bits from address + 2 bits from value ----

    @Test
    fun `CHR bank combines addr A0-A3 high nibble and value bits 0-1`() {
        val m = newMapper()
        for (chrHigh in 0..15) {
            for (low in 0..3) {
                m.cpuWrite(regAddr(chip = 0, page = 0, mode32 = true, chrHigh = chrHigh), low.toByte())
                val expected = (chrHigh shl 2) or low   // 0..63
                assertThat("chrHigh $chrHigh low $low", m.chrBankByte(), equalTo(expected))
            }
        }
    }

    @Test
    fun `only value bits 0-1 affect the CHR bank — bits 2-7 are discarded`() {
        val m = newMapper()
        val addr = regAddr(chip = 0, page = 0, mode32 = true, chrHigh = 0x5)  // high nibble = 5
        // 0x02 and 0xFE share bits 0-1 == 0b10; the high bits of 0xFE must not leak.
        m.cpuWrite(addr, 0x02.toByte())
        val withClean = m.chrBankByte()
        m.cpuWrite(addr, 0xFE.toByte())
        val withNoise = m.chrBankByte()
        assertThat(withNoise, equalTo(withClean))
        assertThat(withClean, equalTo((0x5 shl 2) or 0x2))  // 0x16
    }

    // ---- Mirroring: address bit 13 ----

    @Test
    fun `addr bit 13 set selects horizontal mirroring`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 0, page = 0, mode32 = true, horiz = true), 0)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `addr bit 13 clear selects vertical mirroring`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 0, page = 0, mode32 = true, horiz = true), 0)   // flip to H
        m.cpuWrite(regAddr(chip = 0, page = 0, mode32 = true, horiz = false), 0)  // back to V
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    // ---- Writes below $8000 are ignored ----

    @Test
    fun `writes below 0x8000 do not change banking`() {
        val m = newMapper()
        m.cpuWrite(0x6000, 0xFF.toByte())
        m.cpuWrite(0x4020, 0xFF.toByte())
        assertThat(m.prgAt(0), equalTo(0))   // still the init state
        assertThat(m.prgAt(1), equalTo(1))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips banking and mirroring`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 3, page = 12, mode32 = false, chrHigh = 0xA, horiz = true), 0x03.toByte())
        val prg0 = m.prgAt(0); val prg1 = m.prgAt(1)
        val chr = m.chrBankByte(); val mirror = m.currentMirroring()

        val buf = ByteArrayOutputStream()
        m.saveState(DataOutputStream(buf))

        val restored = newMapper()
        restored.loadState(DataInputStream(ByteArrayInputStream(buf.toByteArray())))

        assertThat(restored.prgAt(0), equalTo(prg0))
        assertThat(restored.prgAt(1), equalTo(prg1))
        assertThat(restored.chrBankByte(), equalTo(chr))
        assertThat(restored.currentMirroring(), equalTo(mirror))
    }

    @Test
    fun `open-bus state survives save and load round-trip`() {
        val m = newMapper()
        m.cpuWrite(regAddr(chip = 2, page = 0, mode32 = true), 0)

        val buf = ByteArrayOutputStream()
        m.saveState(DataOutputStream(buf))
        val restored = newMapper()
        restored.loadState(DataInputStream(ByteArrayInputStream(buf.toByteArray())))

        restored.dataBus = 0x77.toByte()
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(0x77))
    }
}
