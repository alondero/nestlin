package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import java.nio.file.Path

class Nestlin {

    private var cpu: Cpu
    private var ppu: Ppu
    private val apu: Apu
    private val memory: Memory
    private var running = false

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

    fun enablePpuDiagnostics(startFrame: Int = 3, endFrame: Int = 8) {
        ppu.enableDiagnosticLogging(startFrame, endFrame)
    }

    fun getAudioSamples(): ShortArray = apu.getAudioSamples()

    fun powerReset() {
        cpu.reset()
    }

    fun start() {
        running = true

        try {
            while (running) {
                (1..3).forEach { ppu.tick() }
                apu.tick()
                cpu.tick()
            }
        } finally {
            // TODO: Development-only feature - Remove undocumented opcode dumping once emulator stability is proven
            // Always dump undocumented opcodes, even if emulation crashes
            cpu.dumpUndocumentedOpcodes()
        }
    }

    fun stop() {running = false}
}

class BadHeaderException(message: String) : RuntimeException(message)