package com.github.alondero.nestlin.compare

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Driver: runs Mesen2 once to dump OAM at our target frames, then prints
 * Mesen vs Nestlin OAM side-by-side. Nestlin OAM is produced by the
 * sibling KirbyOamSnapshotTest, which writes build/kirby-oam-snapshots.txt.
 *
 * Requires Mesen2 + GUI + I/O permissions enabled.
 */
class KirbyMesenVsNestlinOamTest {

    @Test
    fun dumpMesenOam() {
        val romPath = Paths.get("kirby.nes").toAbsolutePath()
        if (!Files.exists(romPath)) {
            System.err.println("Kirby ROM not at $romPath, skipping")
            return
        }
        if (!Mesen2OamDumpRunner.isAvailable()) {
            System.err.println("Mesen2 not at ${Mesen2OamDumpRunner.mesen2Path()}, skipping")
            return
        }

        val targetFrames = listOf(500, 600, 619, 625, 700, 900)
        val outDir = Paths.get("build/kirby-oam-mesen2")
        Mesen2OamDumpRunner.dumpOam(romPath, targetFrames, outDir)

        System.err.println("Mesen2 OAM dumps written to ${outDir.toAbsolutePath()}")
        Files.list(outDir).use { stream ->
            stream.sorted()
                .filter { it.fileName.toString().endsWith(".txt") }
                .forEach { p ->
                    System.err.println("\n=== ${p.fileName} ===")
                    Files.readAllLines(p).forEach { System.err.println(it) }
                }
        }
    }
}
