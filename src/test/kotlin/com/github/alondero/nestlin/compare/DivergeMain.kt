package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * CLI entry point for the DivergenceLocalizer.
 *
 * Usage: DivergeMainKt <rom> [--frame N] [--out DIR]
 *   --frame defaults to 120
 *   --out   defaults to build/reports/divergence/<romname>-frame-N
 *
 * Invoked via the `diverge` Gradle task:
 *   ./gradlew diverge -Prom=X:/src/nestlin/testroms/kirby.nes -Pframe=120
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: diverge <rom> [--frame N] [--out DIR]")
        exitProcess(2)
    }

    val romPath = Paths.get(args[0])
    var frame = 120
    var outDir: Path? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--frame" -> {
                frame = args.getOrNull(i + 1)?.toIntOrNull()
                    ?: run { System.err.println("--frame requires an integer"); exitProcess(2) }
                i += 2
            }
            "--out" -> {
                outDir = args.getOrNull(i + 1)?.let { Paths.get(it) }
                    ?: run { System.err.println("--out requires a directory"); exitProcess(2) }
                i += 2
            }
            else -> {
                System.err.println("Unknown argument: ${args[i]}")
                exitProcess(2)
            }
        }
    }

    if (!Files.exists(romPath)) {
        System.err.println("ROM not found: $romPath")
        exitProcess(2)
    }

    val romName = romPath.fileName.toString().substringBeforeLast('.')
    val reportDir = outDir ?: Paths.get("build/reports/divergence/$romName-frame-$frame")

    if (!Mesen2StateCapturer.isMesen2Available()) {
        println("NOTE: Mesen2 not found at ${Mesen2StateCapturer.getMesen2Path().toAbsolutePath()} — " +
                "Nestlin-only capture; classification needs Mesen2 (set MESEN2_PATH).")
    }

    val report = DivergenceLocalizer.localize(romPath, frame, reportDir)

    println(report.table)
    println("Reports written to: ${reportDir.toAbsolutePath()}")
}
