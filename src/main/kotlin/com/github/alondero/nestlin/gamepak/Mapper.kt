package com.github.alondero.nestlin.gamepak

import java.io.DataInput
import java.io.DataOutput

interface Mapper {
    fun cpuRead(address: Int): Byte
    fun cpuWrite(address: Int, value: Byte)
    fun ppuRead(address: Int): Byte
    fun ppuWrite(address: Int, value: Byte)
    fun currentMirroring(): MirroringMode

    // IRQ support (for mappers like MMC3 that have scanline IRQs)
    fun notifyA12Edge(rising: Boolean) {}
    fun acknowledgeIrq() {}
    fun isIrqPending(): Boolean = false

    // State snapshot for debugging
    fun snapshot(): MapperStateSnapshot? = null

    // Save state persistence. Default no-op for mappers that have no mutable state.
    fun saveState(out: DataOutput) {}
    fun loadState(input: DataInput) {}

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
