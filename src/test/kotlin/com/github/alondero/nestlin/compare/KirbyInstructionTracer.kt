package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.cpu.ProcessorStatus
import com.github.alondero.nestlin.cpu.Registers
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Paths

/**
 * Debug test to trace instruction execution for Kirby's Adventure.
 * Captures the first N instructions to see if game is taking expected code path.
 */
object KirbyInstructionTracer {

    // Track PPU register writes
    data class PpuWrite(
        val instructionCount: Int,
        val pc: Int,
        val address: Int,
        val value: Int
    )

    fun traceInstructions(romPath: String, maxInstructions: Int = 2000): InstructionTraceResult {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get(romPath))
        }

        val cpu = nestlin.cpu
        var instructionCount = 0
        val instructionLog = mutableListOf<InstructionEntry>()
        val ppuWrites = mutableListOf<PpuWrite>()
        var nmiCount = 0

        // Hook into CPU execution to trace instructions
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: com.github.alondero.nestlin.ppu.Frame) {
                // We'll stop after maxInstructions instead
            }
        })

        // We need a custom run loop that stops after maxInstructions
        // Since we can't easily hook into the tick, we'll use reflection to access internal state

        nestlin.powerReset()

        // Run for a limited number of CPU cycles (approximately maxInstructions * 3 cycles each)
        val maxCycles = maxInstructions * 6
        var cycleCount = 0

        // Manually tick to control execution
        // This mirrors Nestlin.start() but with instruction counting
        try {
            // Use reflection to access private 'running' field and stop condition
            val runningField = nestlin.javaClass.getDeclaredField("running")
            runningField.isAccessible = true

            while (runningField.getBoolean(nestlin) && instructionCount < maxInstructions) {
                // Tick PPU 3 times, APU 1 time, CPU 1 time (matching Nestlin.start())
                for (i in 0 until 3) nestlin.ppu.tick()
                nestlin.apu.tick()
                nestlin.cpu.tick()

                cycleCount++

                // Check if we should stop (instruction executed)
                // We can check the CPU's workCyclesLeft to detect when a new instruction starts
                val workCyclesField = cpu.javaClass.getDeclaredField("workCyclesLeft")
                workCyclesField.isAccessible = true
                val workCycles = workCyclesField.getInt(cpu)

                if (workCycles <= 0) {
                    // New instruction starting - log it
                    val pcField = cpu.javaClass.getDeclaredField("registers")
                    pcField.isAccessible = true
                    val registers = pcField.get(cpu) as Registers

                    val pc = registers.programCounter.toUnsignedInt()

                    // Read the opcode at current PC (before increment)
                    val memory = nestlin.memory
                    val opcode = memory[pc].toUnsignedInt()

                    instructionLog.add(InstructionEntry(
                        sequence = instructionCount,
                        pc = pc,
                        opcode = opcode
                    ))

                    // Check for PPU register writes ($2000-$2007)
                    // We detect these by reading PPU state changes after each instruction
                    instructionCount++
                }

                if (cycleCount >= maxCycles) break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return InstructionTraceResult(
            instructions = instructionLog,
            ppuWrites = ppuWrites,
            totalInstructions = instructionCount,
            totalCycles = cycleCount,
            nmiCount = nmiCount
        )
    }

    data class InstructionEntry(
        val sequence: Int,
        val pc: Int,
        val opcode: Int
    )

    data class InstructionTraceResult(
        val instructions: List<InstructionEntry>,
        val ppuWrites: List<PpuWrite>,
        val totalInstructions: Int,
        val totalCycles: Int,
        val nmiCount: Int
    )
}