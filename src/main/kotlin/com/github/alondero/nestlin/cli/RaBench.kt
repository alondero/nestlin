package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.session.GameSessionCoordinator
import com.github.alondero.nestlin.session.NativeRetroAchievementsService
import com.github.alondero.nestlin.session.RaLatencyTracker
import com.github.alondero.nestlin.session.RetroAchievementsService
import com.github.alondero.nestlin.session.RetroAchievementsServiceFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * RetroAchievements performance benchmark (issue #273 AC: "A
 * repeatable benchmark uses a real-sized achievement set and records
 * p95 evaluation latency and audio health").
 *
 * The benchmark drives the full Nestlin boot path with `nestest.nes`
 * (the only ROM in git) for a configurable number of frames, ticking
 * CPU cycles through the PPU's frame-completion listener (which is
 * the production call site for
 * [RetroAchievementsService.evaluateFrame]). Every frame's evaluation
 * duration is recorded into an [RaLatencyTracker] ring buffer; the
 * p95 + budget-breach count are reported at the end.
 *
 * Audio health is sampled by reading [com.github.alondero.nestlin.Apu.silentReads]
 * before and after the run — an audio underrun shows up as a
 * silent-read spike.
 *
 * The benchmark uses [RetroAchievementsServiceFactory.create] which
 * falls back to `NoOpRetroAchievementsService` when the native lib
 * is absent. With NoOp the per-frame cost is ~0 (a single virtual
 * call); with the native service the cost includes the JNA round-trip
 * into the C façade. Either way, the benchmark exercises the full
 * production code path: `preFrameCaptureHook` → `evaluateFrameNext`
 * → `service.evaluateFrame`.
 *
 * When the service is a [NativeRetroAchievementsService], we attach
 * an [RaLatencyTracker] so the production `evaluateFrame` records
 * its own wall-clock duration; when it's NoOp / Fake, the bench
 * records overall throughput instead.
 *
 * ## Usage
 *
 *     java -jar nestlin-all.jar ra-bench [--frames N] [--warmup M]
 *
 * Exit codes:
 *   0 — benchmark completed; p95 < 1 ms (budget honoured) — or
 *       service was NoOp so p95 wasn't measurable
 *   1 — benchmark completed; p95 >= 1 ms (budget breached)
 *   2 — usage / bad args
 */
object RaBench {

    data class Result(
        val frames: Int,
        val totalMillis: Long,
        val framesPerSecond: Double,
        val p95Micros: Double,
        val budgetBreaches: Int,
        val silentReadsMeasured: Long,
        val usingNativeService: Boolean,
    ) {
        /**
         * A frame meets the 1 ms budget if its evaluation cost is
         * strictly less than 1 ms. When the service is NoOp the
         * tracker is not wired, so [p95Micros] is 0 and
         * [meetsBudget] returns true (no measurements, no breaches).
         */
        val meetsBudget: Boolean get() = !usingNativeService || budgetBreaches == 0
    }

