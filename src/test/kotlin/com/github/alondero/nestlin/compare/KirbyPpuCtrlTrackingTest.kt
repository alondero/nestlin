package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo

/**
 * Collapsed test from KirbyPpuCtrlTrackingTest and KirbyPpuCtrlWriteTrackerTest.
 * Asserts that PPUCTRL reaches $A8 (NMI enabled) by frame 10 of Kirby boot.
 */
class KirbyPpuCtrlTrackingTest {

    @Test
    fun trackPpuCtrlReachesA8ByFrame10() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        val frameData = mutableListOf<FrameInfo>()
        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                val ppuCtrl = nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt()
                val ppuMask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                val cycleCount = nestlin.cpu.getInstructionCount()
                frameData.add(FrameInfo(frameData.size + 1, ppuCtrl, ppuMask, pc, cycleCount.toInt()))
                frameCount++
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Print summary
        System.err.println("=== PPUCTRL ($2000) Tracking Across Frames ===")
        System.err.println("Frame | PPUCTRL | PPUMASK | CPU PC   | CycleCount")
        System.err.println("------|---------|---------|----------|------------")
        frameData.forEach { info ->
            val ctrlHex = String.format("%02X", info.ppuCtrl)
            val maskHex = String.format("%02X", info.ppuMask)
            val pcHex = String.format("%04X", info.pc)
            System.err.println("${info.frameNumber.toString().padStart(5)} | ${ctrlHex}      | ${maskHex}      | ${pcHex}    | ${info.cycleCount}")
        }

        // === ASSERTION ===
        // Assert that $A8 was observed by frame 10
        val a8Written = frameData.any { it.ppuCtrl == 0xA8 }
        assertThat("PPUCTRL should reach \$A8 by frame 10 of Kirby boot", a8Written, equalTo(true))
    }

    data class FrameInfo(
        val frameNumber: Int,
        val ppuCtrl: Int,
        val ppuMask: Int,
        val pc: Int,
        val cycleCount: Int
    )
}