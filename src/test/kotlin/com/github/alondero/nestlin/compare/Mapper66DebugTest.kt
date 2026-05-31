package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.Test
import java.io.File
import java.nio.file.Paths

class Mapper66DebugTest {

    private val outputFile = File("C:/Users/alond/AppData/Local/Temp/mapper66_debug.txt")

    @Test
    fun `debug Dragon Power state at frame 5`() {
        outputFile.delete()
        val romPath = Paths.get("X:/src/nestlin/.claude/worktrees/ripe-eager-beam/testroms/dragonpower.nes")
        debugAtFrame(romPath, 5, "DragonPower")
        println("Debug output written to: ${outputFile.absolutePath}")
    }

    @Test
    fun `debug SMB Duck Hunt state at frame 5`() {
        outputFile.delete()
        val romPath = Paths.get("X:/src/nestlin/.claude/worktrees/ripe-eager-beam/testroms/smbdh.nes")
        debugAtFrame(romPath, 5, "SMB+DuckHunt")
        println("Debug output written to: ${outputFile.absolutePath}")
    }

    @Test
    fun compareMesen2StateAtFrame5() {
        outputFile.delete()
        val romPath = Paths.get("X:/src/nestlin/.claude/worktrees/ripe-eager-beam/testroms/smbdh.nes")
        if (!java.nio.file.Files.exists(romPath)) {
            println("ROM not found: $romPath")
            return
        }

        println("Starting Mesen2 state capture for SMB+DuckHunt at frame 5...")
        val mesen2State = try {
            Mesen2StateCapturer.captureState(romPath, 5)
        } catch (e: Exception) {
            println("Mesen2 capture failed: ${e.message}")
            e.printStackTrace()
            return
        }

        println("Mesen2 capture successful!")
        outputFile.appendText("=== SMB+DuckHunt Mesen2 Frame 5 ===\n")
        outputFile.appendText("PC: ${mesen2State.cpu.pc}\n")
        outputFile.appendText("A: ${mesen2State.cpu.a}, X: ${mesen2State.cpu.x}, Y: ${mesen2State.cpu.y}\n")
        outputFile.appendText("PPU Control: ${String.format("%02X", mesen2State.ppu.control)}, Mask: ${String.format("%02X", mesen2State.ppu.mask)}, Status: ${String.format("%02X", mesen2State.ppu.status)}\n")
        outputFile.appendText("PPU Scanline: ${mesen2State.ppu.scanline}, Cycle: ${mesen2State.ppu.cycle}\n")
        outputFile.appendText("Palette[0-7]: ${mesen2State.paletteRam.take(8).joinToString(" ")}\n")

        println("Mesen2 state captured and written to: ${outputFile.absolutePath}")
    }

    private fun getIntField(obj: Any, name: String): Int {
        val field = obj.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(obj)
    }

    private fun debugAtFrame(romPath: java.nio.file.Path, targetFrame: Int, label: String) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        val output = StringBuilder()

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                frameCount++
                if (frameCount == targetFrame) {
                    val gp = GamePak(java.nio.file.Files.readAllBytes(romPath))
                    val mapper = gp.createMapper()

                    val ppuMem = nestlin.memory.ppuAddressedMemory
                    val control = ppuMem.controller.register.toUnsignedInt()
                    val mask = ppuMem.mask.register.toUnsignedInt()
                    val status = ppuMem.status.register.toUnsignedInt()

                    val firstScanlineNonBlack = frame.scanlines.firstOrNull()?.count { it != 0x000000 } ?: 0

                    val scanline = getIntField(nestlin.ppu, "scanline")
                    val cycle = getIntField(nestlin.ppu, "cycle")

                    output.appendLine("=== $label Nestlin Frame $frameCount ===")
                    output.appendLine("PC: ${nestlin.cpu.registers.programCounter}")
                    output.appendLine("A: ${nestlin.cpu.registers.accumulator.toUnsignedInt()}, X: ${nestlin.cpu.registers.indexX.toUnsignedInt()}, Y: ${nestlin.cpu.registers.indexY.toUnsignedInt()}")
                    output.appendLine("PPU Control: ${String.format("%02X", control)}, Mask: ${String.format("%02X", mask)}, Status: ${String.format("%02X", status)}")
                    output.appendLine("PPU Scanline: $scanline, Cycle: $cycle")
                    output.appendLine("First scanline non-black pixels: $firstScanlineNonBlack")

                    if (mapper is com.github.alondero.nestlin.gamepak.Mapper66) {
                        output.appendLine("Mapper 66 - PRG bank: ${mapper.snapshot().banks["prg"]}, CHR bank: ${mapper.snapshot().banks["chr"]}")
                    }

                    val chr0 = mapper.ppuRead(0x0000).toUnsignedInt()
                    val chr1fff = mapper.ppuRead(0x1FFF).toUnsignedInt()
                    output.appendLine("CHR 0000: ${String.format("%02X", chr0)}, CHR 1FFF: ${String.format("%02X", chr1fff)}")

                    val cpu8000 = mapper.cpuRead(0x8000).toUnsignedInt()
                    val cpuffff = mapper.cpuRead(0xFFFF).toUnsignedInt()
                    output.appendLine("PRG 8000: ${String.format("%02X", cpu8000)}, PRG FFFF: ${String.format("%02X", cpuffff)}")

                    val palette = (0 until 8).map { i -> ppuMem.ppuInternalMemory[0x3F00 + i].toUnsignedInt() }
                    output.appendLine("Palette[0-7]: ${palette.joinToString(" ")}")

                    output.appendLine("")
                    output.appendLine("Frame scanlines: ${frame.scanlines.size}")
                    for (i in 0 until minOf(5, frame.scanlines.size)) {
                        val nonBlack = frame.scanlines[i].count { it != 0x000000 }
                        output.appendLine("  Scanline $i: $nonBlack non-black pixels")
                    }

                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        outputFile.appendText(output.toString())
    }
}