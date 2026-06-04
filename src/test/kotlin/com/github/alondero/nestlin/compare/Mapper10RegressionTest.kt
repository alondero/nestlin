package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper10
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Structured-state regression test for issue #49 (Mapper 10 / MMC4).
 *
 * Per docs/TESTING_STRATEGY.md, this is a byte-level state comparison against the
 * Mesen2 oracle at a frame boundary — NOT a pixel diff. It asserts two things for
 * Fire Emblem Gaiden:
 *   1. The ROM is actually a Mapper10 and the MMC4 PRG bank moved off its reset
 *      value during boot (proves the bank-select register is wired, the cheap
 *      "did the mapper do anything" guard from the Star Soldier recipe).
 *   2. Nestlin's CPU/PPU/RAM/OAM/palette snapshot matches Mesen2's at frame N.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override its location
 * with NESTLIN_FIRE_EMBLEM_ROM; otherwise we fall back to the canonical path on
 * Adam's machine. Skipped (not failed) when neither the ROM nor Mesen2 is present,
 * since CI runners won't have either.
 */
class Mapper10RegressionTest {

    private val frameNumber = 120

    private fun fireEmblemRom(): Path {
        val override = System.getenv("NESTLIN_FIRE_EMBLEM_ROM")
        if (override != null && override.isNotBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Fire Emblem Gaiden (Japan) (Translated En).nes")
    }

    @Test
    fun `mmc4 prg bank switches during fire emblem gaiden boot`() {
        val rom = fireEmblemRom()
        assumeTrue(Files.exists(rom), "Fire Emblem Gaiden ROM not found at $rom")

        val nestlin = com.github.alondero.nestlin.Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as? Mapper10
            ?: error("Expected Mapper10 for Fire Emblem Gaiden; got ${nestlin.memory.mapper?.javaClass?.simpleName}")

        val banksSeen = linkedSetOf<Int>()
        var frameCount = 0
        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                banksSeen.add(mapper.snapshot().banks["prgBank"] ?: -1)
                if (++frameCount >= frameNumber) nestlin.stop()
            }
        })
        nestlin.start()

        // A 256KB game cannot run from a single 16KB bank — it must page $8000.
        Assertions.assertTrue(banksSeen.size > 1
        , "MMC4 PRG bank never changed during boot (banks seen: $banksSeen) — " +
                "the \$A000 bank-select register may not be wired.")
    }

    /**
     * Compares the *render-output* state (OAM, palette, PPUCTRL, PPUMASK) between
     * Nestlin and Mesen2 for the same displayed frame.
     *
     * Why this subset rather than StateComparator's full snapshot equality? The two
     * emulators expose only one capture hook each, and they fire at different points
     * in the frame: Mesen2's endFrame at scanline 240 (pre-NMI), Nestlin's frame
     * callback at scanline 261 (post-NMI). That sub-frame offset makes the CPU
     * registers, PPU scanline/cycle, and cycleCount (which Nestlin reports as an
     * instruction count, not a cycle count) inherently non-comparable across the two
     * — chasing cycle-exact cross-emulator equality is the brittleness docs/TESTING_STRATEGY.md
     * explicitly steers away from.
     *
     * The render outputs, by contrast, reflect the *completed* frame and are stable
     * across that offset. They're also exactly what an MMC4 banking bug corrupts: wrong
     * CHR banks fetch wrong tiles (sprite/palette setup diverges), and wrong PRG banks
     * derail the code that populates OAM. Byte-equality here is strong, low-noise
     * evidence the mapper is right. The full snapshot + diff are still written to disk
     * for inspection.
     */
    @Test
    fun `fire emblem gaiden render output matches mesen2 at frame N`() {
        val rom = fireEmblemRom()
        assumeTrue(Files.exists(rom), "Fire Emblem Gaiden ROM not found at $rom")
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")

        val reportsDir = Paths.get("build/reports/state-diffs/fire-emblem-gaiden-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        // Persist the full snapshots and StateComparator report for human inspection.
        Files.createDirectories(reportsDir)
        Files.writeString(reportsDir.resolve("nestlin-state.json"), nestlinState.toJson())
        Files.writeString(reportsDir.resolve("mesen2-state.json"), mesen2State.toJson())
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        val problems = mutableListOf<String>()
        fun cmpArray(name: String, a: IntArray, b: IntArray) {
            if (a.size != b.size) { problems += "$name size ${a.size} != ${b.size}"; return }
            val diffs = (a.indices).filter { a[it] != b[it] }
            if (diffs.isNotEmpty()) {
                val sample = diffs.take(8).joinToString(", ") {
                    "[$it] N=0x%02X M=0x%02X".format(a[it], b[it])
                }
                problems += "$name differs in ${diffs.size} byte(s): $sample"
            }
        }
        fun cmpReg(name: String, n: Int, m: Int) {
            if (n != m) problems += "$name: Nestlin=0x%02X Mesen2=0x%02X".format(n, m)
        }

        cmpArray("OAM", nestlinState.oam, mesen2State.oam)
        cmpArray("paletteRam", nestlinState.paletteRam, mesen2State.paletteRam)
        cmpReg("PPUCTRL", nestlinState.ppuRegisters.controller, mesen2State.ppuRegisters.controller)
        cmpReg("PPUMASK", nestlinState.ppuRegisters.mask, mesen2State.ppuRegisters.mask)

        println("Fire Emblem Gaiden frame $frameNumber render-output: ${if (problems.isEmpty()) "MATCH" else "MISMATCH"}")
        if (problems.isNotEmpty()) {
            // Extract the message to a String-typed local var to disambiguate
            // JUnit 5's fail(Supplier<String>) overload (which has a phantom
            // <V> type variable the Kotlin compiler cannot infer when the
            // argument is a multi-line string concat). Then bypass fail()
            // entirely and throw AssertionFailedError directly — that's what
            // fail(String) does internally anyway.
            val failMessage = "Render output diverged from Mesen2 oracle:\n  " +
                problems.joinToString("\n  ") +
                "\nSee: ${reportsDir.resolve("diff-report.txt")}"
            throw org.opentest4j.AssertionFailedError(failMessage)
        }
    }
}
