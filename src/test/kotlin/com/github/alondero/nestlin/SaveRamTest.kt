package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.gamepak.Mapper1
import com.github.alondero.nestlin.gamepak.Mapper4
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SaveRamTest {

    // JUnit 5's @TempDir replaces JUnit 4's @Rule TemporaryFolder. The Path
    // form lets us use Files.createDirectory (replacing TemporaryFolder.newFolder)
    // and resolve() directly.
    @TempDir
    lateinit var tempFolder: Path

    /** Build a minimal valid iNES-1.0 image. flags6 bit 1 = battery flag. */
    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int, battery: Boolean): ByteArray {
        require(prg.size % 0x4000 == 0) { "PRG must be a multiple of 16KB" }
        require(chr.size == 0 || chr.size % 0x2000 == 0) { "CHR must be a multiple of 8KB" }
        val flags6 = ((mapperId and 0x0F) shl 4) or (if (battery) 0x02 else 0)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            this[6] = flags6.toByte()
            this[7] = (mapperId and 0xF0).toByte()
        }
        return header + prg + chr
    }

    private fun batteryMmc1GamePak(): GamePak {
        // MMC1 with two 16KB PRG banks, 8KB CHR, battery flag set
        return GamePak(buildIne(ByteArray(0x4000 * 2), ByteArray(0x2000), mapperId = 1, battery = true))
    }

    private fun nonBatteryMmc1GamePak(): GamePak {
        return GamePak(buildIne(ByteArray(0x4000 * 2), ByteArray(0x2000), mapperId = 1, battery = false))
    }

    private fun nonBatteryMapper0GamePak(): GamePak {
        return GamePak(buildIne(ByteArray(0x4000 * 2), ByteArray(0x2000), mapperId = 0, battery = false))
    }

    @Test
    fun `header parses battery flag from flags6 bit 1`() {
        assertTrue(batteryMmc1GamePak().header.hasBattery)
        assertFalse(nonBatteryMmc1GamePak().header.hasBattery)
    }

    @Test
    fun `mapper without PRG-RAM returns null from batteryBackedRam`() {
        val mapper = nonBatteryMapper0GamePak().createMapper()
        assertNull(mapper.batteryBackedRam())
    }

    @Test
    fun `mapper1 exposes its 8KB PRG-RAM through batteryBackedRam`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        val ram = mapper.batteryBackedRam()
        assertThat(ram?.size, equalTo(0x2000))
    }

    @Test
    fun `writes to PRG-RAM set batteryDirty on mapper1`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        assertFalse(mapper.batteryDirty)
        mapper.cpuWrite(0x6000, 0x42.toByte())
        assertTrue(mapper.batteryDirty)
    }

    @Test
    fun `writes outside PRG-RAM do not set batteryDirty`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        // $8000+ goes through the MMC1 shift register, not PRG-RAM
        mapper.cpuWrite(0x8000, 0x00.toByte())
        assertFalse(mapper.batteryDirty)
    }

    @Test
    fun `Mapper4 write while write-protected does not set batteryDirty`() {
        val gp = GamePak(buildIne(ByteArray(0x4000 * 2), ByteArray(0x2000), mapperId = 4, battery = true))
        val mapper = gp.createMapper() as Mapper4
        // Enable PRG-RAM but write-protect it: $A001 odd address, bit 7 enable, bit 6 protect
        mapper.cpuWrite(0xA000, 0x00.toByte())          // even = mirroring (don't care)
        mapper.cpuWrite(0xA001, 0xC0.toByte())          // odd = enable + write-protect
        assertFalse(mapper.batteryDirty)
        mapper.cpuWrite(0x6000, 0x42.toByte())
        assertFalse(mapper.batteryDirty, "write-protected write should not mark dirty")
    }

    @Test
    fun `save then load round-trips PRG-RAM byte-for-byte`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        // Stamp a recognisable pattern
        for (i in 0 until 0x2000) {
            mapper.cpuWrite(0x6000 + i, ((i xor 0xA5) and 0xFF).toByte())
        }
        val savPath = Files.createDirectory(tempFolder.resolve("saves")).resolve("game.sav")
        SaveRam.save(savPath, mapper)
        assertTrue(Files.exists(savPath))
        assertFalse(mapper.batteryDirty, "save clears the dirty flag")

        // Fresh mapper, load the same .sav
        val fresh = batteryMmc1GamePak().createMapper() as Mapper1
        SaveRam.load(savPath, fresh)
        assertArrayEquals(mapper.batteryBackedRam(), fresh.batteryBackedRam())
        assertFalse(fresh.batteryDirty, "load also clears the dirty flag")
    }

    @Test
    fun `save on mapper without PRG-RAM writes no file`() {
        val mapper = nonBatteryMapper0GamePak().createMapper()
        val savPath = Files.createDirectory(tempFolder.resolve("saves")).resolve("game.sav")
        SaveRam.save(savPath, mapper)
        assertFalse(Files.exists(savPath))
    }

    @Test
    fun `load with size mismatch is rejected and PRG-RAM is untouched`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        // Stamp a pattern; load should not overwrite it
        for (i in 0 until 0x2000) mapper.cpuWrite(0x6000 + i, 0x7E.toByte())
        val original = mapper.batteryBackedRam()!!.copyOf()

        val savPath = Files.createDirectory(tempFolder.resolve("saves")).resolve("game.sav")
        Files.write(savPath, ByteArray(123))  // wrong size

        SaveRam.load(savPath, mapper)
        assertArrayEquals(original, mapper.batteryBackedRam())
    }

    @Test
    fun `load on missing file is a no-op`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        val savPath = Files.createDirectory(tempFolder.resolve("saves")).resolve("does-not-exist.sav")
        SaveRam.load(savPath, mapper)
        // No throw is the assertion
    }

    @Test
    fun `save leaves no tmp file behind`() {
        val mapper = batteryMmc1GamePak().createMapper() as Mapper1
        mapper.cpuWrite(0x6000, 0x01.toByte())
        val savDir = Files.createDirectory(tempFolder.resolve("saves"))
        val savPath = savDir.resolve("game.sav")
        SaveRam.save(savPath, mapper)

        Files.list(savDir).use { stream ->
            val files = stream.map { it.fileName.toString() }.toList()
            assertTrue(files.size == 1 && files[0] == "game.sav", "expected only game.sav, got $files")
        }
    }
}
