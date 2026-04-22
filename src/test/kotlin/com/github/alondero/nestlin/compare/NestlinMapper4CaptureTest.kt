package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.Test
import java.nio.file.Paths

/**
 * Captures Nestlin state at specific frames for Kirby (Mapper 4).
 * Writes detailed output to build/debug-state/ for analysis.
 */
class NestlinMapper4CaptureTest {

    @Test
    fun captureKirbyAtFrame30() {
        captureAtFrame("testroms/kirby.nes", 30, "kirby-frame30")
    }

    @Test
    fun captureKirbyAtFrame60() {
        captureAtFrame("testroms/kirby.nes", 60, "kirby-frame60")
    }

    @Test
    fun captureKirbyAtFrame100() {
        captureAtFrame("testroms/kirby.nes", 100, "kirby-frame100")
    }

    @Test
    fun captureKirbyAtFrame150() {
        captureAtFrame("testroms/kirby.nes", 150, "kirby-frame150")
    }

    @Test
    fun captureTetrisAtFrame60() {
        captureAtFrame("testroms/tetris.nes", 60, "tetris-frame60")
    }

    private fun captureAtFrame(romPath: String, frame: Int, label: String) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get(romPath))
        }

        var frameCount = 0
        val output = StringBuilder()

        nestlin.addFrameListener(object : com.github.alondero.nestlin.ui.FrameListener {
            override fun frameUpdated(f: Frame) {
                frameCount++
                if (frameCount == frame) {
                    output.appendLine("=== $label ===")
                    output.appendLine("Frame: $frameCount")

                    // CPU
                    val cpu = nestlin.cpu
                    val regs = cpu.registers
                    output.appendLine("CPU: PC=0x${regs.programCounter.toUnsignedInt().toString(16).uppercase().padStart(4, '0')}, A=0x${regs.accumulator.toUnsignedInt().toString(16).uppercase()}, X=0x${regs.indexX.toUnsignedInt().toString(16).uppercase()}, Y=0x${regs.indexY.toUnsignedInt().toString(16).uppercase()}")
                    output.appendLine("     SP=0x${regs.stackPointer.toUnsignedInt().toString(16).uppercase()}, Status=0x${cpu.processorStatus.asByte().toUnsignedInt().toString(16).uppercase()}, Cycles=${cpu.getInstructionCount()}")

                    // PPU
                    val ppu = nestlin.ppu
                    val scanline = getIntField(ppu, "scanline")
                    val ppuCycle = getIntField(ppu, "cycle")
                    val frameCount = getIntField(ppu, "frameCount")
                    output.appendLine("PPU: scanline=$scanline, cycle=$ppuCycle, frame=$frameCount")

                    val ppuMem = nestlin.memory.ppuAddressedMemory
                    output.appendLine("     PPUCTRL=0x${ppuMem.controller.register.toUnsignedInt().toString(16).uppercase()}, PPUMASK=0x${ppuMem.mask.register.toUnsignedInt().toString(16).uppercase()}, PPUSTATUS=0x${ppuMem.status.register.toUnsignedInt().toString(16).uppercase()}")
                    output.appendLine("     VRAMAddr=0x${ppuMem.vRamAddress.asAddress().toString(16).uppercase()}")

                    // Mapper
                    val mapper = nestlin.memory.mapper
                    if (mapper is com.github.alondero.nestlin.gamepak.Mapper4) {
                        val snap = mapper.snapshot()
                        output.appendLine("Mapper4 Banks:")
                        snap.banks.forEach { (k, v) -> output.appendLine("  $k=$v") }
                        output.appendLine("Mapper4 Registers:")
                        snap.registers.forEach { (k, v) -> output.appendLine("  $k=$v") }
                        val irqStateStr = snap.irqState?.map { "$it.key=${it.value}" }?.joinToString(", ") ?: "null"
                        output.appendLine("Mapper4 IRQ: $irqStateStr")

                        // Test some CHR reads
                        output.appendLine("CHR Pattern Reads:")
                        val testAddrs = listOf(0x0000, 0x0400, 0x0800, 0x0C00, 0x1000, 0x1400, 0x1800, 0x1FF8)
                        testAddrs.forEach { addr ->
                            val byte = mapper.ppuRead(addr)
                            output.appendLine("  PPU[0x${addr.toString(16).uppercase().padStart(4,'0')}]=0x${byte.toUnsignedInt().toString(16).uppercase().padStart(2,'0')}")
                        }
                    } else {
                        output.appendLine("Mapper: ${mapper?.javaClass?.simpleName ?: "null"}")
                    }

                    // Internal RAM
                    val internalRam = getObjectField(nestlin.memory, "internalRam") as ByteArray
                    output.appendLine("CPU RAM first 32 bytes: ${(0 until 32).map { "0x${internalRam[it].toUnsignedInt().toString(16).uppercase().padStart(2,'0')}" }.joinToString(" ")}")
                    output.appendLine("CPU RAM stack: ${(0x100 until 0x110).map { "0x${internalRam[it].toUnsignedInt().toString(16).uppercase().padStart(2,'0')}" }.joinToString(" ")}")

                    println(output.toString())
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()
    }

    private fun getIntField(obj: Any, name: String): Int {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.getInt(obj)
    }

    private fun getObjectField(obj: Any, name: String): Any {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(obj)
    }
}