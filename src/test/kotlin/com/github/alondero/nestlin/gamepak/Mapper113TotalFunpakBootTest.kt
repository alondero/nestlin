package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Smoke test for the OTHER HES Australia Mapper 113 game — *Total Funpak* —
 * to prove the new "power-on = last bank" default is correct for both
 * titles, not just Mind Blower Pak.
 *
 * If Total Funpak boots cleanly (PPU enabled, OAM populated, PC
 * navigating in real game code), the chip's power-on state is the last
 * bank. If it gets stuck in a trampoline (the symmetric Mind Blower Pak
 * failure mode), the mapper still has a bug.
 */
class Mapper113TotalFunpakBootTest {

    private fun romPath(): Path {
        val override = System.getenv("NESTLIN_TOTAL_FUNPAK_ROM")
        if (!override.isNullOrBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Total Funpak (Australia) (Unl).nes")
    }

    @Test
    fun `total funpak boot reaches real game code past the mapper power-on state`() {
        val rom = romPath()
        assumeTrue(Files.exists(rom), "ROM not found at $rom")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val ppu = nestlin.memory.ppuAddressedMemory
        val oam = ppu.objectAttributeMemory

        val maxFrames = 240
        var frameCount = 0
        val oamNonZero = mutableSetOf<Int>()
        val visitedPrgBanks = mutableSetOf<Int>()
        val visitedChrBanks = mutableSetOf<Int>()
        var ppuEnabledAt = -1
        var distinctPcs = mutableSetOf<Int>()

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                val ppuMask = ppu.mask.register.toUnsignedInt()
                val mapper = nestlin.memory.mapper as Mapper113
                val snap = mapper.snapshot()
                val prg = snap.banks["prgBank"] ?: -1
                val chr = snap.banks["chrBank"] ?: -1
                visitedPrgBanks += prg
                visitedChrBanks += chr
                distinctPcs += pc
                for (i in 0 until 256) {
                    if (oam[i].toUnsignedInt() != 0) oamNonZero += i
                }
                // PPU enabled = bg or sprites visible (mask bit 3 or 4)
                if (ppuEnabledAt < 0 && (ppuMask and 0x18) != 0) ppuEnabledAt = frameCount
                frameCount++
                if (frameCount >= maxFrames) nestlin.stop()
            }
        })
        nestlin.start()

        val finalMask = ppu.mask.register.toUnsignedInt()
        val finalCtrl = ppu.controller.register.toUnsignedInt()
        println(
            "[DEBUG-tf-boot] FINAL frame=$frameCount ppumask=$${finalMask.toString(16).padStart(2, '0')} " +
                "ppuctrl=$${finalCtrl.toString(16).padStart(2, '0')} oamNonZero=${oamNonZero.size} " +
                "prgSeen=$visitedPrgBanks chrSeen=$visitedChrBanks ppuEnabledAt=$ppuEnabledAt " +
                "distinctPcs=${distinctPcs.size}"
        )

        if (ppuEnabledAt < 0) {
            throw AssertionFailedError(
                "PPU never enabled by frame $maxFrames. The game is stuck " +
                    "before enabling rendering — likely a power-on bank " +
                    "bug. PPUCTRL=$${finalCtrl.toString(16).padStart(2, '0')} " +
                    "PPUMASK=$${finalMask.toString(16).padStart(2, '0')}"
            )
        }
        if (oamNonZero.isEmpty()) {
            throw AssertionFailedError("No sprites written to OAM by frame $maxFrames")
        }
        // The PC should not be stuck in a 2-instruction loop. If the game
        // is alive, the frame-end PC will change as NMI fires, code
        // runs, and the CPU advances. A tight trampoline loop would have
        // PC staying within a 4-byte window across all 240 frames; a
        // healthy boot sees hundreds of distinct PC values.
        if (distinctPcs.size < 10) {
            throw AssertionFailedError(
                "PC only visited ${distinctPcs.size} distinct address(es) " +
                    "in $maxFrames frames — the game looks stuck in a tight " +
                    "loop. Sample PCs: ${distinctPcs.take(8)}"
            )
        }
    }
}
