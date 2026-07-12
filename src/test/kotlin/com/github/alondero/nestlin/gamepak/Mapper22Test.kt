package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
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
 * Unit tests for Mapper 22 (Konami VRC2a).
 *
 * VRC2a is a stripped-down VRC4: same register address map, same address-pin
 * decode (the VRC4b / Mapper 25 swap), same 8KB PRG banking at $8000/$A000,
 * same 8×1KB CHR banking, but no IRQ counter, no $9002 swap mode, no
 * $6000-$7FFF WRAM, V/H-only mirroring (bit 1 ignored), and a CHR shift
 * quirk where the stored 9-bit CHR bank is right-shifted by 1 when selecting
 * the actual CHR page (Mesen2's `VRC2_4.h::UpdateState`).
 *
 * Test fixtures stamp every 8KB PRG bank with its bank index (byte 0) and
 * every 1KB CHR page with its page index, so a read asserts exactly which
 * bank is mapped into the window. The fixtures are built via [testGamePak]
 * (the project's lint-enforced path) rather than hand-built iNES headers
 * — see HeaderConstructionLintTest.
 */
class Mapper22Test {

    /** VRC2a's address-pin decode is the VRC4b / Mapper 25 swap. */
    private fun Mapper.vrc22write(group: Int, sub: Int, value: Int) {
        val low = (sub and 0x01) shl 1
        val high = (sub and 0x02) shr 1
        cpuWrite(group or low or high, value.toSignedByte())
    }

    private fun build(
        prgKb: Int = 64,
        chrKb: Int = 8,
        verticalMirroring: Boolean = true
    ): GamePak = testGamePak {
        mapper = 22
        this.prgKb = prgKb
        this.chrKb = chrKb
        this.verticalMirroring = verticalMirroring
        // Fill each 8KB PRG bank with its index byte, so any read inside the
        // window tells us which bank is mapped there. TestRomBuilder.stampPrgBanks
        // only stamps byte 0 of each window — not enough when a test asserts
        // the bank index at offsets deep inside the window (e.g. $9FFF).
        for (bank in 0 until prgKb / 8) {
            for (i in 0 until 0x2000) {
                prg[bank * 0x2000 + i] = (bank and 0xFF).toByte()
            }
        }
        if (chrKb > 0) {
            stampChrBanks(windowKb = 1)        // each 1KB CHR page stamped with its index
        }
    }

    // ---- Factory wiring ----

    @Test
    fun `mapper 22 is selected for header mapper 22`() {
        assertThat(build().createMapper() is Mapper22, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `E000-FFFF is fixed to the last 8KB bank`() {
        val mapper = Mapper22(build(prgKb = 64))   // 64KB PRG = 8 × 8KB banks, last = 7
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
        assertThat(mapper.cpuRead(0xFFFF).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `C000-DFFF is fixed to the second-to-last 8KB bank`() {
        val mapper = Mapper22(build(prgKb = 64))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
        assertThat(mapper.cpuRead(0xDFFF).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `default PRG banks at 8000 and A000 are bank 0`() {
        val mapper = Mapper22(build(prgKb = 32))   // 32KB PRG = 4 × 8KB banks
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x9FFF).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0xBFFF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8000 PRG select switches the 8KB window at 8000`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.vrc22write(0x8000, 0, 3)    // prg0 = bank 3
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0x9FFF).toUnsignedInt(), equalTo(3))
        // $C000-$FFFF still fixed.
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `A000 PRG select switches the 8KB window at A000`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.vrc22write(0xA000, 0, 5)    // prg1 = bank 5
        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(mapper.cpuRead(0xBFFF).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `8000 and A000 PRG selects are independent`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.vrc22write(0x8000, 0, 2)
        mapper.vrc22write(0xA000, 0, 4)
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `PRG bank register is 5 bits and masks to the low 5 bits`() {
        val mapper = Mapper22(build(prgKb = 64))
        // 5-bit field, so writing 0x21 masks to 0x01.
        mapper.vrc22write(0x8000, 0, 0x21)
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    // ---- No $9002 swap mode (VRC2 has no swap register) ----

    @Test
    fun `9002 swap-mode bit does NOT move prg0 to C000`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.vrc22write(0x8000, 0, 3)        // prg0 = bank 3
        mapper.vrc22write(0x9000, 2, 0x02)     // VRC4's swap-mode bit — must be a no-op for VRC2
        // prg0 still at $8000, not moved to $C000.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(6))   // second-to-last, not prg0
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `9002 WRAM-enable bit does NOT enable 6000 RAM`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.vrc22write(0x9000, 2, 0x01)     // VRC4's WRAM-enable bit — must be no-op for VRC2
        // $6000 should still be open-bus / dataBus, NOT a writable RAM page.
        mapper.dataBus = 0x00.toSignedByte()
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat("VRC2 has no WRAM at \$6000",
            mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    // ---- No IRQ (VRC2 has no $Fxxx registers) ----

    @Test
    fun `F000-F003 writes do not assert an IRQ even after many CPU cycles`() {
        val mapper = Mapper22(build(prgKb = 64))
        // Try every $Fxxx write a VRC4 game might issue.
        for (sub in 0..3) {
            mapper.vrc22write(0xF000, sub, 0xFF)
        }
        // Tick many CPU cycles — IRQ must never fire because VRC2 has no counter.
        repeat(10_000) { mapper.tickCpuCycle() }
        assertThat("VRC2 has no IRQ counter — isIrqPending must always be false",
            mapper.isIrqPending(), equalTo(false))
    }

    // ---- $6000-$7FFF (no WRAM) ----

    @Test
    fun `6000-7FFF reads return the open-bus dataBus value, not zero`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.dataBus = 0x48.toSignedByte()
        assertThat("VRC2 reads \$6000 as open bus",
            mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x48))
        assertThat("VRC2 reads \$7FFF as open bus",
            mapper.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0x48))
    }

    @Test
    fun `6000-7FFF writes are silently dropped and batteryBackedRam is null`() {
        val mapper = Mapper22(build(prgKb = 64))
        mapper.dataBus = 0x00.toSignedByte()
        mapper.cpuWrite(0x6000, 0xAA.toSignedByte())
        mapper.cpuWrite(0x7FFF, 0xBB.toSignedByte())
        assertThat("VRC2 has no battery-backed RAM chip",
            mapper.batteryBackedRam(), equalTo(null))
        assertThat(mapper.batteryDirty, equalTo(false))
    }

    // ---- Mirroring (V/H only; bit 1 ignored) ----

    @Test
    fun `9000 bit 0 selects Vertical or Horizontal mirroring`() {
        val mapper = Mapper22(build(prgKb = 32, verticalMirroring = false))
        mapper.vrc22write(0x9000, 0, 0x00)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.vrc22write(0x9000, 0, 0x01)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `9000 bit 1 is ignored - 1-screen-mode writes collapse to V or H`() {
        // The VRC4 silicon would decode bit 1 of $9000 as 1-screen-lower /
        // 1-screen-upper; the VRC2 silicon ties that bit to a no-op. So a
        // VRC2a game writing 0x02 (which would be 1-screen-lower on VRC4)
        // must read back as Vertical.
        val mapper = Mapper22(build(prgKb = 32, verticalMirroring = false))
        mapper.vrc22write(0x9000, 0, 0x02)   // would be 1-screen-lower on VRC4
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.vrc22write(0x9000, 0, 0x03)   // would be 1-screen-upper on VRC4
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring falls back to header when no 9000 write has happened`() {
        val v = Mapper22(build(prgKb = 32, verticalMirroring = true))
        assertThat(v.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        val h = Mapper22(build(prgKb = 32, verticalMirroring = false))
        assertThat(h.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    // ---- CHR banking + shift quirk ----

    @Test
    fun `each of the 8 CHR windows selects an independent 1KB bank`() {
        val mapper = Mapper22(build(prgKb = 32, chrKb = 64))   // 64 distinct 1KB pages
        // Stamp banks 8..15 into the 8 CHR windows.
        val groups = listOf(0xB000, 0xB000, 0xC000, 0xC000, 0xD000, 0xD000, 0xE000, 0xE000)
        for (window in 0..7) {
            val group = groups[window]
            val pairOffset = (window % 2) * 2
            val bankValue = window + 8
            mapper.vrc22write(group, pairOffset + 0, bankValue and 0x0F)
            mapper.vrc22write(group, pairOffset + 1, (bankValue shr 4) and 0x1F)
        }
        for (window in 0..7) {
            val addr = window * 0x400
            // VRC2a shift quirk: page index = stored 9-bit value >> 1.
            val expected = ((window + 8) shr 1) and 0xFF
            assertThat("window $window", mapper.ppuRead(addr).toUnsignedInt(), equalTo(expected))
        }
    }

    @Test
    fun `CHR high nibble adds bank bits 4-8 before the VRC2a right-shift`() {
        // With 32 1KB pages (4KB CHR), pick a stored 9-bit bank value >= 16
        // to exercise the high nibble. After the shift, page 0x15 = 21 -> 10.
        val mapper = Mapper22(build(prgKb = 32, chrKb = 32))
        mapper.vrc22write(0xB000, 0, 0x05)       // low nibble for chr0 = 5
        mapper.vrc22write(0xB000, 1, 0x01)       // high nibble for chr0 = 1 -> stored 0x15
        // Shift: 0x15 >> 1 = 0x0A. CHR byte[0] was stamped with its 1KB page index,
        // so ppuRead returns 0x0A.
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(0x0A))
    }

    @Test
    fun `CHR RAM is writable when chrRom is empty`() {
        val mapper = Mapper22(build(prgKb = 32, chrKb = 0))   // chrKb=0 means CHR-RAM
        mapper.ppuWrite(0x0000, 0x77.toSignedByte())
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips banking, mirroring, and CHR state`() {
        val original = Mapper22(build(prgKb = 64, chrKb = 16, verticalMirroring = false))
        original.vrc22write(0x8000, 0, 3)        // prg0 = 3
        original.vrc22write(0xA000, 0, 5)        // prg1 = 5
        original.vrc22write(0x9000, 0, 0x00)     // V mirroring
        original.vrc22write(0xB000, 0, 0x07)     // chr0 low nibble = 7
        original.vrc22write(0xB000, 1, 0x01)     // chr0 high nibble = 1 -> stored 0x17

        val bytes = ByteArrayOutputStream().also {
            original.saveState(DataOutputStream(it))
        }.toByteArray()

        val restored = Mapper22(build(prgKb = 64, chrKb = 16, verticalMirroring = false))
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(restored.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        // PPU read applies the shift quirk: 0x17 >> 1 = 0x0B.
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(0x0B))
    }

    @Test
    fun `loadState rejects a mismatched version byte`() {
        val mapper = Mapper22(build(prgKb = 32))
        val bogus = ByteArrayOutputStream().also {
            DataOutputStream(it).writeByte(0xFF)
        }.toByteArray()
        try {
            mapper.loadState(DataInputStream(ByteArrayInputStream(bogus)))
            assert(false) { "expected IncompatibleSaveStateException" }
        } catch (e: com.github.alondero.nestlin.SaveState.IncompatibleSaveStateException) {
            // expected
        }
    }

    // ---- Address-pin decode (VRC4b swap) ----

    @Test
    fun `address-pin decode uses the VRC4b swap (CPU A1→bit0, CPU A0→bit1)`() {
        // The decode contract: writes at the same $Bxxx group must target the
        // same CHR register regardless of sub-pin layout. With the VRC4b swap,
        // the four addresses map to sub 0/2/1/3 respectively, so:
        //   $B000 → low  nibble (sub 0)
        //   $B002 → high nibble (sub 1)
        //   $B001 → low  nibble (sub 2, which is also low nibble of chr1)
        //   $B003 → high nibble (sub 3, which is also high nibble of chr1)
        val mapper = Mapper22(build(prgKb = 32, chrKb = 32))
        mapper.cpuWrite(0xB000, 0x07.toSignedByte())   // sub 0 -> chr0 low  = 7
        mapper.cpuWrite(0xB002, 0x01.toSignedByte())   // sub 1 -> chr0 high = 1 -> stored 0x17
        // After shift: 0x17 >> 1 = 0x0B.
        assertThat("sub 0 must target chr0 low nibble",
            mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(0x0B))

        // Now overwrite with $B001 — this should land on chr1 low (sub 2),
        // not chr0. If the swap is wrong, this write would clobber chr0.
        mapper.cpuWrite(0xB001, 0x05.toSignedByte())   // sub 2 -> chr1 low = 5
        // chr0 still has stored 0x17 -> ppuRead returns 0x0B.
        assertThat("\$B001 must NOT clobber chr0 (proves the swap)",
            mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(0x0B))
        // chr1 now has stored low=5, high=0 -> stored 0x05 -> shift -> 0x02.
        assertThat("\$B001 must set chr1 low nibble",
            mapper.ppuRead(0x0400).toUnsignedInt(), equalTo(0x02))
    }
}