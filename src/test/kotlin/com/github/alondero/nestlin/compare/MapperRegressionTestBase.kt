package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Base class for per-mapper Mesen2-oracle regression tests (the
 * Mapper10RegressionTest pattern from docs/TESTING_STRATEGY.md, extracted).
 *
 * A typical subclass is ~25 lines: one test calling [assertBankSwitchesDuringBoot]
 * (the cheap "did the mapper do anything" guard) and one @[RequiresMesen2] test
 * calling [assertRenderOutputMatchesMesen2].
 *
 * ## Why only render outputs are compared against Mesen2
 *
 * The two emulators expose only one capture hook each, and they fire at different
 * points in the frame: Mesen2's endFrame at scanline 240 (pre-NMI), Nestlin's frame
 * callback at scanline 261 (post-NMI). That sub-frame offset makes the CPU
 * registers, PPU scanline/cycle, and cycleCount (which Nestlin reports as an
 * instruction count, not a cycle count) inherently non-comparable across the two —
 * chasing cycle-exact cross-emulator equality is the brittleness
 * docs/TESTING_STRATEGY.md explicitly steers away from.
 *
 * The render outputs (OAM, palette, PPUCTRL, PPUMASK), by contrast, reflect the
 * *completed* frame and are stable across that offset. They're also exactly what a
 * mapper banking bug corrupts: wrong CHR banks fetch wrong tiles (sprite/palette
 * setup diverges), and wrong PRG banks derail the code that populates OAM.
 * Byte-equality here is strong, low-noise evidence the mapper is right. The full
 * snapshots, the StateComparator diff, and the DivergenceLocalizer table are still
 * written to disk for inspection.
 */
// Lane: this base carries @Tag("mesen"); JUnit 5 inherits class-level tags to subclasses, so every
// MapperRegressionTestBase subclass is automatically in the Mesen2 comparison lane (excluded from
// `./gradlew test`, included by `./gradlew testMesenComparison`) with no per-class wiring and no
// build-script list to keep in sync. Annotate the render-output test method with @RequiresMesen2 so
// it skips loudly (naming the resolved path) when Mesen2 is absent.
@Tag("mesen")
abstract class MapperRegressionTestBase {

    /**
     * Resolves a test ROM: [envVar] override first, falling back to [defaultPath]
     * (typically an absolute path into the NO-INTRO library on Adam's machine).
     * Skips (assumption failure, not a test failure) when the ROM is absent, since
     * CI runners won't have the ROM library.
     */
    protected fun resolveRom(envVar: String, defaultPath: String): Path {
        val override = System.getenv(envVar)
        val rom = if (!override.isNullOrBlank()) Paths.get(override) else Paths.get(defaultPath)
        assumeTrue(Files.exists(rom), "ROM not found at $rom (override with $envVar)")
        return rom
    }

    /**
     * The cheap "did the mapper do anything" guard from the Star Soldier recipe:
     * asserts the ROM loads with a mapper of [mapperClass] and that the bank under
     * [bankKey] (a key of `MapperStateSnapshot.banks`) takes more than one value
     * during the first [frames] frames of boot — i.e. the bank-select register is
     * actually wired. A multi-bank game cannot run from a single fixed bank.
     */
    protected fun assertBankSwitchesDuringBoot(rom: Path, frames: Int, bankKey: String, mapperClass: Class<*>) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper
        if (!mapperClass.isInstance(mapper)) {
            error("Expected ${mapperClass.simpleName} for $rom; got ${mapper?.javaClass?.simpleName}")
        }

        val banksSeen = linkedSetOf<Int>()
        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                banksSeen.add(mapper!!.snapshot()?.banks?.get(bankKey) ?: -1)
                if (++frameCount >= frames) nestlin.stop()
            }
        })
        nestlin.start()

        Assertions.assertTrue(
            banksSeen.size > 1,
            "${mapperClass.simpleName} bank '$bankKey' never changed during boot " +
                "(banks seen: $banksSeen) — the bank-select register may not be wired."
        )
    }

    /**
     * Captures BOTH emulators at [frame], writes the full report set under
     * `build/reports/state-diffs/<reportName>-frame-<frame>/` (nestlin-state.json,
     * mesen2-state.json, diff-report.txt via StateComparator, and the
     * DivergenceLocalizer table + classifications in divergence-report.txt), then
     * byte-compares the render outputs — OAM, palette RAM, PPUCTRL, PPUMASK — and
     * throws AssertionFailedError on any mismatch. See the class KDoc for why only
     * this subset is compared.
     *
     * Callers should be annotated @[RequiresMesen2]; this helper assumes Mesen2 is
     * present and will fail (not skip) if it is missing.
     */
    protected fun assertRenderOutputMatchesMesen2(rom: Path, frame: Int, reportName: String) {
        val reportsDir = Paths.get("build/reports/state-diffs/$reportName-frame-$frame")

        val nestlinState = NestlinStateCapturer.captureState(rom, frame)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frame)

        // Persist the full snapshots, the StateComparator report, and the
        // DivergenceLocalizer table (which also rewrites the snapshot JSONs —
        // harmless, same content) for human inspection.
        Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))
        val divergence = DivergenceLocalizer.report(nestlinState, mesen2State, reportsDir)

        val problems = mutableListOf<String>()
        fun cmpArray(name: String, a: IntArray, b: IntArray) {
            if (a.size != b.size) { problems += "$name size ${a.size} != ${b.size}"; return }
            val diffs = a.indices.filter { a[it] != b[it] }
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

        println("$reportName frame $frame render-output: ${if (problems.isEmpty()) "MATCH" else "MISMATCH"}")
        if (problems.isNotEmpty()) {
            // JUnit 5's fail(Supplier<String>) overload has a phantom <V> type
            // variable Kotlin cannot infer for a multi-line string concat — bypass
            // fail() and throw AssertionFailedError directly (that's what
            // fail(String) does internally anyway).
            val failMessage = "Render output diverged from Mesen2 oracle:\n  " +
                problems.joinToString("\n  ") +
                (if (divergence.classifications.isNotEmpty())
                    "\nLIKELY CAUSE:\n  " + divergence.classifications.joinToString("\n  ")
                 else "") +
                "\nSee: ${reportsDir.resolve("divergence-report.txt")}"
            throw org.opentest4j.AssertionFailedError(failMessage)
        }
    }
}
