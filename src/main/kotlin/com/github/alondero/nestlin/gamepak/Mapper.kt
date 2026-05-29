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

    // Clocked once per CPU (M2) cycle. Mappers whose IRQ counter is driven by
    // CPU cycles rather than PPU A12 edges (e.g. Sunsoft FME-7 / mapper 69) use
    // this; A12-clocked mappers (MMC3) leave it as a no-op.
    fun tickCpuCycle() {}

    // State snapshot for debugging
    fun snapshot(): MapperStateSnapshot? = null

    // Save state persistence. Default no-op for mappers that have no mutable state.
    fun saveState(out: DataOutput) {}
    fun loadState(input: DataInput) {}

    enum class MirroringMode {
        HORIZONTAL,
        VERTICAL,
        ONE_SCREEN_LOWER,
        ONE_SCREEN_UPPER
    }
}
