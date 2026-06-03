package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class Nestlin {

    val config = EmulatorConfig()  // Public for UI access
    val cpu: Cpu
    val ppu: Ppu
    val apu: Apu
    internal val memory: Memory  // internal for test access
    private var running = false
    private var nextSyncDeadlineNanos: Long = 0
    private var ticksSinceLastSync: Int = 0

    // Active timing region, derived from the ROM (and any user override) at load/reset.
    private var region: Region = Region.NTSC
    // PPU "dot credit" accumulator (×10). Each CPU cycle adds region.ppuDotsPerCpuTimes10
    // and we emit one PPU tick per 10 credits: NTSC = exactly 3, PAL = 3,3,3,3,4 (avg 3.2).
    private var ppuDotCredit: Int = 0

    init {
        memory = Memory()
        cpu = Cpu(memory)
        ppu = Ppu(memory)
        apu = Apu(memory)
        memory.apu = apu
    }

    fun getController1() = memory.controller1

    fun load(romPath: Path) {
        val data = romPath.load()
        val displayName = romPath.fileName.toString().removeSuffix(".nes").removeSuffix(".7z")
        cpu.currentGame = data?.let { GamePak(it, displayName) }
        applyRegion()
    }

    /** The region currently in effect (after override + detection). */
    fun currentRegion(): Region = region

    /**
     * Resolve the effective region (manual override beats ROM auto-detection, which
     * beats NTSC) and push it into every timing-sensitive subsystem. Also aligns the
     * throttle's target frame rate with the region. Safe to call repeatedly.
     */
    fun applyRegion() {
        region = config.regionOverride ?: cpu.currentGame?.region ?: Region.NTSC
        ppu.region = region
        apu.region = region
        config.targetFps = region.refreshRateHz
    }

    fun currentGameName(): String = cpu.currentGame?.name ?: "No Game Loaded"

    fun addFrameListener(listener: FrameListener) {
        ppu.addFrameListener(listener)
    }

    fun enableLogging() {
        cpu.enableLogging()
    }

    fun getAudioSamples(): ShortArray = apu.getAudioSamples()
    fun getAudioSampleRateHz(): Double = apu.outputSampleRateHz()

    fun ppuMask(): Int = memory.ppuAddressedMemory.mask.register.toUnsignedInt()

    fun powerReset() {
        cpu.reset()
        applyRegion()
    }

    /** Write the current emulator state to [out]. Caller is responsible for closing. */
    fun saveState(out: OutputStream) = SaveState.save(this, out)

    /** Restore emulator state from [input]. Caller is responsible for closing. */
    fun loadState(input: InputStream) = SaveState.load(this, input)

    /** Convenience overload: write save state to a file path. */
    fun saveState(path: Path) {
        Files.newOutputStream(path).use { saveState(it) }
    }

    /** Convenience overload: read save state from a file path. */
    fun loadState(path: Path) {
        Files.newInputStream(path).use { loadState(it) }
    }

    /**
     * Load battery-backed SRAM from `saves/<rom-basename>.sav` into the current mapper's PRG-RAM.
     * No-op if the cartridge has no battery flag, no mapper PRG-RAM, or the file is absent/wrong size.
     * Must be called AFTER [powerReset] so the mapper exists.
     */
    fun loadBatteryRam(romPath: Path) {
        val game = cpu.currentGame ?: return
        if (!game.header.hasBattery) return
        val mapper = memory.mapper ?: return
        SaveRam.load(batteryRamPath(romPath), mapper)
    }

    /**
     * Persist the current mapper's battery-backed PRG-RAM to `saves/<rom-basename>.sav`.
     * No-op if the cartridge has no battery flag or the mapper has no PRG-RAM.
     */
    fun saveBatteryRam(romPath: Path) {
        val game = cpu.currentGame ?: return
        if (!game.header.hasBattery) return
        val mapper = memory.mapper ?: return
        SaveRam.save(batteryRamPath(romPath), mapper)
    }

    /** Flush only if the mapper reports its battery RAM has been written since the last flush. */
    fun flushBatteryRamIfDirty(romPath: Path) {
        val mapper = memory.mapper ?: return
        if (!mapper.batteryDirty) return
        saveBatteryRam(romPath)
    }

    private fun batteryRamPath(romPath: Path): Path {
        val base = romPath.fileName.toString().removeSuffix(".nes").removeSuffix(".7z")
        return Paths.get("saves", "$base.sav")
    }

    /**
     * Advance the machine by exactly one CPU cycle, ticking the PPU the correct
     * number of dots for the active region (3 for NTSC, 3-or-4 for PAL averaging
     * 3.2). Extracted so tests can drive emulation at the right ratio without
     * re-implementing the loop. APU and CPU each tick once per CPU cycle.
     */
    fun stepCpuCycle() {
        ppuDotCredit += region.ppuDotsPerCpuTimes10
        while (ppuDotCredit >= 10) {
            ppu.tick()
            ppuDotCredit -= 10
        }
        apu.tick()
        cpu.tick()
    }

    fun start() {
        running = true
        nextSyncDeadlineNanos = System.nanoTime()
        ticksSinceLastSync = 0
        ppuDotCredit = 0

        try {
            while (running) {
                if (config.paused) {
                    Thread.sleep(10)
                    // Reset throttle baseline so the first frame after resume isn't sped up.
                    // Without this, syncToWallClock would treat the pause duration as drift to "catch up".
                    nextSyncDeadlineNanos = System.nanoTime()
                    ticksSinceLastSync = 0
                    continue
                }
                stepCpuCycle()
                ticksSinceLastSync++

                if (ticksSinceLastSync >= TICKS_PER_SYNC_CHUNK) {
                    ticksSinceLastSync = 0
                    syncToWallClock()
                }
            }
        } finally {
            // TODO: Development-only feature - Remove undocumented opcode dumping once emulator stability is proven
            // Always dump undocumented opcodes, even if emulation crashes
            cpu.dumpUndocumentedOpcodes()
        }
    }

    // Sleeping the emulation thread for ~16 ms per frame starves the APU consumer;
    // syncing every ~1 ms keeps Apu.audioBuffer continuously fed without changing the
    // total throttle time.
    private fun syncToWallClock() {
        if (!config.speedThrottlingEnabled) return

        val nanosPerTick = config.targetFrameTimeNanos / region.cpuCyclesPerFrame
        nextSyncDeadlineNanos += TICKS_PER_SYNC_CHUNK * nanosPerTick

        val now = System.nanoTime()
        val ahead = nextSyncDeadlineNanos - now

        if (ahead > MIN_SLEEP_NANOS) {
            try {
                val sleepMillis = ahead / 1_000_000
                val remainderNanos = (ahead % 1_000_000).toInt()
                Thread.sleep(sleepMillis, remainderNanos)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else if (-ahead > MAX_DRIFT_NANOS) {
            // Emulation is running slower than wall clock and falling behind. Reset
            // the deadline so we don't try to "catch up" by skipping all future sleeps
            // (which would defeat throttling on hosts where the emu can briefly outpace
            // its target during transient stalls).
            nextSyncDeadlineNanos = now
        }
    }

    fun stop() {running = false}

    companion object {
        // Sync to wall clock every ~1 ms of emulated time (1789 ticks at 1.79 MHz).
        // Small enough that APU production gaps are barely perceptible to the audio
        // thread, large enough that sync overhead stays negligible.
        private const val TICKS_PER_SYNC_CHUNK = 1789
        // Skip sub-0.1 ms sleeps; JVM/OS timer resolution can't honor them reliably.
        private const val MIN_SLEEP_NANOS = 100_000L
        // If wall-clock is more than 100 ms ahead of emulation, give up trying to catch up.
        private const val MAX_DRIFT_NANOS = 100_000_000L
    }
}

class BadHeaderException(message: String) : RuntimeException(message)
