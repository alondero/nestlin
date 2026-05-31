package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import java.nio.file.Path

object CompareCpuCycles {

    fun compareCycles(romPath: Path, label: String, maxFrames: Int = 30) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        var lastCycles = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                frameCount++
                if (frameCount <= maxFrames) {
                    val cycles = nestlin.cpu.getInstructionCount()
                    val delta = if (frameCount == 1) cycles else cycles - lastCycles
                    lastCycles = cycles

                    val ppuMem = nestlin.memory.ppuAddressedMemory
                    val status = ppuMem.status.register.toUnsignedInt()
                    val mask = ppuMem.mask.register.toUnsignedInt()

                    println("$label Frame $frameCount: cycles=$cycles delta=$delta status=$%02X mask=$%02X".format(status, mask))

                    if (frameCount == maxFrames) {
                        nestlin.stop()
                    }
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()
    }
}