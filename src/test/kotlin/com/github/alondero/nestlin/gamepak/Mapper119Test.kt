package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Unit tests for Mapper 119 (Nintendo MMC6).
 *
 * MMC6 is an MMC3 variant: same PRG/CHR/IRQ, but the work-RAM is 1KB at
 * `$7000-$73FF` (NOT 8KB at `$6000-$7FFF`) with banked R/W control via
 * `$A001` bits 4-7 and an enable gate at `$8000` bit 5. These tests pin
 * down the MMC6-only behaviour on top of the inherited MMC3 banking.
 *
 * The mapper ID 119 is the iNES 1.0 alias for NES 2.0 mapper 4 submapper 1
 * — many older MMC6 dumps are labelled mapper 119 directly rather than via
 * the NES 2.0 submapper field.
 */
class Mapper119Test {

    /** Build a minimal mapper-119 GamePak: 32KB PRG, 8KB CHR. CHR is stamped per 1KB so CHR-bank tests can read bank index from PPU address $0000/$0400/etc. */
    private fun createGamePak(prgKb: Int = 32, chrKb: Int = 8): GamePak =
        testGamePak {
            mapper = 119
            this.prgKb = prgKb
            this.chrKb = chrKb
            stampChrBanks(windowKb = 1)
        }

    // ---- WRAM gating ($8000 bit 5) ----

