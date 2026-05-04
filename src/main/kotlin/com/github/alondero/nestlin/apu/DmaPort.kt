package com.github.alondero.nestlin.apu

/**
 * Seam for DMC sample DMA reads.
 * DmcChannel reads bytes from CPU address space during sample playback.
 * Separating this into an interface allows DmcChannel to be tested
 * without a full Memory/MAPPER dependency, and breaks the Memory→Apu→DmcChannel→Memory cycle.
 */
interface DmaPort {
    operator fun get(address: Int): Byte
}
