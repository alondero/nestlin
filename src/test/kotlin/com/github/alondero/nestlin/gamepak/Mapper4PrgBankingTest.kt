package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

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
     * Issue #311 review (round 3): a positive but out-of-range mirroringOverride
     * ordinal in a corrupted save state must raise
     * [SaveState.IncompatibleSaveStateException], not silently alias to `null`
     * (which would alias "user has no override" with "file is corrupt").
     * The negative ordinal remains the documented "no override" sentinel.
     */
    @Test
    fun `mirroringOverride with out-of-range positive ordinal raises IncompatibleSaveStateException`() {
        listOf(99, Int.MAX_VALUE).forEach { badOrdinal ->
            val mapper = Mapper4(testGamePak { mapper = 4; prgKb = 16; chrKb = 8 })
            val bytes = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { mapper.saveState(it) }
                baos.toByteArray()
            }
            // Patch the mirroringOverride int (written by Mapper4.saveState).
            // Layout from Mapper4.saveState: ... bankSelect, chrPrgInvert,
            // prgMode, scanlineCounter state, then mirrorOrd as a 4-byte
            // big-endian int at the very end of the mirrorOverride block.
            // The simplest correct patch is to overwrite the last 4 bytes of
            // the stream — Mapper4.saveState is the last thing Mapper.saveState
            // writes before chrMemory, and chrMemory is the variable-length tail.
            // For test isolation we rebuild via DataOutputStream rather than
            // compute byte offsets by hand.
            val patched = ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    dos.write(bytes, 0, bytes.size - 4)    // everything before mirrorOrd
                    dos.writeInt(badOrdinal)                // patched mirrorOrd
                }
                baos.toByteArray()
            }
            assertThrowsWithMessage<SaveState.IncompatibleSaveStateException>(
                "Invalid mirroring override ordinal $badOrdinal",
            ) {
                DataInputStream(ByteArrayInputStream(patched)).use { mapper.loadState(it) }
            }
        }
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
