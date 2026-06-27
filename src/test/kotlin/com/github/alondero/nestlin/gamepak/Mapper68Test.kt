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
 * Unit tests for Mapper 68 (Sunsoft-4).
 *
 * PRG is stamped so each 16KB bank's first byte holds its bank index;
 * CHR is stamped so each 2KB bank's first byte holds its bank index.
 * That lets a single read assert exactly which bank is currently mapped
 * into a window without needing the snapshot or the file structure.
 *
 * Test ROM is built with [testGamePak] / TestRomBuilder — see
 * HeaderConstructionLintTest for why raw hand-built iNES headers are
 * not allowed in new tests.
 */
class Mapper68Test {

    private fun newMapper68(
        prg16k: Int = 4,
        chr2k: Int = 8,
        batteryFlag: Boolean = false,
    ): Mapper68 {
        // testGamePak needs PRG in multiples of 16KB and CHR in multiples
        // of 8KB. CHR granularity in iNES is 8KB; for tests we round up
        // to that and stamp every 2KB window inside.
        val chrSizeKb = (chr2k * 2).coerceAtLeast(8)
        val prgSizeKb = prg16k * 16
        val gamePak = testGamePak {
            mapper = 68
            prgKb = prgSizeKb
            chrKb = chrSizeKb
            battery = batteryFlag
            stampPrgBanks(windowKb = 16)
            stampChrBanks(windowKb = 2)
        }
        return gamePak.createMapper() as Mapper68
    }

    // ---- Mapper dispatch ----

    @Test
    fun `createMapper returns Mapper68 for iNES mapper 68`() {
        val mapper = newMapper68()
        assertThat(mapper.snapshot().mapperId, equalTo(68))
        assertThat(mapper.snapshot().type, equalTo("Sunsoft-4"))
    }

    // ---- PRG banking ----

