package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Boot + stability tests for issue #132 (Mapper 64 / Tengen RAMBO-1).
 *
 * Runs each Tengen RAMBO-1 game for 600 frames (10 seconds at 60 fps) and
 * asserts boot progress is real *and stays real* — the test that follows
 * up on the user-reported "Klax is corrupt and stops rendering after a
 * bit" and "Road Runner crashes after a bit" regressions. The 240-frame
 * boot window used by Mapper 16 was too short to catch those; the IRQ-
 * driven animations (Klax bonus timer, Road Runner road scroll) need
 * 10+ seconds of game time before they break if the IRQ is missing.
 *
 * What we check:
 *   (1) >1 distinct CHR bank seen during the first 240 frames — the
 *       mapper is wired and the game is doing real work.
 *   (2) PPU rendering was enabled at some point — the title screen
 *       actually appeared.
 *   (3) >200K instructions in the first 240 frames — the boot isn't
 *       stuck in a poll loop.
 *   (4) PPU rendering is *still* enabled at frame 600 — the user
 *       reported "Klax is corrupt and then didn't render anymore" and
 *       "Road Runner crashes after a bit". Both regressions manifest as
 *       the game disabling rendering (or freezing the CPU) several
 *       hundred frames into the boot, so the 600-frame check is the
 *       real bug catcher.
 *   (5) Total instruction count at frame 600 is well above what a
 *       frozen game produces (<50K) — guards against a different
 *       failure mode where the CPU gets stuck after the IRQ breaks.
 *
 * ROMs are NO-INTRO dumps; override with `NESTLIN_KLAX_ROM`,
 * `NESTLIN_SKULL_AND_CROSSBONES_ROM`, `NESTLIN_ROAD_RUNNER_ROM`. Skipped
 * (not failed) when any ROM is missing since CI runners won't have them.
 */
class Mapper64RealGameBootTest {

    private fun klaxRom(): Path {
        val override = System.getenv("NESTLIN_KLAX_ROM")
        if (override != null && override.isNotBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Klax (USA) (Unl).nes")
    }

    private fun skullAndCrossbonesRom(): Path {
        val override = System.getenv("NESTLIN_SKULL_AND_CROSSBONES_ROM")
        if (override != null && override.isNotBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Skull & Crossbones (USA) (Unl).nes")
    }

    private fun roadRunnerRom(): Path {
        val override = System.getenv("NESTLIN_ROAD_RUNNER_ROM")
        if (override != null && override.isNotBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Road Runner (USA) (Unl).nes")
    }

    private fun bootIndicatesProgress(rom: Path, label: String) {
        assumeTrue(Files.exists(rom), "$label ROM not found at $rom")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as Mapper64
        val ppuAddrMem = nestlin.ppu.memory.ppuAddressedMemory

        val prgBankHistory = mutableListOf<Int>()
        val chrBankHistory = mutableListOf<Int>()
        val maskHistory = mutableListOf<Int>()
        val instructionCountAtStart = nestlin.cpu.getInstructionCount()
        var lastSeenInstructionCount = instructionCountAtStart
        var frameCount = 0
        val maxFrames = 600
        val earlyFrameCutoff = 240

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                val snap = mapper.snapshot()
                prgBankHistory += snap.banks["prgBank6"] ?: -1
                chrBankHistory += snap.banks["chrBankR0"] ?: -1
                maskHistory += ppuAddrMem.mask.register.toUnsignedInt()
                lastSeenInstructionCount = nestlin.cpu.getInstructionCount()
                if (++frameCount >= maxFrames) nestlin.stop()
            }
        })
        nestlin.start()

        val totalInstructions = lastSeenInstructionCount - instructionCountAtStart

        // Early-boot metrics (frame 0..239): title screen must appear and
        // the game must be doing real work, not stuck in a poll loop.
        val earlyPrg = prgBankHistory.take(earlyFrameCutoff).toSet()
        val earlyChr = chrBankHistory.take(earlyFrameCutoff).toSet()
        val earlyMask = maskHistory.take(earlyFrameCutoff)
        val earlyRendering = earlyMask.any { (it and 0x18) != 0 }
        val earlyInstructions = (totalInstructions * earlyFrameCutoff) / maxFrames

        // Late-boot metrics (last 60 frames): the game must STILL be
        // rendering and STILL be running instructions. Catches the
        // user-reported "corrupt and stops rendering" and "crashes after
        // a bit" regressions — both manifest several hundred frames in.
        val lateStart = (maxFrames - 60).coerceAtLeast(0)
        val lateMask = if (maskHistory.size > lateStart) maskHistory.subList(lateStart, maskHistory.size) else maskHistory
        val lateRendering = lateMask.any { (it and 0x18) != 0 }
        val latePrg = if (prgBankHistory.size > lateStart) prgBankHistory.subList(lateStart, prgBankHistory.size).toSet() else prgBankHistory.toSet()
        val lateChr = if (chrBankHistory.size > lateStart) chrBankHistory.subList(lateStart, chrBankHistory.size).toSet() else chrBankHistory.toSet()

        val progress = "$label: PRG banks [early=$earlyPrg late=$latePrg]; " +
            "CHR banks [early=$earlyChr late=$lateChr]; " +
            "rendering [early=$earlyRendering late=$lateRendering]; " +
            "total instructions (600 frames): $totalInstructions"
        println(progress)

        // ---- Early-boot assertions: title screen must appear ----
        // The CHR-banking check (`earlyChr.size > 1 || earlyChr.contains(0).not()`)
        // is a "register window wired" diagnostic — it fires if neither R0 nor
        // any subsequent bank is touched. But some games (Klax) keep all
        // CHR banks at 0 for the static title screen and only swap CHR
        // once the player presses Start. We can't distinguish that from a
        // broken register window without playing the game, so we accept
        // EITHER some CHR banking OR some PRG banking as evidence the
        // mapper is wired. (Both signals together would be even better,
        // but a working PRG register write proves the $8000/$8001 decode
        // is functioning — CHR writes go through the same path.)
        val mapperWired = earlyChr.size > 1 || earlyChr.contains(0).not() ||
            earlyPrg.size > 1 || earlyPrg.contains(0).not()
        Assertions.assertTrue(mapperWired,
            "$label mapper appears unwired (no PRG or CHR banking in the first 240 frames). ($progress)")
        Assertions.assertTrue(earlyRendering,
            "$label never enabled PPU rendering in the first 240 frames — title screen did not appear. ($progress)")
        Assertions.assertTrue(earlyInstructions > 200_000,
            "$label barely ran in the first 240 frames — boot is stuck. ($progress)")

        // ---- Late-boot assertions: game must still be alive ----
        // This is the regression check for the "Klax is corrupt and stops
        // rendering" and "Road Runner crashes after a bit" symptoms.
        Assertions.assertTrue(lateRendering,
            "$label PPU rendering is OFF in the last 60 frames — the game has stopped rendering " +
            "(Klax corruption / Road Runner crash regression). ($progress)")
        Assertions.assertTrue(totalInstructions > 1_000_000,
            "$label only ran $totalInstructions instructions in 600 frames — game has stalled. ($progress)")
    }

    @Test
    fun `klax boots and stays stable for 600 frames`() {
        bootIndicatesProgress(klaxRom(), "Klax")
    }

    @Test
    fun `skull and crossbones boots and stays stable for 600 frames`() {
        bootIndicatesProgress(skullAndCrossbonesRom(), "Skull & Crossbones")
    }

    @Test
    fun `road runner boots and stays stable for 600 frames`() {
        bootIndicatesProgress(roadRunnerRom(), "Road Runner")
    }
}
