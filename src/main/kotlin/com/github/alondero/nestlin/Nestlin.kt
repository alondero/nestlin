package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import fm2.InputReplayer
import java.nio.file.Path

class Nestlin {

    val config = EmulatorConfig()  // Public for UI access
    private var cpu: Cpu
    private var ppu: Ppu
    private val apu: Apu
    private val memory: Memory
    private var running = false
    private var lastFrameTimeNanos: Long = 0
    private var inputReplayer: InputReplayer? = null
    private var replayFinished = false

    init {
        memory = Memory()
        cpu = Cpu(memory)
        ppu = Ppu(memory)
        apu = Apu(memory)
        memory.apu = apu
    }

    fun getController1() = memory.controller1

    fun setInputReplayer(replayer: InputReplayer) {
        this.inputReplayer = replayer
        this.replayFinished = false
    }

    fun isReplayFinished(): Boolean = replayFinished

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
        lastFrameTimeNanos = System.nanoTime()

        try {
            while (running) {
                // Check if replay is active and finished
                if (inputReplayer != null && inputReplayer!!.isFinished()) {
                    replayFinished = true
                    running = false
                    continue
                }

                // Get current frame input if replaying
                val replayFrame = inputReplayer?.nextFrame()
                if (replayFrame != null) {
                    // Apply replay commands (soft reset, hard reset, etc)
                    if ((replayFrame.commands and 0x01) != 0) {
                        // Soft reset - just reset CPU
                        cpu.reset()
                    }
                    if ((replayFrame.commands and 0x02) != 0) {
                        // Hard reset - reset everything
                        powerReset()
                    }
                    // Pass controller state to emulation
                    updateControllerFromReplay(replayFrame)
                }

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

    private fun updateControllerFromReplay(frame: fm2.InputFrame) {
        val controller = memory.controller1
        controller.setButton(Controller.Button.A, frame.port0.a)
        controller.setButton(Controller.Button.B, frame.port0.b)
        controller.setButton(Controller.Button.SELECT, frame.port0.select)
        controller.setButton(Controller.Button.START, frame.port0.start)
        controller.setButton(Controller.Button.UP, frame.port0.up)
        controller.setButton(Controller.Button.DOWN, frame.port0.down)
        controller.setButton(Controller.Button.LEFT, frame.port0.left)
        controller.setButton(Controller.Button.RIGHT, frame.port0.right)
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