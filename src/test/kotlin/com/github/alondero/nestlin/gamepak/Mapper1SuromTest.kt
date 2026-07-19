package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * MMC1 512KB SUROM PRG banking (Dragon Warrior III/IV).
 *
 * A 512KB MMC1 board can only address 256KB with the 4-bit PRG-bank register;
 * the fifth PRG address line (A18) that selects the active 256KB half comes from
 * **bit 4 of the CHR bank-0 register**. That outer bank applies to BOTH the
 * switchable window and the fixed "last bank" window — the fixed bank is the last
 * 16KB of the *current* 256KB half, not the last bank of the whole ROM.
 *
 * Before the fix, the PRG register was used as a flat 5-bit index and CHR bit 4
 * was ignored, so the upper 256KB was unreachable and the fixed bank was always
 * the whole-ROM last bank.
 *
 * This behaviour is guarded on PRG size (only 512KB boards), so ordinary MMC1
 * games whose CHR bit 4 legitimately selects a CHR bank are unaffected.
 */
class Mapper1SuromTest {

    /** 512KB PRG (32 × 16KB banks, each stamped with its index), CHR-RAM. */
    private fun surom(): Mapper1 {
        val pak = testGamePak {
            mapper = 1
            prgKb = 512
            chrKb = 0
            stampPrgBanks(windowKb = 16)
        }
        return pak.createMapper() as Mapper1
    }

    /**
     * Drive MMC1's 5-write serial load (LSB first) into the register at [addr].
     *
     * Each write is spaced two CPU cycles from the previous one so the
     * consecutive-write guard (issue #235) accepts every bit — real software
     * issues each of the 5 writes as a separate STA, several cycles apart.
     */
    private fun write5(m: Mapper1, addr: Int, value: Int) {
        for (i in 0 until 5) {
            repeat(2) { m.tickCpuCycle() }
            m.cpuWrite(addr, (((value shr i) and 1)).toSignedByte())
        }
    }

    private fun setControl(m: Mapper1, value: Int) = write5(m, 0x8000, value)   // $8000-$9FFF
    private fun setChrBank0(m: Mapper1, value: Int) = write5(m, 0xA000, value)  // $A000-$BFFF
    private fun setPrgBank(m: Mapper1, value: Int) = write5(m, 0xE000, value)   // $E000-$FFFF

    @Test
    fun `CHR bit 4 selects the 256KB half for the switchable window`() {
        val m = surom()
        setControl(m, 0x0C)      // PRG mode 3, 8KB CHR mode
        setPrgBank(m, 5)

        setChrBank0(m, 0x00)     // outer half 0
        assertThat("lower half switchable", m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))

        setChrBank0(m, 0x10)     // outer half 1 (PRG A18 = 1)
        assertThat("upper half switchable", m.cpuRead(0x8000).toUnsignedInt(), equalTo(16 + 5))
    }

    @Test
    fun `fixed C000 bank is the last 16KB of the current 256KB half`() {
        val m = surom()
        setControl(m, 0x0C)      // PRG mode 3
        setPrgBank(m, 0)

        setChrBank0(m, 0x00)     // lower half -> fixed = bank 15
        assertThat("lower half fixed", m.cpuRead(0xC000).toUnsignedInt(), equalTo(15))

        setChrBank0(m, 0x10)     // upper half -> fixed = bank 31
        assertThat("upper half fixed", m.cpuRead(0xC000).toUnsignedInt(), equalTo(31))
    }

    @Test
    fun `every 16KB bank across the full 512KB is reachable`() {
        val m = surom()
        setControl(m, 0x0C)      // PRG mode 3, switchable at $8000
        for (bank in 0 until 32) {
            setChrBank0(m, (bank shr 4) shl 4)   // outer half from bit 4
            setPrgBank(m, bank and 0x0F)
            assertThat("bank $bank", m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }
}
