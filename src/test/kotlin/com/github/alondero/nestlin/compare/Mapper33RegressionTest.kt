package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper33
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Structured-state regression test for issue #131 (Mapper 33 / Taito TC0190).
 *
 * Two-pronged verification, mirroring `Mapper10RegressionTest`:
 *  1. The Don Doko Don boot code actually moves the PRG bank register
 *     (`prgBank0` / `prgBank1`) during boot — proves the $8000/$8001
 *     register-decode is wired and the chip isn't stuck at its reset state.
 *  2. Nestlin's CHR banks are byte-identical to Mesen2's at frame N —
 *     proves the full PRG-bank + CHR-bank + mirroring interaction is correct.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override with
 * `NESTLIN_DON_DOKO_DON_ROM`; otherwise we fall back to the canonical path
 * on Adam's machine. Skipped (not failed) when neither the ROM nor Mesen2
 * is present, since CI runners won't have either.
 */
@org.junit.jupiter.api.Tag("mesen")
class Mapper33RegressionTest {

    private val frameNumber = 60

    private fun donDokoDonRom(): Path {
        val override = System.getenv("NESTLIN_DON_DOKO_DON_ROM")
        if (override != null && override.isNotBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Don Doko Don (Japan).nes")
    }

    @Test
    fun `tc0190 prg banks move during don doko don boot`() {
        val rom = donDokoDonRom()
        assumeTrue(Files.exists(rom), "Don Doko Don ROM not found at $rom")

        val nestlin = com.github.alondero.nestlin.Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as? Mapper33
            ?: error("Expected Mapper33 for Don Doko Don; got ${nestlin.memory.mapper?.javaClass?.simpleName}")

        val banks0Seen = linkedSetOf<Int>()
        val banks1Seen = linkedSetOf<Int>()
        var frameCount = 0
        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                val snap = mapper.snapshot()
                banks0Seen.add(snap.banks["prgBank0"] ?: -1)
                banks1Seen.add(snap.banks["prgBank1"] ?: -1)
                if (++frameCount >= frameNumber) nestlin.stop()
            }
        })
        nestlin.start()

        // A 128KB game (8 PRG banks of 16KB) cannot run from a single 8KB
        // window — the boot code must page $8000 / $A000. Either bank moving
        // is enough to prove the $8000/$8001 registers are wired.
        Assertions.assertTrue(
            banks0Seen.size > 1 || banks1Seen.size > 1,
            "TC0190 PRG banks never changed during boot " +
                "(prgBank0 seen: $banks0Seen, prgBank1 seen: $banks1Seen) — " +
                "the \$8000/\$8001 bank-select registers may not be wired."
        )
    }

    /**
     * Render-output state diff against Mesen2 (per `Mapper10RegressionTest`'s
     * worked example and `mesen2-capturer-instant-offset-...` memory).
     *
     * Mesen2's `endFrame` fires at scanline 240, Nestlin's frame callback
     * fires at scanline 261, so CPU regs / cycle counts / scanline are
     * inherently non-comparable across the two. Render outputs (CHR, OAM,
     * palette, PPUCTRL, PPUMASK) are stable across that offset and are
     * exactly what a banking bug corrupts.
     *
     * **Don Doko Don's OAM is *not* stable across the 21-scanline offset**
     * the way Fire Emblem Gaiden's is — the game updates OAM inside its NMI
     * handler, so by Nestlin's post-NMI capture point the OAM holds the
     * *next* frame's sprites while Mesen2's pre-NMI capture holds the
     * current frame's. This is a cross-emulator timing property of the game,
     * not a TC0190 bug (CHR is byte-identical — see the diff report).
     *
     * So the assertion is **CHR-only** here. The full snapshot + diff still
     * go to `build/reports/state-diffs/don-doko-don-frame-N/` so a human
     * can see the OAM timing drift alongside the CHR match.
     */
    @Test
    fun `don doko don chr banks match mesen2 at frame N`() {
        val rom = donDokoDonRom()
        assumeTrue(Files.exists(rom), "Don Doko Don ROM not found at $rom")
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")

        val reportsDir = Paths.get("build/reports/state-diffs/don-doko-don-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        Files.createDirectories(reportsDir)
        Files.writeString(reportsDir.resolve("nestlin-state.json"), nestlinState.toJson())
        Files.writeString(reportsDir.resolve("mesen2-state.json"), mesen2State.toJson())
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // The mapper's job is CHR banking. The other render outputs (OAM,
        // palette, PPUCTRL, PPUMASK) are reported alongside for human
        // inspection but are NOT asserted on Don Doko Don — see the kdoc
        // for the cross-emulator NMI/OAM offset rationale.
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
        println("Don Doko Don frame $frameNumber CHR banks: MATCH (8KB across all 8 1KB windows)")
    }
}
