package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.rewind.RewindBuffer
import com.github.alondero.nestlin.ui.FrameListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    // @Volatile: start() runs on the emulation thread and reads this in the
    // while-loop; stop() is called from the UI thread. Without the volatile
    // barrier the JMM permits the emulation thread to cache the read and the
    // loop may never observe the stop (issue #12).
    @Volatile
    private var running = false
    private var nextSyncDeadlineNanos: Long = 0
    private var ticksSinceLastSync: Int = 0

    // Active timing region, derived from the ROM (and any user override) at load/reset.
    private var region: Region = Region.NTSC
    // PPU "dot credit" accumulator (×10). Each CPU cycle adds region.ppuDotsPerCpuTimes10
    // and we emit one PPU tick per 10 credits: NTSC = exactly 3, PAL = 3,3,3,3,4 (avg 3.2).
    private var ppuDotCredit: Int = 0

    // --- Rewind (issue #52) ---
    // Ring buffer of per-frame savestates. Captured by a frame-completion listener during
    // normal play; consumed (scrubbed backward) when [rewindActive] is set by the UI.
    val rewindBuffer = RewindBuffer(config.rewindCapacityFrames)
    // @Volatile: set by the JavaFX thread (hold/release Backspace), read by the emulation
    // loop. A plain bool could be cached in the loop and never observe the release.
    @Volatile
    private var rewindActive = false
    // Tracker of whether we are *currently* in a rewind pass, so the loop can run the one-shot
    // enter/exit transitions (mute audio, reset throttle baseline). Written by the emulation
    // thread; @Volatile because the JavaFX thread reads it via [isRewinding] each frame to drive
    // the on-screen indicator, and would otherwise see a stale value.
    @Volatile
    private var rewinding = false
    // Set while a rewind step re-renders a frame, so the capture listener doesn't record the
    // re-simulated frames back into the buffer (which would corrupt the timeline being scrubbed).
    private var suppressRewindCapture = false

    init {
        memory = Memory()
        cpu = Cpu(memory)
        memory.cpu = cpu
        ppu = Ppu(memory)
        apu = Apu(memory)
        memory.apu = apu
        // One savestate per frame into the rewind ring. Registered on the long-lived PPU so it
        // survives ROM loads/resets; fires on the emulation thread at a clean frame boundary.
        ppu.addFrameCompletionListener { captureRewindSnapshot() }
    }

    fun getController1() = memory.controller1
    fun getController2() = memory.controller2

    fun load(romPath: Path) {
        val data = romPath.load()
        val displayName = romPath.fileName.toString().removeSuffix(".nes").removeSuffix(".7z")
        cpu.currentGame = data?.let { GamePak(it, displayName) }
        applyRegion()
        // Rewind history is per-ROM: a snapshot made against ROM A can't be loaded into ROM B
        // (the savestate ROM/mapper guard would reject it). Drop it on every ROM swap.
        rewindBuffer.clear()
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

    /**
     * Side-effect-free read of a CPU bus address for the Memory Editor (issue #168).
     * Delegates to [Memory.peek]; the same [memory] instance is reused across ROM
     * loads and resets, so a viewer holding this reference keeps working without
     * needing to reopen. See [Memory.peek] for the side-effect and thread-safety
     * contract.
     */
    fun peekMemory(address: Int): Byte = memory.peek(address)

    /**
     * Write a byte to a CPU bus address from the Memory Editor (issue #170).
     * Delegates to [Memory.poke], which applies full game-visible side effects
     * (the running game sees the value on its next read) except for the
     * blacklisted `$4014`/`$4016` I/O registers. See [Memory.poke].
     */
    fun pokeMemory(address: Int, value: Byte) = memory.poke(address, value)

    fun powerReset() {
        cpu.reset()
        applyRegion()
        // A power-cycle is a fresh timeline; any rewind history predates this boot. Clearing
        // here also covers the Hard Reset path (resetRomForMovieSession -> load + powerReset).
        rewindBuffer.clear()
    }

    /**
     * Engage or disengage rewind scrubbing (issue #52). Called from the UI thread on
     * hold/release of the Backspace key. While active and [EmulatorConfig.rewindEnabled], the
     * emulation loop walks backward through [rewindBuffer] at ~3× real-time, re-rendering each
     * scrubbed frame and muting audio; on release it resumes normal play from the scrubbed point.
     */
    fun setRewindActive(active: Boolean) {
        rewindActive = active
    }

    /** True while a rewind scrub pass is in progress (drives the on-screen indicator). */
    fun isRewinding(): Boolean = rewinding

    /** Number of frames currently retained in the rewind buffer (diagnostics/tests). */
    fun rewindBufferSize(): Int = rewindBuffer.size

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
                // Rewind scrubbing (issue #52): while Backspace is held, walk backward through
                // the savestate ring instead of advancing the machine. Needs a game and the
                // feature enabled — otherwise fall through to normal play.
                if (rewindActive && config.rewindEnabled && cpu.currentGame != null) {
                    if (!rewinding) enterRewind()
                    stepRewind()
                    continue
                }
                if (rewinding) exitRewind()
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

    // ---------------------------------------------------------------------------------------
    // Rewind scrubbing internals (issue #52). All run on the emulation thread.
    // ---------------------------------------------------------------------------------------

    /**
     * Capture the just-completed frame into the rewind ring. Invoked by the PPU
     * frame-completion listener. No-op when rewind is disabled, when no game is loaded, or
     * while a rewind step is re-rendering (so re-simulated frames don't pollute the buffer).
     */
    private fun captureRewindSnapshot() {
        if (!config.rewindEnabled) return
        if (suppressRewindCapture) return
        if (cpu.currentGame == null) return
        try {
            val out = ByteArrayOutputStream(INITIAL_SNAPSHOT_CAPACITY)
            SaveState.save(this, out)
            rewindBuffer.capture(out.toByteArray())
        } catch (e: Throwable) {
            // This runs on the emulation thread for EVERY frame, so an unhandled throw here
            // (e.g. a mapper with an incomplete saveState, or OOM building the ~6 MB buffer)
            // would kill the whole emulator and hang the UI on its join(). Rewind is a
            // convenience feature, never worth crashing play for: log once, disable it, and
            // free the partial history so the game keeps running.
            System.err.println("[REWIND] Snapshot capture failed; disabling rewind: ${e.message}")
            config.rewindEnabled = false
            rewindBuffer.clear()
        }
    }

    /** One-shot transition into a rewind pass: mute audio so scrubbing is silent, not crackly. */
    private fun enterRewind() {
        rewinding = true
        apu.outputMuted = true
    }

    /** One-shot transition out of a rewind pass: unmute and reset the throttle baseline. */
    private fun exitRewind() {
        rewinding = false
        apu.outputMuted = false
        // The machine just resumed from a scrubbed point; don't let syncToWallClock treat the
        // scrub duration as drift to "catch up" by sprinting through the next chunk of frames.
        nextSyncDeadlineNanos = System.nanoTime()
        ticksSinceLastSync = 0
    }

    /**
     * Advance one rewind step: jump back [REWIND_FRAMES_PER_STEP] snapshots, load the snapshot
     * now at the head, and re-render exactly one frame so the screen shows the rewound state
     * (savestates carry no pixels). The re-render advances the machine one frame past the
     * snapshot, but the next step reloads from the ring, so that drift never compounds.
     * Paced to ~one display frame so the scrub reads as ~3× real-time backward.
     */
    private fun stepRewind() {
        val snapshot = rewindBuffer.rewind(REWIND_FRAMES_PER_STEP)
        if (snapshot == null) {
            // Empty buffer (Backspace held before any frame was captured): nothing to scrub to,
            // and capture only runs during normal play, so the buffer can never fill while we
            // sit here. Sleep one frame UNCONDITIONALLY — paceRewindFrame would skip the sleep
            // with throttling off, leaving the emulation thread spinning at 100% CPU for nothing.
            try {
                Thread.sleep(EMPTY_REWIND_PARK_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        suppressRewindCapture = true
        try {
            SaveState.load(this, ByteArrayInputStream(snapshot))
            runOneFrameForRender()
        } finally {
            suppressRewindCapture = false
        }
        paceRewindFrame()
    }

    /**
     * Drive the emulator to the next frame boundary so the render listener fires and the
     * canvas updates. Clears any stale frame-complete latch first, and bails if the emulator
     * is stopped mid-pass.
     */
    private fun runOneFrameForRender() {
        ppu.frameJustCompleted()  // consume any stale one-shot latch from normal play
        while (running) {
            stepCpuCycle()
            if (ppu.frameJustCompleted()) return
        }
    }

    /**
     * Sleep ~one display frame between rewind steps so scrubbing runs at roughly display rate.
     * Each step moves back [REWIND_FRAMES_PER_STEP] frames, so the apparent backward speed is
     * ~3× real-time. Honors the throttle toggle: with throttling off (e.g. Tab fast-forward),
     * scrubbing runs as fast as the host allows.
     */
    private fun paceRewindFrame() {
        if (!config.speedThrottlingEnabled) return
        val frameNanos = config.targetFrameTimeNanos
        try {
            Thread.sleep(frameNanos / 1_000_000, (frameNanos % 1_000_000).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun stop() {
        running = false
        // Defensive: if we're stopped mid-rewind, make sure audio isn't left muted for the
        // next start(). stop() runs on the UI thread; outputMuted is volatile so this is safe.
        apu.outputMuted = false
        rewinding = false
    }

    companion object {
        // Sync to wall clock every ~1 ms of emulated time (1789 ticks at 1.79 MHz).
        // Small enough that APU production gaps are barely perceptible to the audio
        // thread, large enough that sync overhead stays negligible.
        private const val TICKS_PER_SYNC_CHUNK = 1789
        // Skip sub-0.1 ms sleeps; JVM/OS timer resolution can't honor them reliably.
        private const val MIN_SLEEP_NANOS = 100_000L
        // If wall-clock is more than 100 ms ahead of emulation, give up trying to catch up.
        private const val MAX_DRIFT_NANOS = 100_000_000L
        // Frames to walk back per rewind step. Paced at ~display rate (60 Hz), 3 frames/step
        // gives ~3× real-time backward scrubbing (issue #52 acceptance criterion).
        private const val REWIND_FRAMES_PER_STEP = 3
        // Pre-size the per-frame snapshot stream to skip the early doubling reallocs. A typical
        // savestate is ~10 KB; 16 KB covers most mappers' state without growing.
        private const val INITIAL_SNAPSHOT_CAPACITY = 16 * 1024
        // Idle sleep when rewind is held against an empty buffer — keeps the emulation thread
        // off the CPU instead of busy-spinning (paceRewindFrame is skipped when throttling is off).
        private const val EMPTY_REWIND_PARK_MILLIS = 16L
    }
}

class BadHeaderException(message: String) : RuntimeException(message)
