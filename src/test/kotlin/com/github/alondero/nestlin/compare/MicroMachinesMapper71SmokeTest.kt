package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper71
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * End-to-end smoke test for Mapper 71 (Camerica / Codemasters, BF909x) on
 * Micro Machines (USA) (Unl). The ROM is on the NO-INTRO library at
 * `S:/Media/Nintendo NES/Games` and advertises mapper 71 in iNES byte 6/7.
 *
 * What we're verifying:
 *  1. The mapper dispatch correctly recognises the header and instantiates
 *     `Mapper71` (not a fallback / throw).
 *  2. The game's PRG-bank switching doesn't deadlock Nestlin — the CPU
 *     executes many millions of instructions across the boot.
 *  3. The PPU reaches a state where the game has enabled rendering
 *     (PPUMASK has background/sprite-enable bits set).
 *  4. The framebuffer has more than a handful of distinct colours, i.e. the
 *     game has actually drawn something (not a black or single-colour screen).
 *  5. The mapper's `prgBank` snapshot stays within the 16-bank PRG ROM
 *     (`[0, prgBankCount)`) — catches an out-of-bounds read from a wrong
 *     modulo or mask.
 *
 * Skips loudly if the ROM is not on this machine, like the other smoke tests.
 */
class MicroMachinesMapper71SmokeTest {

    @Test
    fun `Micro Machines boots, renders, and stays within PRG-bank range`() {
        val rom = locateRom()
        assumeTrue(rom != null
        , "Micro Machines (USA) ROM not found at S:/Media/Nintendo NES/Games or " +
            "NESTLIN_MICRO_MACHINES_ROM. Skipping mapper 71 real-ROM validation.")

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        var frame = 0
        var maxMask = 0
        var firstRenderFrame = -1
        var totalInstructions = 0
        var pcFinal = 0
        var last: Frame? = null
        val mapperRef = java.util.concurrent.atomic.AtomicReference<Mapper71?>(null)
        val prgBankOutOfRange = java.util.concurrent.atomic.AtomicBoolean(false)

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++; last = f
                val m = nestlin.ppuMask()
                if (m > maxMask) maxMask = m
                if (firstRenderFrame < 0 && (m and 0x18) != 0) firstRenderFrame = frame

                // Snapshot the mapper state on every frame so we can verify the
                // bank stays in range across the whole boot.
                val mapper = nestlin.memory.mapper
                if (mapper is Mapper71) {
                    mapperRef.compareAndSet(null, mapper)
                    val snap = mapper.snapshot()
                    val prgBank = snap.banks["prgBank"] ?: 0
                    if (prgBank !in 0 until 16) prgBankOutOfRange.set(true)
                }
            }
        })

        nestlin.load(rom!!)
        nestlin.powerReset()

        // Sanity: the right mapper got selected for this header.
        val mapper = nestlin.memory.mapper
        assertTrue(mapper is Mapper71
        , "Expected Mapper71, got ${mapper?.javaClass?.simpleName ?: "null"} " +
            "(mapper id in header = ${mapper?.let { mapper.javaClass.simpleName }})")

        // Tick ~240 frames with a guard ceiling. The actual loop terminates
        // when `frame` hits 240; the guard is a safety net so a runaway mapper
        // (e.g. PC stuck in a tight loop with no PPU progress) can't hang the
        // test forever — we'll catch it in the assertions instead.
        var guard = 240L * 80_000
        while (frame < 240 && guard-- > 0) {
            nestlin.stepCpuCycle()
        }

        totalInstructions = nestlin.cpu.getInstructionCount()
        pcFinal = nestlin.cpu.registers.programCounter.toUnsignedInt()

        val colors = last?.let { f -> f.scanlines.flatMap { it.asList() }.toHashSet().size } ?: 0
        val prgBankFinal = mapperRef.get()?.snapshot()?.banks?.get("prgBank") ?: 0
        val prgBankCount = mapperRef.get()?.let {
            // 16 KB banks, 256 KB ROM -> 16 banks. Pull from the snapshot's
            // type description; this is informational, not a hard assert.
            16
        } ?: 16

        println(
            "[MicroMachines] frame=$frame maxMask=0x%02X firstRenderFrame=$firstRenderFrame " +
            "colors=$colors prgBank=$prgBankFinal/$prgBankCount pcFinal=0x%04X instructions=$totalInstructions"
                .format(pcFinal)
        )

        assertTrue(maxMask and 0x18 != 0
        , "rendering never enabled (maxMask=0x%02X) — game did not reach a renderable state"
                .format(maxMask))
        assertTrue(colors > 4
        , "framebuffer essentially blank (only $colors distinct colours) — game did not draw content")
        assertTrue(totalInstructions > 100_000
        , "CPU appears stuck (only $totalInstructions instructions across $frame frames) — PRG banking may be wrong")
        assertFalse(prgBankOutOfRange.get()
        , "mapper prgBank drifted out of valid 16-bank range (likely a missing % modulo)")
    }

    private fun locateRom(): Path? {
        System.getenv("NESTLIN_MICRO_MACHINES_ROM")?.let {
            val p = Paths.get(it)
            if (Files.exists(p)) return p
        }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = Paths.get(lib)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                val match = stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("micro machines") && n.contains("usa")
                }
                if (match != null) return match
            }
        }
        return null
    }
}
