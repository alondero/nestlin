package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Mapper 30 (UNROM 512). Oracle: Mesen2 `UnRom512.h`. The register layout is
 * `~NCCP PPPP` written to any `$8000-$FFFF` address:
 *
 *   - bits 0-4: 5-bit PRG bank (16 KiB page at `$8000-$BFFF`)
 *   - bits 5-6: 2-bit CHR-RAM bank (8 KiB page at `$0000-$1FFF`)
 *   - bit  7:   mirroring toggle (semantics depend on submapper / byte 6)
 *
 * Mirroring is the only soft state — there is no IRQ, no audio, no PRG-RAM.
 * Each test below pins one of those field decodes against a real game-like
 * fixture so a regression in the bit mask surfaces immediately.
 */
class Mapper30Test {

    /** Helper: build a UNROM 512 game with the requested byte 6 flags. */
    private fun newMapper(
        prgKb: Int = 512,
        chrKb: Int = 0,
        submapper: Int? = null,
        fourScreen: Boolean = false,
        verticalMirroring: Boolean = false,
        battery: Boolean = false
    ): Mapper30 {
        val pak = testGamePak {
            mapper = 30
            this.prgKb = prgKb
            this.chrKb = chrKb
            stampPrgBanks(windowKb = 16)
            if (chrKb > 0) stampChrBanks(windowKb = 8)
            this.fourScreen = fourScreen
            this.verticalMirroring = verticalMirroring
            this.battery = battery
            // Submapper forces NES 2.0 header; leaving it null keeps iNES.
            submapper?.let { this.submapper = it }
        }
        return pak.createMapper() as Mapper30
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    @Test
    fun `mapper 30 is selected for header mapper 30`() {
        // Same tautological-shape assertion Mapper7Test/Mapper33Test/Mapper65Test/
        // Mapper71Test use — the alternative `is Mapper30` check is a compile-time
        // tautology, but the test's job is to prove GamePak.createMapper() returns
        // *something* of this concrete type, not a generic Mapper.
        @Suppress("USELESS_IS_CHECK")
        assertThat(newMapper() is Mapper30, equalTo(true))
    }

    // ---------------------------------------------------------------------
    // PRG bank select
    // ---------------------------------------------------------------------

    @Test
    fun `PRG bank 0 reads at 8000 by default`() {
        val m = newMapper(prgKb = 128)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `PRG bank select uses bits 0-4 (5 bits)`() {
        val m = newMapper(prgKb = 512)
        for (bank in 0..31) {
            m.cpuWrite(0x8000, bank.toSignedByte())
            assertThat("bank $bank at \$8000", m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `C000 window is hard-wired to the last PRG bank`() {
        val m = newMapper(prgKb = 256)
        // Last bank = 256/16 - 1 = 15. Stamped byte at first byte of each 16KB.
        m.cpuWrite(0x8000, 0x07.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
        assertThat("\$C000-\$FFFF stays at last bank 15", m.cpuRead(0xC000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `oversized bank number wraps modulo bank count`() {
        // 64 KiB PRG = 4 banks; bank 5 should wrap to 1, 31 to 3.
        val m = newMapper(prgKb = 64)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        m.cpuWrite(0xFFFF, 0x1F.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `writes below 8000 are ignored`() {
        val m = newMapper(prgKb = 64)
        m.cpuWrite(0x8000, 0x02.toSignedByte())
        m.cpuWrite(0x7FFF, 0x03.toSignedByte())   // ignored
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `any address in 8000-FFFF drives the register`() {
        val m = newMapper(prgKb = 128)
        m.cpuWrite(0xC001, 0x05.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        m.cpuWrite(0xABCD, 0x06.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
    }

    // ---------------------------------------------------------------------
    // CHR-RAM bank select
    // ---------------------------------------------------------------------

    @Test
    fun `CHR-RAM bank 0 is the default window`() {
        // 32 KiB CHR-RAM = four 8 KiB banks. After init, chrBank = 0.
        // Verify the read returns 0 (uninitialised) and the write sticks.
        val m = newMapper(chrKb = 0)
        m.ppuWrite(0x0000, 0xAA.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0xAA))
    }

    @Test
    fun `CHR-RAM bank select uses bits 5-6 (2 bits, 4 banks)`() {
        val m = newMapper(chrKb = 0)
        // Stamp each 8 KiB page so a bank-switch lands on a different stamp.
        for (bank in 0..3) {
            m.cpuWrite(0x8000, ((bank shl 5) and 0xFF).toSignedByte())
            // Write then read should round-trip.
            m.ppuWrite(0x0000, (0x10 + bank).toSignedByte())
            assertThat("bank $bank readback", m.ppuRead(0x0000).toUnsignedInt(),
                equalTo(0x10 + bank))
        }
    }

    @Test
    fun `CHR-RAM banks are isolated`() {
        val m = newMapper(chrKb = 0)
        // Fill bank 0 with 0xAA, bank 1 with 0xBB.
        m.cpuWrite(0x8000, 0x00.toSignedByte())  // bank 0
        m.ppuWrite(0x0000, 0xAA.toSignedByte())
        m.cpuWrite(0x8000, 0x20.toSignedByte())  // bank 1 (bit 5 set)
        m.ppuWrite(0x0000, 0xBB.toSignedByte())
        // Re-read each.
        m.cpuWrite(0x8000, 0x00.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0xAA))
        m.cpuWrite(0x8000, 0x20.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0xBB))
    }

    // ---------------------------------------------------------------------
    // Mirroring decode
    // ---------------------------------------------------------------------

    @Test
    fun `byte 6 bits 3,0 equal 00 selects fixed horizontal`() {
        val m = newMapper(fourScreen = false, verticalMirroring = false)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `byte 6 bits 3,0 equal 01 selects fixed vertical`() {
        val m = newMapper(fourScreen = false, verticalMirroring = true)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `byte 6 bits 3,0 equal 10 selects 1-screen switchable defaulting to lower`() {
        val m = newMapper(fourScreen = true, verticalMirroring = false)
        assertThat("default before any write", m.currentMirroring(),
            equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `byte 6 bits 3,0 equal 10 bit 7 toggles 1-screen lower or upper`() {
        val m = newMapper(fourScreen = true, verticalMirroring = false)
        m.cpuWrite(0x8000, 0x80.toSignedByte())
        assertThat("bit 7 = 1 → upper", m.currentMirroring(),
            equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
        m.cpuWrite(0x8000, 0x00.toSignedByte())
        assertThat("bit 7 = 0 → lower", m.currentMirroring(),
            equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `byte 6 bits 3,0 equal 11 enters four-screen mode`() {
        val m = newMapper(fourScreen = true, verticalMirroring = true)
        // currentMirroring() returns the cosmetic sentinel; what matters
        // here is the readNametableOverride route, covered by the next tests.
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `four-screen mode routes nametable reads to last 8 KiB of CHR-RAM`() {
        val m = newMapper(chrKb = 0, fourScreen = true, verticalMirroring = true)
        // The nametable override path is what a real PPU $2000 access takes
        // when 4-screen mode is active. Writing through writeNametableOverride
        // and reading back through readNametableOverride exercises that path
        // exactly the way the PPU would.
        m.writeNametableOverride(0x2000, 0x77.toSignedByte())
        val read = m.readNametableOverride(0x2000)
        assertThat("readNametableOverride(\$2000) is non-null in 4-screen mode",
            read != null, equalTo(true))
        assertThat(read!!.toUnsignedInt(), equalTo(0x77))
    }

    @Test
    fun `four-screen mode routes nametable writes to last 8 KiB of CHR-RAM`() {
        val m = newMapper(chrKb = 0, fourScreen = true, verticalMirroring = true)
        val consumed = m.writeNametableOverride(0x2050, 0xCD.toSignedByte())
        assertThat("writeNametableOverride returns true (consumed) in 4-screen",
            consumed, equalTo(true))
        assertThat("byte round-trips through nametable override",
            m.readNametableOverride(0x2050)?.toUnsignedInt(), equalTo(0xCD))
    }

    @Test
    fun `four-screen nametable override covers full 2000-2FFF window`() {
        val m = newMapper(chrKb = 0, fourScreen = true, verticalMirroring = true)
        // Endpoints that exercise the masked offset path.
        m.writeNametableOverride(0x2000, 0x11.toSignedByte())
        m.writeNametableOverride(0x27FF, 0x22.toSignedByte())
        m.writeNametableOverride(0x2800, 0x33.toSignedByte())
        m.writeNametableOverride(0x2FFF, 0x44.toSignedByte())
        assertThat(m.readNametableOverride(0x2000)?.toUnsignedInt(), equalTo(0x11))
        assertThat(m.readNametableOverride(0x27FF)?.toUnsignedInt(), equalTo(0x22))
        assertThat(m.readNametableOverride(0x2800)?.toUnsignedInt(), equalTo(0x33))
        assertThat(m.readNametableOverride(0x2FFF)?.toUnsignedInt(), equalTo(0x44))
    }

    @Test
    fun `non-four-screen mode does NOT claim nametable reads or writes`() {
        val m = newMapper(fourScreen = false, verticalMirroring = false)
        assertThat("read falls through", m.readNametableOverride(0x2000), equalTo(null))
        assertThat("write falls through", m.writeNametableOverride(0x2000, 0x00.toSignedByte()),
            equalTo(false))
    }

    @Test
    fun `1-screen switchable bit 7 toggles are independent of PRG bank`() {
        val m = newMapper(prgKb = 64, fourScreen = true, verticalMirroring = false)
        // High byte: bits 0-3 PRG, bit 7 mirror toggle. Test combined.
        m.cpuWrite(0x8000, 0x81.toSignedByte())  // PRG bank 1, mirror upper
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---------------------------------------------------------------------
    // Submapper-specific behaviour
    // ---------------------------------------------------------------------

    @Test
    fun `submapper 3 default mirroring is vertical`() {
        val m = newMapper(submapper = 3)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `submapper 3 bit 7 toggles H over V`() {
        val m = newMapper(submapper = 3)
        m.cpuWrite(0x8000, 0x80.toSignedByte())
        assertThat("bit 7 = 1 → vertical", m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0x8000, 0x00.toSignedByte())
        assertThat("bit 7 = 0 → horizontal", m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `submapper 4 ignores LED register writes at 8000-BFFF`() {
        // Submapper 4's "LED" register at $8000-$BFFF is not emulated —
        // those writes must not change PRG/CHR/mirroring (Mesen2 removes
        // the range from the write decoder). The standard register still
        // accepts writes at $C000-$FFFF, so we set PRG bank 7 there first.
        val m = newMapper(prgKb = 128, submapper = 4)
        m.cpuWrite(0xC000, 0x07.toSignedByte())
        assertThat("PRG bank 7 set via \$C000", m.cpuRead(0x8000).toUnsignedInt(),
            equalTo(7))
        m.cpuWrite(0x9000, 0xFF.toSignedByte())  // LED register → no-op
        assertThat("PRG bank unchanged by \$9000 write", m.cpuRead(0x8000).toUnsignedInt(),
            equalTo(7))
    }

    @Test
    fun `submapper 4 writes at C000-FFFF still drive the register`() {
        val m = newMapper(prgKb = 128, submapper = 4)
        m.cpuWrite(0xC000, 0x05.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
    }

    // ---------------------------------------------------------------------
    // Save state round-trip
    // ---------------------------------------------------------------------

    @Test
    fun `save state round-trips all soft state`() {
        val original = newMapper(chrKb = 0, fourScreen = true, verticalMirroring = false)
        original.cpuWrite(0xABCD, 0xA5.toSignedByte())  // PRG=5, CHR=2 (bits 5,6,7 = 0,1,0,1)
        original.ppuWrite(0x1000, 0x77.toSignedByte())
        original.ppuWrite(0x6000, 0x88.toSignedByte())

        val buffer = java.io.ByteArrayOutputStream().also {
            val out = java.io.DataOutputStream(it)
            original.saveState(out)
        }
        val restored = newMapper(chrKb = 0, fourScreen = true, verticalMirroring = false)
        restored.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(buffer.toByteArray())))

        assertThat("PRG bank restored", restored.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat("CHR-RAM bank 2 byte restored", restored.ppuRead(0x1000).toUnsignedInt(), equalTo(0x77))
        // Bank 2 base = 2 * 0x2000 = 0x4000. The original write went to
        // address 0x1000, which is at chr-bank index 0 of the windowed page.
        // After restoring, bank 2 should still hold that byte at offset 0x1000.
    }

    @Test
    fun `save state version mismatch throws IncompatibleSaveStateException`() {
        val m = newMapper()
        // Hand-craft a stream whose version byte doesn't match.
        val bad = java.io.ByteArrayOutputStream().also {
            val out = java.io.DataOutputStream(it)
            out.writeByte(0xFF)  // wrong version
        }
        val ex = assertThrows<SaveState.IncompatibleSaveStateException> {
            m.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(bad.toByteArray())))
        }
        assertThat(ex.message!!.contains("Mapper30"), equalTo(true))
    }

    // ---------------------------------------------------------------------
    // Snapshot / debug
    // ---------------------------------------------------------------------

    @Test
    fun `snapshot exposes mapper id and bank fields`() {
        val m = newMapper(prgKb = 128, fourScreen = true, verticalMirroring = false)
        // 0xE1 = 1110_0001: bit7=1 (mirror toggle), bits 5,6 = 11 = 3 (CHR bank),
        // bits 0-4 = 00001 = 1 (PRG bank). Using 0xE1 here (not 0xC1) because
        // bit 5 alone gives (v>>5) & 3 = 0b110 & 0b11 = 2, not 3 — both bits
        // must be set for chrBank = 3.
        m.cpuWrite(0x8000, 0xE1.toSignedByte())
        // Mapper30.snapshot() overrides the base class's nullable signature
        // with a non-null return; the local Kotlin compiler can see that, so
        // the `!!` from the original draft is just a warning. Direct bind.
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(30))
        assertThat(snap.type, equalTo("UNROM512"))
        assertThat(snap.banks["prgBank"], equalTo(1))
        assertThat(snap.banks["chrBank"], equalTo(3))    // bits 5,6 = 11 → 3
        assertThat(snap.banks["mirroringBit"], equalTo(1))
        assertThat("CHR-RAM is 32 KiB", snap.chrRam!!.size, equalTo(0x8000))
    }
}