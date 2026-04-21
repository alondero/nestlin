package com.github.alondero.nestlin.compare

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * State snapshot capturing CPU, PPU, and memory state at a specific frame boundary.
 * Serializable to JSON via Gson for human inspection and programmatic diffing.
 */
data class EmulatorStateSnapshot(
    val emulator: String,
    val romName: String,
    val frameNumber: Int,
    val cpu: CpuState,
    val ppu: PpuState,
    val cpuRam: IntArray,          // $0000-$07FF as unsigned values
    val ppuRegisters: PpuRegisters, // $2000-$2007
    val oam: IntArray,             // 256 bytes as unsigned values
    val paletteRam: IntArray,      // 32 bytes as unsigned values
    val timestamp: Long
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        val gson: Gson = GsonBuilder()
            .setPrettyPrinting()
            .create()
    }
}

data class CpuState(
    val pc: Int,
    val a: Int,
    val x: Int,
    val y: Int,
    val sp: Int,
    val status: Int,
    val cycleCount: Long
)

data class PpuState(
    val cycle: Int,
    val scanline: Int,
    val frameCount: Int,
    val control: Int,
    val mask: Int,
    val status: Int,
    val vRamAddress: Int
)

data class PpuRegisters(
    val controller: Int,  // $2000
    val mask: Int,         // $2001
    val status: Int,       // $2002
    val oamAddress: Int,   // $2003
    val oamData: Int,      // $2004
    val scroll: Int,       // $2005
    val address: Int,       // $2006
    val data: Int          // $2007
)

/**
 * Result of comparing two state snapshots.
 */
data class StateDiffResult(
    val match: Boolean,
    val cpuMismatches: List<CpuMismatch>,
    val ppuMismatches: List<PpuMismatch>,
    val memoryMismatches: List<MemoryMismatch>,
    val message: String
)

data class CpuMismatch(
    val field: String,
    val nestlinValue: Int,
    val mesen2Value: Int
) {
    fun format(): String = "  $field: Nestlin=0x${nestlinValue.toString(16).uppercase().padStart(4, '0')} ($nestlinValue), Mesen2=0x${mesen2Value.toString(16).uppercase().padStart(4, '0')} ($mesen2Value)"
}

data class PpuMismatch(
    val field: String,
    val nestlinValue: Int,
    val mesen2Value: Int
) {
    fun format(): String = "  $field: Nestlin=$nestlinValue, Mesen2=$mesen2Value"
}

data class MemoryMismatch(
    val region: String,
    val address: Int,
    val nestlinValue: Int,
    val mesen2Value: Int
) {
    fun format(): String = "  $region[0x${address.toString(16).uppercase().padStart(4, '0')}]: Nestlin=0x${nestlinValue.toString(16).uppercase().padStart(2, '0')}, Mesen2=0x${mesen2Value.toString(16).uppercase().padStart(2, '0')}"
}
