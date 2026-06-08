package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.SaveState
import java.io.DataInput
import java.io.DataOutput

interface Mapper {
    fun cpuRead(address: Int): Byte
    fun cpuWrite(address: Int, value: Byte)
    fun ppuRead(address: Int): Byte
    fun ppuWrite(address: Int, value: Byte)
    fun currentMirroring(): MirroringMode

    /**
     * CPU data-bus value, set by [Memory] before each `cpuRead` call. Open-
     * bus reads (i.e. addresses the mapper has no byte of its own to return
     * for) should return this value, matching real 6502 hardware where
     * unmapped reads return the last byte driven on the bus. The default
     * implementation is a no-op (data-bus unknown → mapper returns 0 as
     * before). Mappers that need open-bus behaviour override the setter
     * to capture the value, then read [dataBus] inside [cpuRead].
     *
     * This is the second half of the fix for the Mind Blower Pak /
     * Klax / Mapper 64 / Mapper 113 boot divergence from Mesen2 — see
     * the diagnostic in `Mapper113RegressionTest` and the related memory
     * entry `hes-mb-mesen2-divergence-2026-06-07`.
     */
    var dataBus: Byte
        get() = 0
        set(_) {}

    // IRQ support (for mappers like MMC3 that have scanline IRQs)
    fun notifyA12Edge(rising: Boolean) {}
    fun acknowledgeIrq() {}
    fun isIrqPending(): Boolean = false

    // Clocked once per CPU (M2) cycle. Mappers whose IRQ counter is driven by
    // CPU cycles rather than PPU A12 edges (e.g. Sunsoft FME-7 / mapper 69) use
    // this; A12-clocked mappers (MMC3) leave it as a no-op.
    fun tickCpuCycle() {}

    // State snapshot for debugging
    fun snapshot(): MapperStateSnapshot? = null

    /**
     * Format version of this mapper's saveState block. Bump when fields are
     * added, removed, or reordered — loadState rejects mismatches loudly
     * instead of silently deserialising garbage. See Issue #100.
     */
    val saveStateVersion: Int get() = 1

    /**
     * Save state persistence. The default implementation writes a 1-byte
     * per-mapper version stamp (see [saveStateVersion]) so every mapper block
     * is uniformly versioned. Mappers with state override this method and
     * MUST call `super.saveState(out)` first, then write their own fields.
     */
    fun saveState(out: DataOutput) {
        out.writeByte(saveStateVersion)
    }

    /**
     * Load state persistence. The default implementation reads the 1-byte
     * per-mapper version stamp and rejects mismatches with
     * [SaveState.IncompatibleSaveStateException]. Mappers with state override
     * this method and MUST call `super.loadState(input)` first, then read
     * their own fields.
     */
    fun loadState(input: DataInput) {
        // readUnsignedByte so the comparison is sign-safe for versions 128..255.
        // readByte().toInt() sign-extends, which would make 200 load as -56.
        val version = input.readUnsignedByte().toInt()
        if (version != saveStateVersion) {
            throw SaveState.IncompatibleSaveStateException(
                "${this::class.simpleName} save state version $version not supported " +
                "(expected $saveStateVersion)"
            )
        }
    }

    /** The cartridge's PRG-RAM ($6000-$7FFF) for battery persistence. Null when the mapper has none. */
    fun batteryBackedRam(): ByteArray? = null

    /** True when battery-backed RAM has been written since the last flush. */
    var batteryDirty: Boolean
        get() = false
        set(_) {}

    enum class MirroringMode {
        HORIZONTAL,
        VERTICAL,
        ONE_SCREEN_LOWER,
        ONE_SCREEN_UPPER
    }
}
