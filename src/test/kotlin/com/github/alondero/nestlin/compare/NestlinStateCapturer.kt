package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.cpu.ProcessorStatus
import com.github.alondero.nestlin.cpu.Registers
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path

/**
 * Captures CPU, PPU, and memory state from Nestlin at a specific frame boundary.
 * Uses the existing FrameListener pattern from NestlinHeadlessRunner.
 */
object NestlinStateCapturer {

    fun captureState(romPath: Path, frameNumber: Int): EmulatorStateSnapshot {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var captured: EmulatorStateSnapshot? = null
        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                frameCount++
                if (frameCount == frameNumber) {
                    captured = captureCurrentState(nestlin, romPath.fileName.toString(), frameNumber)
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        return captured ?: throw IllegalStateException("Failed to capture state at frame $frameNumber")
    }

    private fun captureCurrentState(nestlin: Nestlin, romName: String, frameNumber: Int): EmulatorStateSnapshot {
        val cpu = nestlin.cpu
        val ppu = nestlin.ppu
        val memory = nestlin.memory

        // Capture CPU state
        val cpuState = captureCpuState(cpu)

        // Capture PPU state
        val ppuState = capturePpuState(ppu, memory)

        // Capture CPU RAM ($0000-$07FF)
        val cpuRam = IntArray(0x800) { i ->
            getInternalRam(memory)[i].toUnsignedInt()
        }

        // Capture PPU registers ($2000-$2007)
        val ppuRegisters = capturePpuRegisters(memory.ppuAddressedMemory)

        // Capture OAM (256 bytes from ObjectAttributeMemory)
        val oam = IntArray(256) { i ->
            memory.ppuAddressedMemory.objectAttributeMemory[i].toUnsignedInt()
        }

        // Capture Palette RAM via PpuInternalMemory.get() at 0x3F00-0x3F1F
        val paletteRam = IntArray(32) { i ->
            memory.ppuAddressedMemory.ppuInternalMemory[0x3F00 + i].toUnsignedInt()
        }

        return EmulatorStateSnapshot(
            emulator = "Nestlin",
            romName = romName,
            frameNumber = frameNumber,
            cpu = cpuState,
            ppu = ppuState,
            cpuRam = cpuRam,
            ppuRegisters = ppuRegisters,
            oam = oam,
            paletteRam = paletteRam,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun captureCpuState(cpu: Cpu): CpuState {
        val regs = cpu.registers
        val ps = cpu.processorStatus
        return CpuState(
            pc = regs.programCounter.toUnsignedInt(),
            a = regs.accumulator.toUnsignedInt(),
            x = regs.indexX.toUnsignedInt(),
            y = regs.indexY.toUnsignedInt(),
            sp = regs.stackPointer.toUnsignedInt(),
            status = ps.asByte().toUnsignedInt(),
            cycleCount = cpu.getInstructionCount().toLong()
        )
    }

    private fun capturePpuState(ppu: Ppu, memory: com.github.alondero.nestlin.Memory): PpuState {
        return PpuState(
            cycle = getPpuCycle(ppu),
            scanline = getPpuScanline(ppu),
            frameCount = getPpuFrameCount(ppu),
            control = memory.ppuAddressedMemory.controller.register.toUnsignedInt(),
            mask = memory.ppuAddressedMemory.mask.register.toUnsignedInt(),
            status = memory.ppuAddressedMemory.status.register.toUnsignedInt(),
            vRamAddress = memory.ppuAddressedMemory.vRamAddress.asAddress()
        )
    }

    private fun getPpuCycle(ppu: Ppu): Int {
        val field = ppu.javaClass.getDeclaredField("cycle")
        field.isAccessible = true
        return field.getInt(ppu)
    }

    private fun getPpuScanline(ppu: Ppu): Int {
        val field = ppu.javaClass.getDeclaredField("scanline")
        field.isAccessible = true
        return field.getInt(ppu)
    }

    private fun getPpuFrameCount(ppu: Ppu): Int {
        val field = ppu.javaClass.getDeclaredField("frameCount")
        field.isAccessible = true
        return field.getInt(ppu)
    }

    private fun capturePpuRegisters(ppuMem: com.github.alondero.nestlin.ppu.PpuAddressedMemory): PpuRegisters {
        return PpuRegisters(
            controller = ppuMem.controller.register.toUnsignedInt(),
            mask = ppuMem.mask.register.toUnsignedInt(),
            status = ppuMem.status.register.toUnsignedInt(),
            oamAddress = ppuMem.oamAddress.toUnsignedInt(),
            oamData = ppuMem.oamData.toUnsignedInt(),
            scroll = ppuMem.scroll.toUnsignedInt(),
            address = ppuMem.address.toUnsignedInt(),
            data = ppuMem.data.toUnsignedInt()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInternalRam(memory: com.github.alondero.nestlin.Memory): ByteArray {
        val field = memory.javaClass.getDeclaredField("internalRam")
        field.isAccessible = true
        return field.get(memory) as ByteArray
    }
}