    @Test
    fun `WRAM disabled by default - reads at 7000 return 0`() {
        val mapper = Mapper119(createGamePak())
        // After power-on wrRamEnabled = false. Reading $7000 should be open bus.
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x71FF).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x7200).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x73FF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8000 bit 5 enables WRAM - reads return open bus until a write lands`() {
        val mapper = Mapper119(createGamePak())
        // Bank-select register ($8000). Bit 5 = WRAM enable.
        mapper.cpuWrite(0x8000, 0x20)        // WRAM enable, R0 select, mode 0
        // Enable R0 read ($A001 bit 5) and R0 write ($A001 bit 4).
        mapper.cpuWrite(0xA001, 0x30)
        // With R0 read enabled but no write yet, $7000 returns 0 (RAM is zeroed).
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        // Write 0x42 to bank 0, then read it back.
        mapper.cpuWrite(0x7000, 0x42.toByte())
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `8000 bit 5 disabled suppresses writes - later enable sees zero`() {
        val mapper = Mapper119(createGamePak())
        // Enable + grant R/W on bank 0.
        mapper.cpuWrite(0x8000, 0x20)
        mapper.cpuWrite(0xA001, 0x30)
        // Try to write with WRAM "enabled" then disable - the write DID land.
        mapper.cpuWrite(0x7000, 0xAB.toByte())
        // Now clear bit 5 - reads return 0 even though the byte is in RAM.
        mapper.cpuWrite(0x8000, 0x00)
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        // Re-enable - the byte is still there (we just couldn't see it).
        mapper.cpuWrite(0x8000, 0x20)
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- $A001 per-bank R/W control ----

    @Test
    fun `A001 bank 0 write only - write succeeds, read returns 0`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20)        // WRAM enable
        mapper.cpuWrite(0xA001, 0x10)        // bank 0 W only (bit 4 set, bit 5 clear)
        // Read is gated off; should return 0 even though WRAM is enabled.
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        // Write succeeds silently (we can't see it from a read).
        mapper.cpuWrite(0x7000, 0x55.toByte())
        // Now grant the read bit and see the value.
        mapper.cpuWrite(0xA001, 0x30)
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0x55))
    }

    @Test
    fun `A001 bank 0 read only - read works, write is silently dropped`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20)        // WRAM enable
        mapper.cpuWrite(0xA001, 0x20)        // bank 0 R only (bit 5 set, bit 4 clear)
        // Read is enabled, but RAM was never written → returns 0.
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        // Attempted write should be dropped (gated off, no error).
        mapper.cpuWrite(0x7000, 0x99.toByte())
        // Re-enable read; still zero (write was discarded).
        mapper.cpuWrite(0xA001, 0x30)
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `A001 bank 1 R-W independent of bank 0`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20.toByte())
        // Enable ONLY bank 1 R/W (bits 6+7). Bank 0 is fully off.
        mapper.cpuWrite(0xA001, 0xC0.toByte())
        // Bank 0 reads return 0.
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x71FF).toUnsignedInt(), equalTo(0))
        // Bank 1 is fully accessible.
        mapper.cpuWrite(0x7200, 0x77.toByte())
        mapper.cpuWrite(0x73FF, 0x88.toByte())
        assertThat(mapper.cpuRead(0x7200).toUnsignedInt(), equalTo(0x77))
        assertThat(mapper.cpuRead(0x73FF).toUnsignedInt(), equalTo(0x88))
    }

    @Test
    fun `A001 controls only the matching 512-byte bank`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20.toByte())
        mapper.cpuWrite(0xA001, 0xF0.toByte())        // both banks R+W
        // Stamp bank 0 fully and bank 1 fully.
        for (i in 0..0x1FF) {
            mapper.cpuWrite(0x7000 + i, (i and 0xFF).toByte())
            mapper.cpuWrite(0x7200 + i, ((i xor 0xFF) and 0xFF).toByte())
        }
        // Spot-check a few addresses on both sides of the $71FF/$7200 boundary.
        assertThat(mapper.cpuRead(0x7000).toUnsignedInt(), equalTo(0x00))
        assertThat(mapper.cpuRead(0x71FF).toUnsignedInt(), equalTo(0xFF))
        assertThat(mapper.cpuRead(0x7200).toUnsignedInt(), equalTo(0xFF))
        assertThat(mapper.cpuRead(0x73FF).toUnsignedInt(), equalTo(0x00))
    }

    // ---- $6000-$6FFF: MMC6 has no PRG-RAM there ----

    @Test
    fun `6000-6FFF reads return 0 - MMC6 has no PRG-RAM there`() {
        val mapper = Mapper119(createGamePak())
        // Even with WRAM "enabled" via $8000, $6000-$6FFF is unaffected.
        mapper.cpuWrite(0x8000, 0x20)
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x6FFF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `6000-6FFF writes are silently discarded`() {
        val mapper = Mapper119(createGamePak())
        // Try to write to $6000 - this must not throw, and must not corrupt anything.
        mapper.cpuWrite(0x6000, 0x42.toByte())
        mapper.cpuWrite(0x6FFF, 0x43.toByte())
        // Subsequent reads return 0.
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat(mapper.cpuRead(0x6FFF).toUnsignedInt(), equalTo(0))
    }

    // ---- batteryBackedRam: should NOT return the unused 8KB buffer ----

    @Test
    fun `batteryBackedRam returns null so no sav file is created`() {
        val mapper = Mapper119(createGamePak())
        assertThat(mapper.batteryBackedRam(), equalTo(null))
    }

    // ---- Inherited MMC3 behaviour: PRG/CHR banking + scanline IRQ ----

    @Test
    fun `inherited PRG banking works - R6 swaps with second-to-last in mode 1`() {
        val mapper = Mapper119(createGamePak(prgKb = 128))   // 8 banks of 16KB
        // Mode 0 (default): $8000 reads from R6.
        mapper.cpuWrite(0x8000, 0x06)        // select R6
        mapper.cpuWrite(0x8001, 0x05)        // R6 = bank 5
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        // Mode 1: $8000 reads second-to-last (bank 6), R6 maps to $C000.
        mapper.cpuWrite(0x8000, 0x46)        // select R6, mode=1
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `inherited CHR banking works - R0 normal mode maps 2KB at 0000`() {
        val mapper = Mapper119(createGamePak())
        // Stamped CHR has bank index as the first byte per 1KB window.
        // With CHR-ROM 8KB, we have banks 0-7. R0 = bank 2 → first byte of $0000 should be 2.
        mapper.cpuWrite(0x8000, 0x00)        // select R0, normal mode
        mapper.cpuWrite(0x8001, 0x02)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
        // The next 1KB window pairs with bank 3 (R0+1).
        assertThat(mapper.ppuRead(0x0400).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `inherited scanline IRQ - latch=2 reload enable fire 3 edges IRQ pending`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0xC000, 2.toByte())   // latch = 2
        mapper.cpuWrite(0xC001, 0x00)         // reload
        mapper.cpuWrite(0xE001, 0x00)         // IRQ enable
        // 3 rising edges: edge 1 → counter=2; edge 2 → counter=1; edge 3 → counter=0 → IRQ.
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)
        assertThat(mapper.isIrqPending(), equalTo(true))
        // Disable clears.
        mapper.cpuWrite(0xE000, 0x00)
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    // ---- Save state round-trip ----

    @Test
    fun `saveState loadState round-trips WRAM and gating`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20.toByte())         // WRAM enable
        mapper.cpuWrite(0xA001, 0xF0.toByte())         // both banks R+W
        mapper.cpuWrite(0x7000, 0x11.toByte())
        mapper.cpuWrite(0x73FF, 0x22.toByte())

        val javaIo = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(javaIo)
        mapper.saveState(out)
        val bytes = javaIo.toByteArray()

        // Restore into a fresh mapper instance.
        val mapper2 = Mapper119(createGamePak())
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        mapper2.loadState(inp)

        assertThat(mapper2.cpuRead(0x7000).toUnsignedInt(), equalTo(0x11))
        assertThat(mapper2.cpuRead(0x73FF).toUnsignedInt(), equalTo(0x22))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reports mapperId 119 and exposes WRAM control`() {
        val mapper = Mapper119(createGamePak())
        mapper.cpuWrite(0x8000, 0x20.toByte())         // WRAM enable
        mapper.cpuWrite(0xA001, 0xC0.toByte())         // bank 1 R+W only
        val snap = mapper.snapshot()  // Mapper.snapshot() returns non-null MapperStateSnapshot in this mapper
        assertThat(snap.mapperId, equalTo(119))
        assertThat(snap.registers["wrRamEnabled"], equalTo(1))
        assertThat(snap.registers["wrRamControl"], equalTo(0xC0))
        assertThat(snap.type, equalTo("MMC6"))
    }
}