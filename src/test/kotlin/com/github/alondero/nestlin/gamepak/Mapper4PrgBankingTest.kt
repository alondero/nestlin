package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/** Regression coverage for Mapper 4 program-ROM fixed-bank selection. */
class Mapper4PrgBankingTest {

    @Test
    fun `one 8KB PRG bank clamps the second-to-last fixed bank to zero in both modes`() {
        val mapper = mapperWithPrgBankCount(1)

        // Power-on PRG mode: the fixed second-to-last bank is at $C000.
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(0))

        // PRG mode 1 swaps that fixed bank into $8000.
        mapper.cpuWrite(0x8000, 0x40)
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `zero derived PRG banks clamps the last fixed bank to zero`() {
        val mapper = mapperWithPrgBankCount(0)

        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(0))
    }

    /**
     * iNES represents PRG ROM in 16KB units, so [GamePak] cannot naturally
     * construct the issue's latent one-8KB-bank state. Start from its smallest
     * valid image, then override Mapper 4's derived 8KB count so the test still
     * exercises the real [Mapper4.cpuRead] fixed-bank indexing path.
     */
    private fun mapperWithPrgBankCount(prgBankCount: Int): Mapper4 {
        val gamePak = testGamePak {
            mapper = 4
            prgKb = 16
            chrKb = 8
            stampPrgBanks(windowKb = 8)
        }
        return Mapper4(gamePak).also { mapper ->
            Mapper4::class.java.getDeclaredField("prgBankCount").apply {
                isAccessible = true
                setInt(mapper, prgBankCount)
            }
        }
    }
}