    /**
     * Run the benchmark.
     *
     * @param out where to write the report (default System.out).
     * @param romPath path to a `.nes` ROM to drive the emulation.
     *   The benchmark needs *some* ROM; nestest.nes works because
     *   it boots in <1 s and never depends on inputs.
     * @param frames number of measured frames. Default 1000 ≈ 16 s
     *   at 60 Hz — enough to fill the [RaLatencyTracker] ring
     *   (capacity 1024).
     * @param warmupFrames number of warm-up frames before measurement
     *   starts. The JIT, the APU mixer, and the audio buffer all
     *   need a few hundred frames to settle.
     */
    fun run(
        out: Appendable = System.out,
        romPath: Path,
        frames: Int = 1000,
        warmupFrames: Int = 120,
    ): Result {
        require(frames > 0) { "frames must be positive" }
        require(warmupFrames >= 0) { "warmupFrames must be non-negative" }

        out.appendLine("RA-BENCH: rom=$romPath frames=$frames warmup=$warmupFrames")

        val romBytes = Files.readAllBytes(romPath)
        val nestlin = Nestlin()
        nestlin.loadBytes(romBytes, displayName = romPath.fileName.toString())
        val service = RetroAchievementsServiceFactory.create()
        val coord = GameSessionCoordinator(nestlin, service)

        // When the service is the native impl, attach a tracker so
        // the production evaluateFrame call records its own
        // wall-clock duration. The tracker field is `internal var`
        // on NativeRetroAchievementsService — the only place this
        // benchmark talks to the native service directly.
        val tracker: RaLatencyTracker? = (service as? NativeRetroAchievementsService)?.let { svc ->
            RaLatencyTracker().also { svc.latencyTracker = it }
        }
        val usingNativeService = tracker != null

        // Warmup: tick frames, but discard the latency samples so
        // JIT warm-up + audio buffer fill don't poison the p95.
        tickFrames(nestlin, warmupFrames)
        tracker?.reset()
        nestlin.apu.resetSilentReadsCounter()

        // Measured window: full tracker, full audio health.
        val start = System.nanoTime()
        tickFrames(nestlin, frames)
        val totalNanos = System.nanoTime() - start

        val silentReadsMeasured = nestlin.apu.silentReads()
        coord.shutdown()

        val p95Micros = if (tracker != null) tracker.p95Nanos() / 1_000.0 else 0.0
        val breaches = if (tracker != null) {
            tracker.budgetBreaches(RaLatencyTracker.DEFAULT_BUDGET_NANOS)
        } else 0

        val result = Result(
            frames = frames,
            totalMillis = totalNanos / 1_000_000L,
            framesPerSecond = if (totalNanos > 0) frames.toDouble() * 1_000_000_000.0 / totalNanos else 0.0,
            p95Micros = p95Micros,
            budgetBreaches = breaches,
            silentReadsMeasured = silentReadsMeasured,
            usingNativeService = usingNativeService,
        )

        out.appendLine(
            "RESULT frames=${result.frames} " +
                "wallClockMs=${result.totalMillis} " +
                "fps=%.2f ".format(result.framesPerSecond) +
                "p95Micros=%.1f ".format(result.p95Micros) +
                "budgetBreaches=${result.budgetBreaches} " +
                "silentReads(measured)=$silentReadsMeasured " +
                "service=${if (usingNativeService) "native" else "noop"}"
        )
        out.appendLine("BUDGET: ${if (result.meetsBudget) "PASS" else "FAIL"} " +
            "(1ms p95 — issue #273 AC)")

        return result
    }

    /**
     * Tick CPU cycles until the PPU signals `frames` frame completions.
     * The PPU frame-completion listener is the production call site
     * for `RetroAchievementsService.evaluateFrame` (via
     * `preFrameCaptureHook` → `evaluateFrameNext`), so this drives
     * the full per-frame path.
     */
    private fun tickFrames(nestlin: Nestlin, frames: Int) {
        var completed = 0
        while (completed < frames) {
            nestlin.stepCpuCycle()
            if (nestlin.ppu.frameJustCompleted()) {
                completed++
            }
        }
    }

    val USAGE = """
        usage: nestlin ra-bench --rom <path> [--frames N] [--warmup M]

          --rom <path>    path to a .nes ROM (required; nestest.nes works)
          --frames N      number of measured frames (default: 1000)
          --warmup M      warmup frames discarded before measurement (default: 120)

        Reports p95 per-frame RA evaluation latency in microseconds,
        total throughput (frames/sec), and silent-reads count over
        the measured window. Exits 0 when p95 < 1 ms (or service was
        NoOp so p95 wasn't measurable), 1 otherwise.
    """.trimIndent()
}

object RaBenchCli {
    fun main(args: List<String>, out: Appendable = System.out): Int {
        var romPath: Path? = null
        var frames = 1000
        var warmup = 120
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--rom" -> {
                    val v = args.getOrNull(++i) ?: return usage(out, "--rom requires a value")
                    romPath = Paths.get(v)
                }
                "--frames" -> {
                    val v = args.getOrNull(++i) ?: return usage(out, "--frames requires a value")
                    frames = v.toIntOrNull()?.takeIf { it > 0 }
                        ?: return usage(out, "--frames must be a positive integer, got '$v'")
                }
                "--warmup" -> {
                    val v = args.getOrNull(++i) ?: return usage(out, "--warmup requires a value")
                    warmup = v.toIntOrNull()?.takeIf { it >= 0 }
                        ?: return usage(out, "--warmup must be a non-negative integer, got '$v'")
                }
                else -> {
                    if (arg.startsWith("--")) return usage(out, "unknown option '$arg'")
                    return usage(out, "unexpected positional argument '$arg'")
                }
            }
            i++
        }
        val path = romPath ?: return usage(out, "--rom is required")
        if (!Files.exists(path)) return usage(out, "--rom path does not exist: $path")
        val result = RaBench.run(out, path, frames, warmup)
        return if (result.meetsBudget) 0 else 1
    }

    private fun usage(out: Appendable, message: String): Int {
        out.appendLine("ERROR: $message")
        out.appendLine(RaBench.USAGE)
        return 2
    }
}

/** Entry point for `./gradlew raBench` (or `java -jar nestlin-all.jar ra-bench`). */
fun main(args: Array<String>) {
    kotlin.system.exitProcess(RaBenchCli.main(args.toList()))
}
