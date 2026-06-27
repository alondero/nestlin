package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.rewind.RewindBuffer
import com.github.alondero.nestlin.session.LoadedRom
import com.github.alondero.nestlin.session.RegionConfig
import com.github.alondero.nestlin.session.RewindStateMachine
import com.github.alondero.nestlin.session.RunLoop
import com.github.alondero.nestlin.session.SystemClock
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

    /**
     * Canonical ROM identity (issue #189): the parsed [GamePak] paired with the disk path it
     * was read from. Replaces what was previously split between `cpu.currentGame` and
     * `Application.currentRomPath`. Null before [load] is called.
     */
    var loadedRom: LoadedRom? = null
        private set

    /**
     * Resolved timing region for the current session (issue #189). Built once per [load]/[powerReset]
     * from `EmulatorConfig.regionOverride ?: loadedRom.gamePak.region ?: Region.NTSC`, then pushed
     * into [ppu] / [apu] and the [RunLoop] throttle. `EmulatorConfig.targetFps` now reads through
     * to `regionConfig.targetFps` (no more writeback — the previous writeback was a duplicated fact).
     */
    var regionConfig: RegionConfig = RegionConfig.NtscDefault
        private set

    // PPU "dot credit" accumulator (×10). Each CPU cycle adds region.ppuDotsPerCpuTimes10
    // and we emit one PPU tick per 10 credits: NTSC = exactly 3, PAL = 3,3,3,3,4 (avg 3.2).
    private var ppuDotCredit: Int = 0

    // --- Rewind (issue #52 + #189) ---
    // The ring buffer is constructed here; the state machine in `session/` owns the
    // IDLE/SCRUBBING/WAS_REWINDING transitions. Buffer itself is the same bounded-array backing
    // store the rewind feature has used since issue #52.
    //
    // `internal` so same-module tests can verify buffer state directly (RewindCaptureTest pulls
    // snapshots out of the ring to assert scrub determinism). External code should go through
    // [RewindStateMachine].
    internal val rewindBuffer = RewindBuffer(config.rewindCapacityFrames)
    // `lateinit` so the property can be declared above the init block that constructs it —
    // the lazy `runLoop` delegate below references it, and Kotlin's data-flow analysis
    // doesn't model "the lazy block fires after init".
    //
    // `internal` so external callers go through the thin delegations ([setRewindActive],
    // [isRewinding], [rewindBufferSize]) rather than reaching into the machine.
    internal lateinit var rewind: RewindStateMachine

    /**
     * The emulation loop, owned here so [start]/[stop] can be called from the JavaFX app
     * thread without exposing the loop internals. Constructed lazily on first [start] so tests
     * that never run the loop (movie, replay, bootcheck, Mesen2 comparison) don't allocate it.
     *
     * Takes [rewind] via a provider lambda so this lazy block can be declared above the rewind
     * assignment in [init] without confusing Kotlin's init-order analysis.
     *
     * `internal` — external callers use [start] / [stop], not the loop object directly.
     */
    internal val runLoop: RunLoop by lazy {
        RunLoop(
            nestlin = this,
            config = config,
            rewindProvider = { rewind },
            clock = SystemClock,
        )
    }

    init {
        // Memory↔APU circular wiring goes through the factory (issue #22): the factory
        // builds Memory, hands it to Apu (DmaPort for DMC), then attaches Apu back to
        // Memory (for $4000-$401F register dispatch). After this, memory.apu is
        // non-null and stays that way for the lifetime of the Memory instance.
        Memory.createWithApu().also { (m, a) -> memory = m; apu = a }
        cpu = Cpu(memory)
        memory.cpu = cpu
        ppu = Ppu(memory)

        // Rewind state machine (issue #189): the buffer + the dispatch closures are wired here;
        // the machine itself is testable independently because every side effect is a lambda.
        // Constructed BEFORE the frame-completion listener below so the listener can reference it.
        rewind = RewindStateMachine(
            buffer = rewindBuffer,
            saveState = { saveCurrentStateToBytes() },
            loadState = { bytes -> loadCurrentStateFromBytes(bytes) },
            renderOneFrame = { runOneFrameForRender() },
            setMuted = { muted -> apu.outputMuted = muted },
            paceFrame = { paceRewindFrame() },
            park = { parkRewindBufferEmpty() },
            isGameLoaded = { loadedRom != null },
            isEnabled = { config.rewindEnabled },
        )

        // One savestate per frame into the rewind ring. Registered on the long-lived PPU so it
        // survives ROM loads/resets; fires on the emulation thread at a clean frame boundary.
        ppu.addFrameCompletionListener {
            rewind.captureFrame { error ->
                // Capture failure runs on the emulation thread for EVERY frame, so an unhandled
                // throw here (e.g. a mapper with an incomplete saveState, or OOM building the
                // ~6 MB buffer) would kill the whole emulator and hang the UI on its join().
                // Rewind is a convenience feature, never worth crashing play for: log once,
                // disable it, and free the partial history so the game keeps running.
                System.err.println("[REWIND] Snapshot capture failed; disabling rewind: ${error.message}")
                config.rewindEnabled = false
                rewindBuffer.clear()
            }
        }
    }

    fun getController1() = memory.controller1
    fun getController2() = memory.controller2

    fun load(romPath: Path) {
        val data = romPath.load()
        val displayName = romPath.fileName.toString().removeSuffix(".nes").removeSuffix(".7z")
        val gamePak = data?.let { GamePak(it, displayName) }
        loadedRom = gamePak?.let { LoadedRom(it, romPath) }
        // cpu.currentGame is the canonical mapper-install sentinel (SaveState, mapper.load).
        // Keep it in sync with loadedRom; the path lives only in loadedRom from this point on.
        cpu.currentGame = gamePak
        applyRegion()
        // Rewind history is per-ROM: a snapshot made against ROM A can't be loaded into ROM B
        // (the savestate ROM/mapper guard would reject it). Drop it on every ROM swap.
        rewind.clearBuffer()
    }

    /** The region currently in effect (after override + detection). */
    fun currentRegion(): Region = regionConfig.region

    /**
     * Resolve the effective region (manual override beats ROM auto-detection, which beats NTSC)
     * and push it into every timing-sensitive subsystem. Safe to call repeatedly — pushes through
     * `RegionConfig` which is a value object (issue #189: no more double-storage of the fact).
     */
    fun applyRegion() {
        regionConfig = RegionConfig(config.regionOverride ?: cpu.currentGame?.region ?: Region.NTSC)
        ppu.region = regionConfig.region
        apu.region = regionConfig.region
    }

    fun currentGameName(): String = loadedRom?.gamePak?.name ?: "No Game Loaded"

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
        rewind.clearBuffer()
    }

    /**
     * Engage or disengage rewind scrubbing (issue #52). Called from the UI thread on
     * hold/release of the Backspace key. While active and [EmulatorConfig.rewindEnabled], the
     * emulation loop walks backward through the rewind buffer at ~3× real-time, re-rendering each
     * scrubbed frame and muting audio; on release it resumes normal play from the scrubbed point.
     *
     * Delegates to [RewindStateMachine.setRewindActive] (issue #189).
     */
    fun setRewindActive(active: Boolean) {
        rewind.setRewindActive(active)
    }

    /** True while a rewind scrub pass is in progress (drives the on-screen indicator). */
    fun isRewinding(): Boolean = rewind.isRewinding()

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
        ppuDotCredit += regionConfig.region.ppuDotsPerCpuTimes10
        while (ppuDotCredit >= 10) {
            ppu.tick()
            ppuDotCredit -= 10
        }
        apu.tick()
        cpu.tick()
    }

    fun start() = runLoop.start()

    fun stop() {
        runLoop.stop()
        // `runLoop.stop()` already cleared `apu.outputMuted` defensively. We additionally
        // clear the rewind-active flag here so a subsequent start() doesn't resume mid-scrub.
        // The state machine's own state catches up on the next `tick()` (SCRUBBING→WAS_REWINDING→IDLE).
        rewind.setRewindActive(false)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers for [RewindStateMachine] closures (issue #189). Each lambda captures `this` —
    // the state machine does the transitions; we expose the per-tick side effects here.
    // ---------------------------------------------------------------------------------------

    /** Serialise the current state into a savestate blob — used by [RewindStateMachine.saveState]. */
    private fun saveCurrentStateToBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        SaveState.save(this, out)
        return out.toByteArray()
    }

    /** Restore from a savestate blob — used by [RewindStateMachine.loadState]. */
    private fun loadCurrentStateFromBytes(bytes: ByteArray) {
        SaveState.load(this, java.io.ByteArrayInputStream(bytes))
    }

    /**
     * Drive the emulator to the next frame boundary so the render listener fires and the
     * canvas updates. Clears any stale frame-complete latch first, and bails if the emulator
     * is stopped mid-pass.
     */
    private fun runOneFrameForRender() {
        ppu.frameJustCompleted()  // consume any stale one-shot latch from normal play
        while (runLoop.isRunning) {
            stepCpuCycle()
            if (ppu.frameJustCompleted()) return
        }
    }

    /**
     * Sleep ~one display frame between rewind steps so scrubbing runs at roughly display rate.
     * Each step moves back [RewindStateMachine.framesPerStep] frames, so the apparent backward
     * speed is ~3× real-time. Honors the throttle toggle: with throttling off (e.g. Tab
     * fast-forward), scrubbing runs as fast as the host allows.
     */
    private fun paceRewindFrame() {
        if (!config.speedThrottlingEnabled) return
        val frameNanos = regionConfig.targetFrameTimeNanos
        SystemClock.sleepNanos(frameNanos)
    }

    /**
     * Idle sleep when rewind is held against an empty buffer — keeps the emulation thread off the
     * CPU instead of busy-spinning. [paceRewindFrame] is skipped when throttling is off, so the
     * state machine calls us unconditionally on an empty buffer.
     */
    private fun parkRewindBufferEmpty() {
        SystemClock.sleepNanos(EMPTY_REWIND_PARK_NANOS)
    }

    private companion object {
        // Idle sleep when rewind is held against an empty buffer — keeps the emulation thread
        // off the CPU instead of busy-spinning (paceRewindFrame is skipped when throttling is off).
        // 16 ms ≈ one display frame.
        private const val EMPTY_REWIND_PARK_NANOS = 16_000_000L
    }
}

class BadHeaderException(message: String) : RuntimeException(message)
