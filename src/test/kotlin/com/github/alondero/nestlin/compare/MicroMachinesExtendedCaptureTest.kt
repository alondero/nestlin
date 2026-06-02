package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper71
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.streams.toList

/**
 * Long-running capture test for Micro Machines (mapper 71).
 *
 * Captures mapper state on every frame so we can see when (if ever)
 * firehawk mode latches, what PRG bank the game is sitting on, and how
 * those change across boot. Also saves PNG screenshots at a handful of
 * frames so we can look for the "band" visual artifact the user reported.
 */
class MicroMachinesExtendedCaptureTest {

    @Test
    fun `extended boot trace`() {
        val rom = locateRom() ?: run {
            assumeTrue("Micro Machines (USA) ROM not found", false); return
        }

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        val outDir = Paths.get("build/micro-machines-trace")
        Files.createDirectories(outDir)

        val targetFrames = setOf(0, 30, 60, 90, 120, 150, 180, 240, 360, 480, 600)

        var frame = 0
        var firstFirehawkLatchedFrame = -1
        val firehawkLatchLog = mutableListOf<String>()
        val prgBankTransitions = mutableListOf<String>()
        var lastPrgBank = -1
        var lastFirehawkState = -999
        var pcLast = 0
        var instrLast = 0

        val mapperRef = java.util.concurrent.atomic.AtomicReference<Mapper71?>(null)

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++

                val mapper = nestlin.memory.mapper
                if (mapper is Mapper71) {
                    mapperRef.compareAndSet(null, mapper)
                    val snap = mapper.snapshot()
                    val prgBank = snap.banks["prgBank"] ?: 0
                    val firehawkUpper = snap.registers["firehawkMirrorUpper"] ?: -1
                    val bf9097 = snap.registers["bf9097Mode"] ?: 0

                    if (bf9097 == 1 && firstFirehawkLatchedFrame < 0) {
                        firstFirehawkLatchedFrame = frame
                    }
                    if (bf9097 != lastFirehawkState) {
                        firehawkLatchLog += "frame $frame: bf9097Mode=$bf9097 firehawkMirrorUpper=$firehawkUpper"
                        lastFirehawkState = bf9097
                    }
                    if (prgBank != lastPrgBank) {
                        prgBankTransitions += "frame $frame: prgBank $lastPrgBank -> $prgBank"
                        lastPrgBank = prgBank
                    }
                }

                if (frame in targetFrames) {
                    savePng(f, outDir.resolve("frame${"%04d".format(frame)}.png"))
                }

                pcLast = nestlin.cpu.registers.programCounter.toUnsignedInt()
                instrLast = nestlin.cpu.getInstructionCount()
            }
        })

        nestlin.load(rom)
        nestlin.powerReset()

        var guard = 600L * 80_000
        while (frame < 600 && guard-- > 0) {
            nestlin.stepCpuCycle()
        }

        println("[MicroMachinesTrace] ran $frame frames, ended at pc=0x${"%04X".format(pcLast)} instructions=$instrLast")
        println("[MicroMachinesTrace] firstFirehawkLatchedFrame=$firstFirehawkLatchedFrame")
        println("[MicroMachinesTrace] firehawk latch events:")
        firehawkLatchLog.forEach { println("  $it") }
        println("[MicroMachinesTrace] PRG bank transitions (${prgBankTransitions.size} total):")
        prgBankTransitions.forEach { println("  $it") }
        println("[MicroMachinesTrace] screenshots written to $outDir")

        // Sanity: the game must still be alive after 600 frames (~10 seconds).
        // 5,000,000 instructions is roughly 600 frames * 8,000 instr/frame on a
        // well-behaved mapper 71 boot.
        Assert.assertTrue(
            "Game appears stuck: only $instrLast instructions over $frame frames",
            instrLast > 5_000_000
        )
    }

    private fun savePng(frame: Frame, outputPath: Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        Files.createDirectories(outputPath.parent)
        ImageIO.write(image, "PNG", outputPath.toFile())
    }

    private fun locateRom(): Path? {
        System.getenv("NESTLIN_MICRO_MACHINES_ROM")?.let {
            val p = Paths.get(it); if (Files.exists(p)) return p
        }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = Paths.get(lib)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                return stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("micro machines") && n.contains("usa")
                }
            }
        }
        return null
    }
}
