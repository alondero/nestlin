package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Regression guard for *why* Mapper64 must implement the full RAMBO-1
 * decode rather than inheriting MMC3's. Klax exercises the three features
 * that the original MMC3-derived implementation got wrong, leaving a blank
 * screen:
 *   - register select bits 0-3 (R8/R9/RF) vs MMC3's bits 0-2 (R0-R7)
 *   - bit 5 (K) "1 KB CHR mode" (adds R8/R9)
 *   - the third PRG bank RF (register 0x0F)
 *
 * Decorates the live Mapper64, records every $8000 (bank select) write,
 * and asserts Klax actually touches all three. If a future refactor drops
 * any of them, this fails loudly with the captured usage in
 * `build/reports/klax-bank-trace.txt`.
 */
class Mapper64KlaxBankTraceTest {

    @Test
    fun `klax bank-select register usage`() {
        val rom = Paths.get("S:/Media/Nintendo NES/Games/Klax (USA) (Unl).nes")
        assumeTrue(Files.exists(rom), "Klax ROM not found at $rom")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val decorator = Trace(nestlin.memory.mapper!!)
        nestlin.memory.mapper = decorator

        var frames = 0
        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                if (++frames >= 120) nestlin.stop()
            }
        })
        nestlin.start()

        val selects = decorator.bankSelects
        val regs = selects.map { it and 0x0F }.toSortedSet()
        val anyK = selects.any { (it and 0x20) != 0 }
        val anyRF = selects.any { (it and 0x0F) == 0x0F }
        val anyR8R9 = selects.any { (it and 0x0F) in 8..9 }
        val prgModes = selects.map { (it and 0x40) shr 6 }.toSortedSet()
        val inverts = selects.map { (it and 0x80) shr 7 }.toSortedSet()

        val report = buildString {
            appendLine("=== Klax RAMBO-1 bank-select usage (120 frames) ===")
            appendLine("total 0x8000 writes: ${selects.size}")
            appendLine("distinct register selects (bits 0-3): $regs")
            appendLine("uses K (1KB CHR mode, bit 5): $anyK")
            appendLine("uses RF (third PRG bank, reg 0x0F): $anyRF")
            appendLine("uses R8/R9 (extra 1KB CHR): $anyR8R9")
            appendLine("PRG modes seen (bit 6): $prgModes")
            appendLine("CHR invert seen (bit 7): $inverts")
        }
        val out = Paths.get("build/reports/klax-bank-trace.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
        println(report)

        assertTrue(anyRF, "Klax must use the third PRG bank R15 — see $out\n$report")
        assertTrue(anyK, "Klax must use 1KB CHR mode (K) — see $out\n$report")
        assertTrue(anyR8R9, "Klax must use the extra CHR banks R8/R9 — see $out\n$report")
    }

    private class Trace(private val wrapped: Mapper) : Mapper by wrapped {
        val bankSelects = mutableListOf<Int>()
        override fun cpuWrite(address: Int, value: Byte) {
            // RAMBO-1 bank select = $8000-$9FFF, even address.
            if (address in 0x8000..0x9FFF && (address and 1) == 0) {
                bankSelects += value.toInt() and 0xFF
            }
            wrapped.cpuWrite(address, value)
        }
    }
}
