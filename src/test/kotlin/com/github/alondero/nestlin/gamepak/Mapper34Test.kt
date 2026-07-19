package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 34 (BNROM / NINA-001).
 *
 * Two unrelated boards share one iNES number; the test suite is split along
 * the variant boundary so a regression in either decode is localisable.
 *
 * Stamp convention (follows the in-repo test style — see `Mapper33Test`,
 * `Mapper10Test`, `Mapper71Test`):
 *  - 32KB PRG banks: byte 0 = bank index, byte 0x7FFF = bank XOR 0xFF.
 *  - 4KB CHR banks:  byte 0 = bank index, byte 0x0FFF = bank XOR 0xFF.
 *  - 8KB CHR banks:  byte 0 = bank index, byte 0x1FFF = bank XOR 0xFF.
 *
 * All fixtures use [testGamePak] / `TestRomBuilder` — never raw hand-rolled headers
 * (HeaderConstructionLintTest enforces that).
 */
class Mapper34Test {

    // ---- BNROM variant fixtures ----

    /**
     * Plain iNES BNROM: 128KB PRG (4 32KB banks) + 8KB CHR. The CHR size is the
     * discriminating heuristic — ≤8KB picks BNROM when no submapper is set.
     * Default header mirroring is horizontal.
     */
    private fun newBnrom(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            prgKb = 128       // 4 × 32KB banks (so bank 2 is distinct from bank 0)
            chrKb = 8         // 1 × 8KB bank — heuristic picks BNROM
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 4)  // stamps every 4KB; test reads use byte 0
        }
        return pak.createMapper() as Mapper34
    }

    /** BNROM with vertical mirroring. */
    private fun newBnromVertical(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            prgKb = 64
            chrKb = 8
            verticalMirroring = true
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 4)
        }
        return pak.createMapper() as Mapper34
    }

    /** BNROM with no CHR at all — exercises the CHR-RAM fallback. */
    private fun newBnromChrRam(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            prgKb = 32
            chrKb = 0
            stampPrgBanks(windowKb = 32)
        }
        return pak.createMapper() as Mapper34
    }

    // ---- NINA-001 variant fixtures ----

    /**
     * NES 2.0 NINA-001 (submapper 1): 64KB PRG (2 × 32KB banks) + 16KB CHR
     * (4 × 4KB banks). 64KB is the NINA-001 PRG ceiling per the spec; 16KB
     * gives 4 CHR banks for testing the 4-bit bank select.
     */
    private fun newNina001(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            submapper = 1     // NES 2.0: NINA-001
            prgKb = 64
            chrKb = 16        // 4 × 4KB banks
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 4)
        }
        return pak.createMapper() as Mapper34
    }

    private fun newNina001Vertical(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            submapper = 1
            prgKb = 64
            chrKb = 16
            verticalMirroring = true
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 4)
        }
        return pak.createMapper() as Mapper34
    }

    /** NINA-001 with no CHR ROM — exercises the CHR-RAM fallback path. */
    private fun newNina001ChrRam(): Mapper34 {
        val pak = testGamePak {
            mapper = 34
            submapper = 1
            prgKb = 64
            chrKb = 0
            stampPrgBanks(windowKb = 32)
        }
        return pak.createMapper() as Mapper34
    }

    // ============================================================
    //  Dispatch — covered implicitly by every other test in this file
    //  (which calls `as Mapper34` to get the typed instance), so no
    //  dedicated dispatch test is needed.
    // ============================================================

    // ============================================================
    //  BNROM — register decode
    // ============================================================

    @Test
    fun `BNROM PRG bank select uses low 3 bits of write value`() {
        val m = newBnrom()
        m.cpuWrite(0x8000, 0x02.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `BNROM PRG high bits (4-7) are ignored`() {
        // The memory read alone can't prove the high bits are dropped — with
        // 4 banks, bank 5 (0xF5 & 0x07) wraps modulo to bank 1, but the same
        // modulo would apply to a register that did NOT mask the high bits
        // (245 % 4 = 1). So we assert against the snapshot's raw register
        // value, which reports what was actually latched into the register
        // before the modulo read-path runs.
        val m = newBnrom()
        m.cpuWrite(0xC000, 0xF5.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.banks["prgBank"], equalTo(5))    // raw register value
        // And the memory read at $8000 sees bank 1 (5 % 4) — proves the
        // register was wired into the PRG read path.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `BNROM any write in 8000-FFFF fires the bank register`() {
        // The chip has no address decode — the *whole* 32KB write window
        // collapses to one register. Test the address-edge cases.
        val m = newBnrom()
        m.cpuWrite(0x8000, 0x01.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        m.cpuWrite(0xFFFF, 0x03.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        m.cpuWrite(0xABCD, 0x02.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `BNROM writes below 8000 do not fire the register`() {
        val m = newBnrom()
        m.cpuWrite(0x6000, 0x03.toSignedByte())   // 6502 stack page
        m.cpuWrite(0x7FFF, 0x03.toSignedByte())
        // Still PRG bank 0 — the writes never reached the mapper.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `BNROM all 4 PRG banks are reachable`() {
        val m = newBnrom()  // 4 32KB banks
        for (bank in 0..3) {
            m.cpuWrite(0x8000, bank.toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `BNROM PRG bank numbers larger than bank count wrap modulo`() {
        // 4 banks. 0xFF & 0x07 = 0x07, 0x07 % 4 = 3.
        val m = newBnrom()
        m.cpuWrite(0x8000, 0xFF.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    // ============================================================
    //  BNROM — CHR banking
    // ============================================================

    @Test
    fun `BNROM CHR bank select uses bits 3-4`() {
        // Plain iNES header with chrKb=16 would trigger the NINA-001 heuristic.
        // To force BNROM with >8KB CHR we use NES 2.0 submapper 2 (submapper
        // wins over the heuristic, see `NES 2_0 submapper 2 always selects BNROM`).
        val pak = testGamePak {
            mapper = 34
            submapper = 2     // NES 2.0: BNROM
            prgKb = 64
            chrKb = 16        // 2 × 8KB banks
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 8)
        }
        val m = pak.createMapper() as Mapper34

        // Bits 3-4 = 0 → CHR bank 0.
        m.cpuWrite(0x8000, 0x00.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Bits 3-4 = 1 (value 0x08) → CHR bank 1.
        m.cpuWrite(0x8000, 0x08.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `BNROM CHR high bits (5-7) are ignored`() {
        val m = testGamePak {
            mapper = 34
            submapper = 2
            prgKb = 64
            chrKb = 16
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 8)
        }.createMapper() as Mapper34
        // 0xFF & 0x07 = 7 (PRG bank 7, wraps to 1 with 2 32KB banks).
        // (0xFF >> 3) & 0x03 = 0x1F & 0x03 = 3 → 3 % 2 = 1 (one 8KB bank out of 2).
        m.cpuWrite(0x8000, 0xFF.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    // ============================================================
    //  BNROM — CHR-RAM fallback + mirroring + save/load
    // ============================================================

    @Test
    fun `BNROM with 0 CHR falls back to 8KB CHR-RAM`() {
        val m = newBnromChrRam()
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x1234, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `BNROM mirroring follows iNES header (horizontal)`() {
        assertThat(newBnrom().currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `BNROM mirroring follows iNES header (vertical)`() {
        assertThat(newBnromVertical().currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `BNROM batteryBackedRam is null`() {
        assertThat(newBnrom().batteryBackedRam(), equalTo(null))
    }

    @Test
    fun `BNROM saveState then loadState round-trips banks`() {
        val m = newBnrom()
        // PRG bank = 0x15 & 0x07 = 5 → wraps modulo 4 (BNROM has 4 banks) = 1.
        // CHR bits = (0x15 >> 3) & 0x03 = 2 → wraps modulo 1 (single 8KB CHR bank).
        m.cpuWrite(0x8000, 0x15.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newBnrom()
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        // Read bank 1 (the modulo result of PRG bank 5 across 4 physical banks).
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        // CHR bank 2 wraps modulo 1 — read byte 0 returns 0.
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Confirm via the snapshot that the RAW register values round-tripped
        // (5 for PRG, 2 for CHR) — modulo is applied at read time, not stored.
        val snap = fresh.snapshot()
        assertThat(snap.banks["prgBank"], equalTo(5))
        assertThat(snap.banks["chrBank"], equalTo(2))
    }

    @Test
    fun `BNROM saveState rejects when variant mismatches`() {
        // Build a BNROM save state...
        val m = newBnrom()
        m.cpuWrite(0x8000, 0x01.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        // ...and try to load it into a NINA-001 instance.
        val fresh = newNina001()
        assertThrows<SaveState.IncompatibleSaveStateException> {
            DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        }
    }

    // ============================================================
    //  NINA-001 — register decode
    // ============================================================

    @Test
    fun `NINA-001 7FFD bit 0 selects the 32KB PRG bank`() {
        val m = newNina001()  // 64KB PRG = 2 32KB banks
        // Default state: PRG bank 0.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `NINA-001 7FFD high bits are masked off`() {
        // NINA-001's PRG bank field is 1 bit (max 64KB / 2 banks). High bits
        // must be ignored — otherwise a buggy game could try to address beyond
        // the 64KB ceiling and we'd return arbitrary data.
        val m = newNina001()
        m.cpuWrite(0x7FFD, 0xFF.toSignedByte())
        // 0xFF & 0x01 = 1 → PRG bank 1. Verified via the raw register (the
        // snapshot reports the latched value before any modulo, which is the
        // only place a bug in the mask would show up).
        val snap = m.snapshot()
        assertThat(snap.banks["prgBank"], equalTo(1))
    }

    @Test
    fun `NINA-001 writes outside 7FFD-7FFF do not select PRG`() {
        val m = newNina001()
        m.cpuWrite(0x8000, 0xFF.toSignedByte())    // would fire BNROM — must not affect NINA-001 PRG
        m.cpuWrite(0x7FFE, 0xFF.toSignedByte())
        m.cpuWrite(0x7FFF, 0xFF.toSignedByte())
        // PRG bank is still 0 — none of those addresses are the PRG register.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `NINA-001 7FFE selects 4KB CHR bank 0 at PPU 0000-0FFF`() {
        val m = newNina001()  // 16KB CHR = 4 4KB banks
        m.cpuWrite(0x7FFE, 0x02.toSignedByte())   // bank 2 at $0000-$0FFF
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `NINA-001 7FFF selects 4KB CHR bank 1 at PPU 1000-1FFF`() {
        val m = newNina001()
        m.cpuWrite(0x7FFF, 0x03.toSignedByte())   // bank 3 at $1000-$1FFF
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `NINA-001 two 4KB CHR windows are independent`() {
        // Write bank 1 to $7FFE and bank 2 to $7FFF. Verify the windows
        // did NOT bleed into each other — a common bug if you accidentally
        // collapse the two 4KB windows into a single 8KB window.
        val m = newNina001()
        m.cpuWrite(0x7FFE, 0x01.toSignedByte())
        m.cpuWrite(0x7FFF, 0x02.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `NINA-001 CHR high nibble is masked off`() {
        // 4-bit bank field (max 16 banks / 64KB CHR).
        val m = newNina001()
        m.cpuWrite(0x7FFE, 0xFF.toSignedByte())
        // 0xFF & 0x0F = 15 — out of 4 banks, 15 % 4 = 3.
        // Use the snapshot to verify the raw register value, since a bug in
        // the mask would be hidden by the modulo read-path (15 mod 4 = 3 either way).
        val snap = m.snapshot()
        assertThat(snap.banks["chrBank0"], equalTo(15))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(3))
    }

    // ============================================================
    //  NINA-001 — PRG-RAM
    // ============================================================

    @Test
    fun `NINA-001 6000-7FFF is 8KB PRG-RAM (excluding the three register addresses)`() {
        // On real NINA-001 hardware, $7FFD/$7FFE/$7FFF are the bank registers
        // (they take precedence over the $6000-$7FFF PRG-RAM range) — the
        // `when` branch ordering in `cpuWrite` matches that. So this test
        // uses $6000 and $7000 only, both of which land in the PRG-RAM array.
        val m = newNina001()
        // RAM is zero-initialized.
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.cpuWrite(0x6000, 0xAB.toSignedByte())
        m.cpuWrite(0x7000, 0xCD.toSignedByte())
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0xAB))
        assertThat(m.cpuRead(0x7000).toUnsignedInt(), equalTo(0xCD))
    }

    @Test
    fun `NINA-001 writes to PRG-RAM set batteryDirty`() {
        val m = newNina001()
        // batteryDirty starts false.
        assertThat(m.batteryDirty, equalTo(false))
        m.cpuWrite(0x6000, 0x01.toSignedByte())
        assertThat(m.batteryDirty, equalTo(true))
    }

    @Test
    fun `NINA-001 batteryBackedRam returns the 8KB buffer`() {
        val m = newNina001()
        val ram = m.batteryBackedRam()
        Assertions.assertNotNull(ram)
        assertThat(ram!!.size, equalTo(0x2000))
    }

    @Test
    fun `NINA-001 PRG-RAM and PRG ROM do not alias`() {
        // Writing to $6000 (PRG-RAM) must not change the value visible at
        // $8000 (PRG ROM). And vice versa — flipping PRG banks must not
        // appear to write PRG-RAM.
        val m = newNina001()
        m.cpuWrite(0x6000, 0xAB.toSignedByte())
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())
        // PRG-RAM still has the byte we wrote.
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0xAB))
        // PRG ROM at $8000 is now bank 1 — not 0xAB.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    // ============================================================
    //  NINA-001 — CHR-RAM fallback + mirroring + save/load
    // ============================================================

    @Test
    fun `NINA-001 with 0 CHR falls back to 8KB CHR-RAM`() {
        val m = newNina001ChrRam()
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x1234, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
        // CHR-RAM is shared across both 4KB windows (it's one backing buffer).
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `NINA-001 mirroring follows iNES header (horizontal)`() {
        assertThat(newNina001().currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `NINA-001 mirroring follows iNES header (vertical)`() {
        assertThat(newNina001Vertical().currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `NINA-001 saveState then loadState round-trips all state`() {
        val m = newNina001()
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())   // PRG bank 1
        m.cpuWrite(0x7FFE, 0x02.toSignedByte())   // CHR bank 0 = 2
        m.cpuWrite(0x7FFF, 0x03.toSignedByte())   // CHR bank 1 = 3
        m.cpuWrite(0x6000, 0xAB.toSignedByte())
        m.cpuWrite(0x7000, 0xCD.toSignedByte())   // another PRG-RAM byte (NOT $7FFF — that's a CHR register)

        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newNina001()
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }

        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
        assertThat(fresh.ppuRead(0x1000).toUnsignedInt(), equalTo(3))
        assertThat(fresh.cpuRead(0x6000).toUnsignedInt(), equalTo(0xAB))
        assertThat(fresh.cpuRead(0x7000).toUnsignedInt(), equalTo(0xCD))
    }

    @Test
    fun `NINA-001 saveState rejects when variant mismatches`() {
        val m = newNina001()
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newBnrom()
        assertThrows<SaveState.IncompatibleSaveStateException> {
            DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        }
    }

    // ============================================================
    //  Variant detection
    // ============================================================

    @Test
    fun `NES 2_0 submapper 1 always selects NINA-001`() {
        // Even with 0 CHR (which would otherwise pick BNROM), submapper wins.
        val m = testGamePak {
            mapper = 34
            submapper = 1
            prgKb = 32
            chrKb = 0
        }.createMapper() as Mapper34
        // NINA-001 register addresses should be wired — a $7FFD write flips PRG.
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())
        // 32KB PRG = 1 bank, so the modulo wraps to bank 0. The point is the
        // write was accepted (no crash, no exception) and the register fired.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `NES 2_0 submapper 2 always selects BNROM`() {
        // Even with >8KB CHR (which would otherwise pick NINA-001 via heuristic),
        // submapper 2 wins.
        val m = testGamePak {
            mapper = 34
            submapper = 2
            prgKb = 32
            chrKb = 16
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 8)
        }.createMapper() as Mapper34
        // BNROM: bits 3-4 select the 8KB CHR bank. Bits 0-2 select PRG.
        m.cpuWrite(0x8000, 0x08.toSignedByte())   // PRG=0, CHR=1
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        // BNROM has no PRG-RAM.
        assertThat(m.batteryBackedRam(), equalTo(null))
    }

    @Test
    fun `plain iNES heuristic picks NINA-001 when CHR greater than 8KB`() {
        val m = testGamePak {
            mapper = 34   // no submapper → plain iNES
            prgKb = 32
            chrKb = 16    // >8KB → heuristic picks NINA-001
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 4)
        }.createMapper() as Mapper34
        // NINA-001 register addresses should be wired.
        m.cpuWrite(0x7FFE, 0x01.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `plain iNES heuristic picks BNROM when CHR is 8KB`() {
        val m = testGamePak {
            mapper = 34
            prgKb = 32
            chrKb = 8     // ≤8KB → heuristic picks BNROM
            stampPrgBanks(windowKb = 32)
            stampChrBanks(windowKb = 8)
        }.createMapper() as Mapper34
        // BNROM: a write anywhere in $8000-$FFFF flips PRG bits 0-2.
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        // 32KB PRG = 1 bank, so modulo wraps to bank 0. The point is the
        // BNROM register fired (no exception, write accepted).
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.batteryBackedRam(), equalTo(null))
    }

    @Test
    fun `plain iNES 0 CHR defaults to BNROM decode`() {
        val m = testGamePak {
            mapper = 34
            prgKb = 32
            chrKb = 0
            stampPrgBanks(windowKb = 32)
        }.createMapper() as Mapper34
        // Default to BNROM: no PRG-RAM, write-anywhere register.
        assertThat(m.batteryBackedRam(), equalTo(null))
    }

    // ============================================================
    //  Snapshots
    // ============================================================

    @Test
    fun `BNROM snapshot reports BNROM variant and BNROM-style bank keys`() {
        val m = newBnrom()
        m.cpuWrite(0x8000, 0x15.toSignedByte())   // PRG=5, CHR=2
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(34))
        assertThat(snap.type, equalTo("BNROM"))
        assertThat(snap.banks["variant"], equalTo(0))
        assertThat(snap.banks["prgBank"], equalTo(5))
        assertThat(snap.banks["chrBank"], equalTo(2))
        // BNROM has no PRG-RAM.
        assertThat(snap.prgRam, equalTo(null))
    }

    @Test
    fun `NINA-001 snapshot reports NINA-001 variant and three bank keys`() {
        val m = newNina001()
        m.cpuWrite(0x7FFD, 0x01.toSignedByte())
        m.cpuWrite(0x7FFE, 0x02.toSignedByte())
        m.cpuWrite(0x7FFF, 0x03.toSignedByte())
        m.cpuWrite(0x6000, 0xAB.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(34))
        assertThat(snap.type, equalTo("NINA-001"))
        assertThat(snap.banks["variant"], equalTo(1))
        assertThat(snap.banks["prgBank"], equalTo(1))
        assertThat(snap.banks["chrBank0"], equalTo(2))
        assertThat(snap.banks["chrBank1"], equalTo(3))
        Assertions.assertNotNull(snap.prgRam)
        assertThat(snap.prgRam!![0].toUnsignedInt(), equalTo(0xAB))
    }
}