    @Test
    fun `power-on state has page 0 fixed to bank 0`() {
        val mapper = newMapper68(prg16k = 8)
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `power-on state has page 1 fixed to the last 16KB bank`() {
        val mapper = newMapper68(prg16k = 8)
        // TestRomBuilder stamps only the FIRST byte of each PRG bank.
        // Both addresses land inside bank 7, which is stamped at 0xC000.
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `page 1 stays fixed when F000 is written`() {
        val mapper = newMapper68(prg16k = 8)
        mapper.cpuWrite(0xF000, 0x03)   // page 0 -> bank 3
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `F000 bits 0-2 select page 0 bank`() {
        val mapper = newMapper68(prg16k = 8)
        for (bank in 0..7) {
            mapper.cpuWrite(0xF000, bank.toByte())
            assertThat("page 0 after writing bank $bank",
                mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `F000 mirrors within 0xF000 to 0xFFFF`() {
        val mapper = newMapper68(prg16k = 8)
        // The mapper uses `addr and 0xF000`, so the low 12 bits of the
        // register write are ignored. Verify three different addresses.
        listOf(0xF000, 0xFABC, 0xFFFF).forEach { addr ->
            mapper.cpuWrite(addr, 0x05)
            assertThat("page 0 after writing to $addr",
                mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        }
    }

    // ---- External PRG mode ----

    @Test
    fun `F000 bit 3 selects an external PRG bank when PRG is over 8 banks`() {
        val mapper = newMapper68(prg16k = 12)   // 192KB -> banks 8..11 are external
        // bit 3 = 0 -> external mode. low 3 bits = 2 -> external page 8|2 = 10.
        mapper.cpuWrite(0xF000, 0x02)
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(10))
    }

    @Test
    fun `F000 bit 3 set disables external mode`() {
        val mapper = newMapper68(prg16k = 12)
        mapper.cpuWrite(0xF000, 0x0B)   // bit 3 = 1 -> regular; low 3 bits = 3
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `external PRG is ignored when PRG has 8 or fewer banks`() {
        // With only 8 PRG banks, there is no "external" range to address.
        val mapper = newMapper68(prg16k = 8)
        mapper.cpuWrite(0xF000, 0x00)   // bit 3 = 0, but prgBankCount = 8
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    // ---- CHR banking (4×2KB windows) ----

    @Test
    fun `8000 selects the 2KB CHR bank at PPU 0000-07FF`() {
        val mapper = newMapper68(chr2k = 16)
        mapper.cpuWrite(0x8000, 0x05.toByte())
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `9000 selects the 2KB CHR bank at PPU 0800-0FFF`() {
        val mapper = newMapper68(chr2k = 16)
        mapper.cpuWrite(0x9000, 0x06)
        assertThat(mapper.ppuRead(0x0800).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `A000 selects the 2KB CHR bank at PPU 1000-17FF`() {
        val mapper = newMapper68(chr2k = 16)
        mapper.cpuWrite(0xA000, 0x07)
        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `B000 selects the 2KB CHR bank at PPU 1800-1FFF`() {
        val mapper = newMapper68(chr2k = 16)
        mapper.cpuWrite(0xB000, 0x09)
        assertThat(mapper.ppuRead(0x1800).toUnsignedInt(), equalTo(9))
    }

    @Test
    fun `CHR register writes mirror within their 4KB window`() {
        // `(addr & 0xF000) == 0x8000` ignores the low 12 bits, so any
        // address in $8000-$8FFF selects CHR bank 0.
        val mapper = newMapper68(chr2k = 16)
        mapper.cpuWrite(0x8123, 0x05)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
    }

    // ---- Mirroring ($E000) ----

    @Test
    fun `E000 bit pattern selects each of the four mirroring modes`() {
        val mapper = newMapper68()
        for (mode in 0..3) {
            mapper.cpuWrite(0xE000, mode.toByte())
            val expected = when (mode) {
                0 -> Mapper.MirroringMode.VERTICAL
                1 -> Mapper.MirroringMode.HORIZONTAL
                2 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
                else -> Mapper.MirroringMode.ONE_SCREEN_UPPER
            }
            assertThat("mirroring after E000=$mode",
                mapper.currentMirroring(), equalTo(expected))
        }
    }

    @Test
    fun `E000 only uses bits 0-1 for mirroring`() {
        val mapper = newMapper68()
        mapper.cpuWrite(0xE000, 0xFF.toByte())   // everything high
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---- PRG-RAM at $6000-$7FFF ----

    @Test
    fun `PRG-RAM reads return open bus when F000 bit 4 is clear`() {
        // Default state: prgRamEnabled = false. CPU reads at $6000 should
        // return the data-bus value (0 when Memory doesn't override it).
        val mapper = newMapper68()
        mapper.dataBus = 0x5A.toByte()
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x5A))
    }

    @Test
    fun `PRG-RAM writes are accepted regardless of F000 bit 4`() {
        // Mesen2 stores the byte AND arms the licensing timer regardless
        // of the enable bit — the enable only gates reads.
        val mapper = newMapper68()
        mapper.cpuWrite(0x6000, 0x42.toByte())
        mapper.cpuWrite(0xF000, 0x10)   // enable PRG-RAM reads
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `F000 bit 4 enables PRG-RAM reads`() {
        val mapper = newMapper68()
        mapper.cpuWrite(0x6000, 0x99.toByte())
        assertThat("disabled: open bus",
            mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        mapper.cpuWrite(0xF000, 0x10)
        assertThat("enabled: returns RAM",
            mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x99))
    }

    @Test
    fun `batteryBackedRam returns the same buffer SaveRam will persist`() {
        val mapper = newMapper68()
        val ram = mapper.batteryBackedRam()
        assertThat(ram?.size, equalTo(0x2000))
        mapper.cpuWrite(0x6000, 0xAB.toByte())
        assertThat(ram!![0].toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `writing to PRG-RAM sets batteryDirty`() {
        val mapper = newMapper68()
        assertThat(mapper.batteryDirty, equalTo(false))
        mapper.cpuWrite(0x6000, 0xCD.toByte())
        assertThat(mapper.batteryDirty, equalTo(true))
    }

    // ---- Licensing IC ----

    @Test
    fun `writing to 6000-7FFF arms the licensing timer and unmaps 8000-BFFF`() {
        val mapper = newMapper68(prg16k = 4)
        mapper.cpuWrite(0x6000, 0x42.toByte())    // arm licensing timer
        // Page 0 is now unmapped; reads should return open bus.
        mapper.dataBus = 0x77.toByte()
        assertThat("open bus during licensing window",
            mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0x77))
        // Page 1 ($C000-$FFFF) is NEVER unmapped by the licensing IC.
        assertThat("page 1 stays mapped",
            mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `licensing timer counts down via tickCpuCycle`() {
        val mapper = newMapper68(prg16k = 4)
        mapper.cpuWrite(0x6000, 0x01.toByte())
        assertThat(mapper.snapshot().registers["licensingTimer"]!! > 0, equalTo(true))
        // A handful of cycles should not exhaust it (timer is 107,520 long).
        repeat(100) { mapper.tickCpuCycle() }
        mapper.dataBus = 0x77.toByte()
        assertThat("still unmapped after 100 cycles",
            mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0x77))
    }

    @Test
    fun `licensing timer expires after 1024 times 105 cycles`() {
        val mapper = newMapper68(prg16k = 4)
        mapper.cpuWrite(0x6000, 0x01.toByte())
        // Tick one cycle past the LICENSING_DURATION total.
        repeat(1024 * 105 + 1) { mapper.tickCpuCycle() }
        // Page 0 must be readable again.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `tickCpuCycle is a no-op when the licensing timer is already zero`() {
        val mapper = newMapper68(prg16k = 4)
        // Don't arm the timer.
        repeat(100) { mapper.tickCpuCycle() }
        assertThat(mapper.snapshot().registers["licensingTimer"]!!, equalTo(0))
        // Page 0 stays mapped normally.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `writes to PRG-RAM also arm the licensing timer per Mesen2`() {
        // Mesen2's WriteRam arms the timer regardless of whether the
        // byte is meaningful; we mirror that. Page 0 should be unmapped
        // after any $6000-$7FFF write.
        val mapper = newMapper68(prg16k = 4)
        mapper.cpuWrite(0x7FFF, 0x00.toByte())
        mapper.dataBus = 0x55.toByte()
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0x55))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips banking and PRG-RAM contents`() {
        val original = newMapper68(prg16k = 8, chr2k = 16, batteryFlag = true)
        original.cpuWrite(0x8000, 0x05)
        original.cpuWrite(0x9000, 0x06)
        original.cpuWrite(0xA000, 0x07)
        original.cpuWrite(0xB000, 0x09)
        original.cpuWrite(0xE000, 0x01)         // horizontal mirroring
        original.cpuWrite(0xF000, 0x13)         // bank 3, PRG-RAM enabled
        original.cpuWrite(0x6000, 0x42.toByte()) // write PRG-RAM + arm licensing

        // Snapshot the original BEFORE saving so we can compare banking
        // and register state (excluding the licensing timer, which we tick
        // down on the restored side).
        val originalSnapshot = original.snapshot()

        val bytes = ByteArrayOutputStream()
            .also { original.saveState(DataOutputStream(it)) }
            .toByteArray()

        val restored = newMapper68(prg16k = 8, chr2k = 16, batteryFlag = true)
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        // The original PRG-RAM write armed the licensing IC; the timer is
        // part of the saved state. Run it down so $8000 reads return the
        // bank bytes again instead of open-bus.
        repeat(1024 * 105 + 1) { restored.tickCpuCycle() }

        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(restored.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat(restored.ppuRead(0x0800).toUnsignedInt(), equalTo(6))
        assertThat(restored.ppuRead(0x1000).toUnsignedInt(), equalTo(7))
        assertThat(restored.ppuRead(0x1800).toUnsignedInt(), equalTo(9))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        // PRG-RAM reads still need the enable bit set after restore.
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        // Snapshot equality on banking — registers equality is checked
        // field-by-field below because we mutate `licensingTimer` by
        // ticking it down on the restored side.
        assertThat(restored.snapshot().banks, equalTo(originalSnapshot.banks))
        val restoredRegs = restored.snapshot().registers
        val originalRegs = originalSnapshot.registers
        listOf("mirroringMode", "useChrForNametables", "usingExternalRom",
               "prgRamEnabled", "ntReg0", "ntReg1").forEach { key ->
            assertThat("register $key round-tripped",
                restoredRegs[key], equalTo(originalRegs[key]))
        }
    }
}