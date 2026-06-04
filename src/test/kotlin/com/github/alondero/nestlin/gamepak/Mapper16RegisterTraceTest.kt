package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Diagnostic test: run the game for a few hundred instructions, log every CPU
 * write to $6000-$FFFF, and verify the game is *actually* writing to the
 * register window we expect. The output (under build/reports/...) is the input
 * to diagnosing the boot failure.
 *
 * Skipped (not failed) when the ROM is missing.
 */
class Mapper16RegisterTraceTest {

    private fun crayonShinChanRom(): Path = Paths.get(
        "S:/Media/Nintendo NES/Games/Crayon Shin-chan - Ora to Poi Poi (Japan).nes"
    )
    private fun dragonBallRom(): Path = Paths.get(
        "S:/Media/Nintendo NES/Games/Dragon Ball - Daimaou Fukkatsu (Japan) (Translated En).nes"
    )

    @Test
    fun `trace crayon shin-chan register writes during early boot`() {
        val rom = crayonShinChanRom()
        assumeTrue("ROM not found at $rom", Files.exists(rom))
        traceWrites(rom, "Crayon Shin-chan",
            "build/reports/bandai-fcg-trace/crayon-shin-chan-writes.txt")
    }

    @Test
    fun `trace dragon ball register writes during early boot`() {
        val rom = dragonBallRom()
        assumeTrue("ROM not found at $rom", Files.exists(rom))
        traceWrites(rom, "Dragon Ball",
            "build/reports/bandai-fcg-trace/dragon-ball-writes.txt")
    }

    private fun traceWrites(rom: Path, label: String, outPath: String) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        val gamePak = nestlin.cpu.currentGame!!
        // powerReset() calls readCartridge() which re-creates the mapper from
        // the GamePak. Run powerReset first, then wrap the now-live mapper in
        // our tracing decorator.
        nestlin.powerReset()
        val realMapper = nestlin.memory.mapper as Mapper16
        val decorator = TracingMapper(realMapper)
        nestlin.memory.mapper = decorator

        val perFrame = mutableListOf<String>()
        val maxFrames = 5
        var frameCount = 0
        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                val cpu = nestlin.cpu
                val regs = cpu.registers
                val ps = cpu.processorStatus.asByte().toInt() and 0xFF
                perFrame += "frame %2d: PC=0x%04X A=0x%02X X=0x%02X Y=0x%02X SP=0x%02X PS=0x%02X mapperWrites=%d".format(
                    frameCount,
                    regs.programCounter.toInt() and 0xFFFF,
                    regs.accumulator.toInt() and 0xFF,
                    regs.indexX.toInt() and 0xFF,
                    regs.indexY.toInt() and 0xFF,
                    regs.stackPointer.toInt() and 0xFF,
                    ps,
                    decorator.writes.size,
                )
                if (++frameCount >= maxFrames) nestlin.stop()
            }
        })
        nestlin.start()

        val report = buildString {
            appendLine("=== $label register-write trace (first $maxFrames frames) ===")
            appendLine("Total writes: ${decorator.writes.size}")
            perFrame.forEach { appendLine(it) }
            appendLine()
            val byWindow = decorator.writes.groupBy { it.window }
            for (window in listOf("0x6000-0x7FFF", "0x8000-0xFFFF")) {
                val writes = byWindow[window] ?: emptyList()
                appendLine()
                appendLine("--- Writes to $window (${writes.size}) ---")
                writes.take(60).forEach { appendLine(it.toString()) }
                if (writes.size > 60) appendLine("... (${writes.size - 60} more)")
            }
        }
        Files.createDirectories(Paths.get(outPath).parent)
        Files.writeString(Paths.get(outPath), report)
        println(report.take(2000))

        val total = decorator.writes.size
        Assert.assertTrue(
            "$label wrote $total register writes — that's suspicious. See $outPath",
            total > 0
        )
    }

    /** Decorator: forwards everything to the wrapped Mapper and logs cpuWrite. */
    private class TracingMapper(private val wrapped: Mapper16) : Mapper by wrapped {
        data class Write(val window: String, val address: Int, val value: Int, val instruction: Int)
        val writes = mutableListOf<Write>()
        private var instrCount = 0
        override fun cpuWrite(address: Int, value: Byte) {
            val window = if (address in 0x6000..0x7FFF) "0x6000-0x7FFF"
                else if (address in 0x8000..0xFFFF) "0x8000-0xFFFF"
                else "other"
            writes += Write(window, address, value.toInt(), instrCount)
            instrCount++
            wrapped.cpuWrite(address, value)
        }
    }
}
