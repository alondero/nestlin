package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.movie.runOneFrame
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Headless `bootcheck` command: boot a ROM cold, run it for N frames with no display and no
 * reference emulator, and print a machine-greppable verdict about whether it plausibly *booted*.
 *
 * ## Why this exists
 *
 * The strong mapper gates — the Mesen2 byte-compare in [com.github.alondero.nestlin.compare]
 * and the real-game boot tests — all `assumeTrue(romExists)` / `@RequiresMesen2`, so they
 * *skip* (green) on a worktree that has neither the ROM library nor Mesen2. A weaker model
 * delegated a mapper can then run `./gradlew test`, see green, and declare success having
 * verified nothing about a real game. The two failure modes that slips through are exactly
 * the ones a human notices immediately: the game **doesn't load** (throws / never renders) or
 * shows **garbage/blank** (CHR-bank or mirroring bug).
 *
 * bootcheck is the oracle-free signal for both. It needs no Mesen2 and no library ROM — point
 * it at whatever `.nes` you have. It can't *prove* a mapper is byte-correct (only Mesen2 can),
 * but it reliably catches "didn't boot" and "blank screen", which is what weaker models miss.
 *
 * Determinism comes from [runOneFrame] (no threads, no wall clock) — the same seam `replay`
 * uses — so it can't hang on a CPU spin-loop the way driving [Nestlin.start] on a thread could.
 *
 * ## The verdict
 *
 * Sampled once per completed frame: PPUMASK (rendering-enabled bits), the program counter, the
 * mapper's bank snapshot, and the frame's pixels. Reported:
 *
 *  - `loaded`             — did a mapper instantiate? (its class name)
 *  - `threw`              — did the emulator throw mid-run? (the message)
 *  - `renderingEnabledBy` — first frame PPUMASK bits 3/4 went non-zero (null = never)
 *  - `maxNonBlankRatio`   — peak fraction of pixels differing from the frame's modal colour
 *                           (a uniform "blank" screen ≈ 0.00; a drawn screen is well above)
 *  - `distinctPrgStates`  — number of distinct PRG-bank configurations seen (1 = never paged)
 *  - `distinctChrStates`  — number of distinct CHR-bank configurations seen
 *  - `nmiCount` / `irqCount` — interrupt totals (0 NMIs usually means rendering never armed)
 *  - `distinctFramePCs`   — distinct program counters at frame boundaries (1 = wedged hard)
 *
 * Verdict = FAIL only on near-certain non-boot (threw, didn't load, or never-rendered-and-blank)
 * so it does not cry wolf; WARN flags softer smells (blank despite rendering, banks never moved).
 * Exit codes mirror `replay`: 0 PASS/WARN · 1 FAIL · 2 usage · 3 emulator threw.
 */
object BootCheck {

    /** Exit codes are part of the CLI contract — CI, the Stop-hook, and agents branch on them. */
    const val EXIT_OK = 0          // PASS or WARN
    const val EXIT_FAIL = 1        // booted to nothing (blank + never rendered), or didn't load
    const val EXIT_USAGE = 2
    const val EXIT_THREW = 3       // emulator threw mid-run (crash / unimplemented mapper)

    /** A near-uniform screen below this non-blank ratio is treated as "blank". */
    private const val BLANK_RATIO = 0.01
    /** Rendering is on but barely anything drawn — softer smell, a WARN not a FAIL. */
    private const val SPARSE_RATIO = 0.02
    const val DEFAULT_FRAMES = 120

    enum class Grade { PASS, WARN, FAIL }

    data class Options(val romPath: Path, val frames: Int = DEFAULT_FRAMES)

    data class Verdict(
        val grade: Grade,
        val reasons: List<String>,
        val loaded: Boolean,
        val mapperName: String?,
        val threw: Boolean,
        val threwMessage: String?,
        val framesRun: Int,
        val renderingEnabledByFrame: Int?,
        val maxNonBlankRatio: Double,
        val distinctPrgStates: Int,
        val distinctChrStates: Int,
        val nmiCount: Int,
        val irqCount: Int,
        val distinctFramePCs: Int,
    ) {
        val exitCode: Int
            get() = when {
                threw -> EXIT_THREW
                grade == Grade.FAIL -> EXIT_FAIL
                else -> EXIT_OK
            }
    }

    /** Run bootcheck, writing the human/greppable report to [out]. Returns the [Verdict]. */
    fun run(opts: Options, out: Appendable): Verdict {
        val verdict = try {
            evaluate(opts)
        } catch (t: Throwable) {
            // Last-resort safety net for an unexpected harness error escaping evaluate(); the
            // expected throw paths (load failure, mid-run crash) are handled inside evaluate() so
            // they can report `loaded` accurately. We cannot tell here whether a mapper loaded.
            failedToBoot(opts, threw = true, threwMessage = t.message ?: t.javaClass.simpleName, loaded = false)
        }
        report(opts, verdict, out)
        return verdict
    }

    private fun evaluate(opts: Options): Verdict {
        // Phase 1 — load. A throw here (unsupported mapper, bad header, unreadable file) means the
        // cartridge genuinely did not come up: loaded = false.
        val nestlin = try {
            Nestlin().apply {
                config.speedThrottlingEnabled = false
                // Headless boot check never rewinds — skip per-frame savestate capture.
                config.rewindEnabled = false
                load(opts.romPath)
                powerReset()
            }
        } catch (t: Throwable) {
            return failedToBoot(opts, threw = true, threwMessage = t.message ?: t.javaClass.simpleName, loaded = false)
        }
        val mapper = nestlin.memory.mapper
            ?: return failedToBoot(opts, threw = false, threwMessage = null, loaded = false) // header parsed but no mapper

        // Past here a mapper IS instantiated, so loaded = true even if the run later crashes — a
        // mapper that loads then throws mid-frame is exactly the bug bootcheck exists to surface,
        // and must NOT be reported as "failed to load / unsupported mapper".
        val capture = FrameCapture().also { nestlin.addFrameListener(it) }

        var renderingEnabledByFrame: Int? = null
        var maxNonBlankRatio = 0.0
        val prgStates = linkedSetOf<String>()
        val chrStates = linkedSetOf<String>()
        val framePCs = linkedSetOf<Int>()
        var framesRun = 0
        var threwMessage: String? = null

        try {
            for (f in 1..opts.frames) {
                nestlin.runOneFrame()
                framesRun = f

                if (renderingEnabledByFrame == null && (nestlin.ppuMask() and RENDER_MASK) != 0) {
                    renderingEnabledByFrame = f
                }
                framePCs.add(nestlin.cpu.getCurrentPc().toInt() and 0xFFFF)

                val banks = mapper.snapshot()?.banks
                if (banks != null) {
                    prgStates.add(bankSignature(banks, "prg"))
                    chrStates.add(bankSignature(banks, "chr"))
                }

                capture.last?.let { maxNonBlankRatio = maxOf(maxNonBlankRatio, nonBlankRatio(it)) }
            }
        } catch (t: Throwable) {
            threwMessage = t.message ?: t.javaClass.simpleName
        }

        val (grade, reasons) = if (threwMessage != null) {
            Grade.FAIL to listOf("emulator threw at frame ${framesRun + 1} after loading $framesRun frame(s): $threwMessage")
        } else {
            gradeSignals(
                renderingEnabledByFrame = renderingEnabledByFrame,
                maxNonBlankRatio = maxNonBlankRatio,
                distinctPrgStates = prgStates.size,
                distinctChrStates = chrStates.size,
                distinctFramePCs = framePCs.size,
            )
        }

        return Verdict(
            grade = grade,
            reasons = reasons,
            loaded = true,
            mapperName = mapper.javaClass.simpleName,
            threw = threwMessage != null,
            threwMessage = threwMessage,
            framesRun = framesRun,
            renderingEnabledByFrame = renderingEnabledByFrame,
            maxNonBlankRatio = maxNonBlankRatio,
            distinctPrgStates = prgStates.size,
            distinctChrStates = chrStates.size,
            nmiCount = nestlin.cpu.nmiCount,
            irqCount = nestlin.cpu.irqCount,
            distinctFramePCs = framePCs.size,
        )
    }

    /**
     * Pure grading of the measured boot signals — extracted from [evaluate] so every PASS/WARN/FAIL
     * branch is unit-testable without booting a ROM. FAIL is reserved for near-certain non-boot
     * (never rendered AND blank) so the gate doesn't cry wolf; the softer smells are WARN.
     */
    internal fun gradeSignals(
        renderingEnabledByFrame: Int?,
        maxNonBlankRatio: Double,
        distinctPrgStates: Int,
        distinctChrStates: Int,
        distinctFramePCs: Int,
    ): Pair<Grade, List<String>> {
        val rendered = renderingEnabledByFrame != null
        val reasons = mutableListOf<String>()
        if (!rendered && maxNonBlankRatio < BLANK_RATIO) {
            reasons += "rendering never enabled (PPUMASK bits 3/4 stayed 0) and every frame is blank " +
                "(maxNonBlankRatio=%.4f < %.2f) - the ROM did not boot to a picture".format(maxNonBlankRatio, BLANK_RATIO)
            return Grade.FAIL to reasons
        }
        if (rendered && maxNonBlankRatio < SPARSE_RATIO) reasons += "rendering enabled at frame " +
            "$renderingEnabledByFrame but the screen is almost empty (maxNonBlankRatio=%.4f) - " +
            "possible CHR-bank/mirroring bug".format(maxNonBlankRatio)
        if (distinctPrgStates <= 1 && distinctChrStates <= 1) reasons += "no bank ever moved during boot " +
            "(prgStates=$distinctPrgStates, chrStates=$distinctChrStates) - fine for a single-bank board, " +
            "suspicious for a banked one"
        if (distinctFramePCs <= 1) reasons += "program counter never advanced past one frame-boundary value - CPU may be wedged"
        return (if (reasons.isEmpty()) Grade.PASS else Grade.WARN) to reasons
    }

    private fun failedToBoot(opts: Options, threw: Boolean, threwMessage: String?, loaded: Boolean): Verdict {
        val reason = if (threw) "emulator threw while loading: ${threwMessage ?: "unknown"}"
        else "no mapper instantiated - ROM failed to load (bad header or unsupported mapper)"
        return Verdict(
            grade = Grade.FAIL, reasons = listOf(reason), loaded = loaded, mapperName = null,
            threw = threw, threwMessage = threwMessage, framesRun = 0, renderingEnabledByFrame = null,
            maxNonBlankRatio = 0.0, distinctPrgStates = 0, distinctChrStates = 0,
            nmiCount = 0, irqCount = 0, distinctFramePCs = 0,
        )
    }

    /** Fraction of pixels that differ from the frame's most-common colour. Blank screen ≈ 0. */
    private fun nonBlankRatio(frame: Frame): Double {
        val counts = HashMap<Int, Int>(1024)
        var total = 0
        for (y in 0 until RESOLUTION_HEIGHT) {
            val line = frame.scanlines[y]
            for (x in 0 until RESOLUTION_WIDTH) {
                counts.merge(line[x], 1, Int::plus)
                total++
            }
        }
        if (total == 0) return 0.0
        val modal = counts.values.maxOrNull() ?: 0
        return (total - modal).toDouble() / total
    }

    /** Stable signature of the bank values whose key mentions [kind] (prg/chr), order-independent. */
    private fun bankSignature(banks: Map<String, Int>, kind: String): String =
        banks.filterKeys { it.contains(kind, ignoreCase = true) }
            .toSortedMap()
            .entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun report(opts: Options, v: Verdict, out: Appendable) {
        out.appendLine("rom=${opts.romPath}")
        out.appendLine("BOOTCHECK VERDICT: ${v.grade}")
        out.appendLine("  loaded             : ${v.loaded}${v.mapperName?.let { "   ($it)" } ?: ""}")
        out.appendLine("  threw              : ${v.threw}${v.threwMessage?.let { "   ($it)" } ?: ""}")
        out.appendLine("  framesRun          : ${v.framesRun}")
        out.appendLine("  renderingEnabledBy : ${v.renderingEnabledByFrame ?: "never"}")
        out.appendLine("  maxNonBlankRatio   : %.4f".format(v.maxNonBlankRatio))
        out.appendLine("  distinctPrgStates  : ${v.distinctPrgStates}")
        out.appendLine("  distinctChrStates  : ${v.distinctChrStates}")
        out.appendLine("  nmiCount           : ${v.nmiCount}    irqCount: ${v.irqCount}")
        out.appendLine("  distinctFramePCs   : ${v.distinctFramePCs}")
        if (v.reasons.isNotEmpty()) {
            out.appendLine("  notes:")
            v.reasons.forEach { out.appendLine("    - $it") }
        }
    }

    private const val RENDER_MASK = 0x18 // PPUMASK bit 3 (show background) | bit 4 (show sprites)

    /** Single-slot frame listener that keeps the most recently completed frame. */
    private class FrameCapture : FrameListener {
        var last: Frame? = null
        override fun frameUpdated(frame: Frame) { last = frame }
    }
}

/**
 * Argument parsing for the headless `bootcheck` subcommand. Kept tiny and separate from
 * [BootCheck] so the verdict logic stays unit-testable with a fabricated [BootCheck.Options].
 *
 *     nestlin bootcheck <rom.nes> [--frames N]
 */
object BootCheckCli {
    val USAGE = """
        usage: nestlin bootcheck <rom.nes> [--frames N]

          --frames N   number of frames to run before judging (default: ${BootCheck.DEFAULT_FRAMES})

        Prints a 'BOOTCHECK VERDICT: PASS|WARN|FAIL' block. No Mesen2 or ROM library needed.
        Exit codes: 0 pass/warn · 1 boot-failed · 2 usage · 3 emulator threw.
    """.trimIndent()

    fun main(args: List<String>, out: Appendable = System.out): Int {
        val positional = mutableListOf<String>()
        var frames = BootCheck.DEFAULT_FRAMES
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--frames" -> {
                    val v = args.getOrNull(++i) ?: return usage(out, "--frames requires a value")
                    frames = v.toIntOrNull()?.takeIf { it > 0 } ?: return usage(out, "--frames must be a positive integer, got '$v'")
                }
                else -> {
                    if (arg.startsWith("--")) return usage(out, "unknown option '$arg'")
                    positional.add(arg)
                }
            }
            i++
        }
        if (positional.size != 1) return usage(out, "expected exactly one <rom.nes>, got ${positional.size}")
        return BootCheck.run(BootCheck.Options(Paths.get(positional[0]), frames), out).exitCode
    }

    private fun usage(out: Appendable, message: String): Int {
        out.appendLine("ERROR: $message")
        out.appendLine(USAGE)
        return BootCheck.EXIT_USAGE
    }
}

/** Entry point for the Gradle `bootcheck` JavaExec task (mainClass = ...cli.BootCheckKt). */
fun main(args: Array<String>) {
    kotlin.system.exitProcess(BootCheckCli.main(args.toList()))
}
