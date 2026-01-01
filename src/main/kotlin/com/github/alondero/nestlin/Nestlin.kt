package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path

class Nestlin {

    val config = EmulatorConfig()  // Public for UI access
    private var cpu: Cpu
    private var ppu: Ppu
    private val apu: Apu
    private val memory: Memory
    private var running = false
    private var lastFrameTimeNanos: Long = 0

    init {
        memory = Memory()
        cpu = Cpu(memory)
        ppu = Ppu(memory)
        apu = Apu(memory)
        memory.apu = apu
    }

    fun getController1() = memory.controller1

    fun load(romPath: Path) {
        cpu.currentGame = romPath.load()?.let(::GamePak)
    }

    fun addFrameListener(listener: FrameListener) {
        ppu.addFrameListener(listener)
    }

    fun enableLogging() {
        cpu.enableLogging()
    }

    fun getAudioSamples(): ShortArray = apu.getAudioSamples()

    fun powerReset() {
        cpu.reset()
    }

    fun start() {
        running = true
        lastFrameTimeNanos = System.nanoTime()

        try {
            while (running) {
                (1..3).forEach { ppu.tick() }
                apu.tick()
                cpu.tick()

                // Check if frame completed and throttle if needed
                if (ppu.frameJustCompleted()) {
                    throttleIfEnabled()
                }
            }
        } finally {
            // TODO: Development-only feature - Remove undocumented opcode dumping once emulator stability is proven
            // Always dump undocumented opcodes, even if emulation crashes
            cpu.dumpUndocumentedOpcodes()
        }
    }

    /**
     * Throttle emulation speed to match target frame rate.
     * Uses high-precision timing to sleep until the next frame should start.
     * Only throttles if speedThrottlingEnabled is true.
     */
    private fun throttleIfEnabled() {
        if (!config.speedThrottlingEnabled) return

        val currentTime = System.nanoTime()
        val elapsedNanos = currentTime - lastFrameTimeNanos
        val targetNanos = config.targetFrameTimeNanos

        if (elapsedNanos < targetNanos) {
            val sleepNanos = targetNanos - elapsedNanos
            val sleepMillis = sleepNanos / 1_000_000
            val remainderNanos = (sleepNanos % 1_000_000).toInt()

            if (sleepMillis > 0 || remainderNanos > 0) {
                Thread.sleep(sleepMillis, remainderNanos)
            }
        }

        lastFrameTimeNanos = System.nanoTime()
    }

    fun stop() {running = false}
}

class BadHeaderException(message: String) : RuntimeException(message)
