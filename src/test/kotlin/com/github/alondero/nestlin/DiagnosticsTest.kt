package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

class DiagnosticsTest {
    @Test
    fun `trace nametable writes in donkey kong for first frames`() {
        val path = Paths.get("testroms/donkeykong.nes")
        if (!Files.exists(path)) {
            println("ROM not found: $path")
            return
        }

        // Capture stderr to see the VRAM WRITE logs
        val prevErr = System.err
        val capturedErr = ByteArrayOutputStream()
        System.setErr(PrintStream(capturedErr))

        try {
            val memory = Memory()
            val cpu = Cpu(memory)
            val ppu = Ppu(memory)

            cpu.currentGame = GamePak(Files.readAllBytes(path))
            cpu.reset()

            // Run for exactly 50 frames
            val cyclesPerFrame = 89342
            val targetFrames = 50
            val targetCycles = cyclesPerFrame * targetFrames

            var cyclesRun = 0
            while (cyclesRun < targetCycles) {
                (1..3).forEach { ppu.tick() }
                cpu.tick()
                cyclesRun++
            }

            println("Ran $cyclesRun cycles (${cyclesRun / cyclesPerFrame} frames)")

        } finally {
            System.setErr(prevErr)
            val output = capturedErr.toString()
            println("\n=== VRAM WRITES CAPTURED ===")
            output.lines()
                .filter { it.contains("VRAM WRITE") }
                .forEach { println(it) }
        }
    }
}
