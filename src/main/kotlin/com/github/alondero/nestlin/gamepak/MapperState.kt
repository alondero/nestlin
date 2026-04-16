package com.github.alondero.nestlin.gamepak

/**
 * Mapper state snapshot for debugging.
 * Each mapper type provides its own banking state.
 */
data class MapperStateSnapshot(
    val mapperId: Int,
    val type: String,
    val banks: Map<String, Int>,
    val registers: Map<String, Int>,
    val irqState: Map<String, Any>?,
    val chrRam: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MapperStateSnapshot

        if (mapperId != other.mapperId) return false
        if (type != other.type) return false
        if (banks != other.banks) return false
        if (registers != other.registers) return false
        if (irqState != other.irqState) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mapperId
        result = 31 * result + type.hashCode()
        result = 31 * result + banks.hashCode()
        result = 31 * result + registers.hashCode()
        result = 31 * result + (irqState?.hashCode() ?: 0)
        return result
    }
}

/**
 * Interface for mappers to provide state snapshots for debugging.
 */
interface MapperState {
    /**
     * Capture current mapper banking state for debugging.
     */
    fun snapshot(): MapperStateSnapshot
}
