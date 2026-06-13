package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper113
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Structured-state regression test for issue #139 (Mapper 113 / HES NTD-8).
 *
 * Two-pronged verification, mirroring `Mapper33RegressionTest` and
 * `Mapper10RegressionTest`:
 *  1. The Mind Blower Pak boot code actually moves the PRG/CHR bank
 *     registers during boot — proves the $4100+ chip-select gate is
 *     wired and the chip isn't stuck at its reset state.
 *  2. Nestlin's CHR banks are byte-identical to Mesen2's at frame N —
 *     proves the full PRG-bank + CHR-bank + mirroring interaction is
 *     correct. Mind Blower Pak is a 6-in-1 HES multicart with a
 *     self-replicating boot trampoline at $FFE0 in bank 1 (the IRQ
 *     handler in bank 1 has to break out by modifying the JMP target),
 *     so a CHR-only comparison is the right scope: the boot logic
 *     upstream of PPU rendering is game-specific and not the mapper's
 *     job.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override
 * with `NESTLIN_MIND_BLOWER_PAK_ROM`; otherwise we fall back to the
 * canonical path on Adam's machine. Skipped (not failed) when neither
 * the ROM nor Mesen2 is present, since CI runners won't have either.
 */
class Mapper113RegressionTest {

    private val frameNumber = 60

    private fun mindBlowerPakRom(): Path {
        val override = System.getenv("NESTLIN_MIND_BLOWER_PAK_ROM")
        if (!override.isNullOrBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Mind Blower Pak (Australia) (Unl).nes")
    }

    @Test
    fun `hes ntd-8 prg and chr banks move during mind blower pak boot`() {
        val rom = mindBlowerPakRom()
        assumeTrue(Files.exists(rom), "Mind Blower Pak ROM not found at $rom")

        val nestlin = com.github.alondero.nestlin.Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as? Mapper113
            ?: error("Expected Mapper113 for Mind Blower Pak; got ${nestlin.memory.mapper?.javaClass?.simpleName}")

        val prgSeen = linkedSetOf<Int>()
        val chrSeen = linkedSetOf<Int>()
        var frameCount = 0
        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                val snap = mapper.snapshot()
                prgSeen.add(snap.banks["prgBank"] ?: -1)
                chrSeen.add(snap.banks["chrBank"] ?: -1)
                if (++frameCount >= frameNumber) nestlin.stop()
            }
        })
        nestlin.start()

        // The HES NTD-8 boot sequence (verified by `Mapper113BootDiagnosticTest`)
        // does: bank 0 reset vector -> copy-loop trampoline -> BRK at $59A9
        // (because Mapper113 returns 0 for <$8000) -> IRQ handler in bank 0
        // does the textbook NES init -> $4100 write with value $59 to switch
        // to PRG bank 5, CHR bank 9, then JMP to $FFE0 in bank 5 ->
        // bank 5's $FFE0 is a SECOND trampoline that re-writes 0x1D to $4120
        // (PRG=1, CHR=13) and JMPs via the bank-1 reset vector. So the
        // diagnostic should see at least PRG 1 and CHR 13 by frame 60.
        Assertions.assertTrue(
            prgSeen.size > 1 || prgSeen.firstOrNull()?.let { it != 0 } == true,
            "HES NTD-8 PRG bank never changed during boot " +
                "(prg seen: $prgSeen) — the \$4100+ chip-select gate may not be wired."
        )
        Assertions.assertTrue(
            chrSeen.size > 1 || chrSeen.firstOrNull()?.let { it != 0 } == true,
            "HES NTD-8 CHR bank never changed during boot " +
                "(chr seen: $chrSeen) — the \$4100+ chip-select gate may not be wired."
        )
    }

    /**
     * Render-output state diff against Mesen2 (per `Mapper33RegressionTest`'s
     * worked example and `mesen2-capturer-instant-offset-...` memory).
     *
     * Mesen2's `endFrame` fires at scanline 240, Nestlin's frame callback
     * fires at scanline 261, so CPU regs / cycle counts / scanline are
     * inherently non-comparable across the two. Render outputs (CHR, OAM,
     * palette, PPUCTRL, PPUMASK) are stable across that offset and are
     * exactly what a banking bug corrupts.
     *
     * The HES NTD-8 multicart's boot is complex (see kdoc on the
     * prg-bank test above); if Nestlin's boot gets stuck in a loop before
     * the title screen renders, the CHR bytes at frame 60 will reflect
     * whatever CHR bank 13 has been latched at, not the title screen.
     * The diff report will surface exactly which bytes diverge.
     */
    @Test
    fun `mind blower pak chr banks match mesen2 at frame N`() {
        val rom = mindBlowerPakRom()
        assumeTrue(Files.exists(rom), "Mind Blower Pak ROM not found at $rom")
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")

        val reportsDir = Paths.get("build/reports/state-diffs/mind-blower-pak-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        Files.createDirectories(reportsDir)
        Files.writeString(reportsDir.resolve("nestlin-state.json"), nestlinState.toJson())
        Files.writeString(reportsDir.resolve("mesen2-state.json"), mesen2State.toJson())
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // The mapper's job is PRG + CHR banking. The other render outputs
        // (OAM, palette, PPUCTRL, PPUMASK) are reported alongside for human
        // inspection but are NOT asserted here — see the kdoc for the
        // cross-emulator NMI/OAM offset rationale.
        val chrDiffs = (nestlinState.chr.indices).filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a mapper bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("Mind Blower Pak frame $frameNumber CHR banks: MATCH (8KB across the 8KB CHR window)")
    }
}
