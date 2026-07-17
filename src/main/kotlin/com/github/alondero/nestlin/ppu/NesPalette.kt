package com.github.alondero.nestlin.ppu

/**
 * The NES has a fixed 64-color palette in hardware.
 * Each color is represented as a 24-bit RGB value.
 *
 * Reference: https://www.nesdev.org/wiki/PPU_palettes
 */
object NesPalette {
    private val colors = intArrayOf(
        0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
        0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
        0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
        0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
        0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
        0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
        0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
        0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
    )

    /**
     * Attenuation applied to the two NON-emphasized channels per set emphasis bit —
     * the standard emulator approximation of the PPU's analog de-emphasis circuit
     * (the emphasized channel itself is left at full level).
     */
    private const val EMPHASIS_ATTENUATION = 0.746

    /**
     * Precomputed [emphasis 0-7][palette index 0-63] → RGB. Emphasis bit 0 = red,
     * bit 1 = green, bit 2 = blue (NTSC layout — the PAL red/green swap is the
     * caller's job, see `Ppu.emphasisBits`). Table lookup keeps the per-pixel cost
     * at one array index instead of per-pixel floating point.
     */
    private val emphasisTable = Array(8) { emphasis ->
        IntArray(64) { index ->
            val rgb = colors[index]
            if (emphasis == 0) rgb else {
                var r = ((rgb shr 16) and 0xFF).toDouble()
                var g = ((rgb shr 8) and 0xFF).toDouble()
                var b = (rgb and 0xFF).toDouble()
                if (emphasis and 0x1 != 0) { g *= EMPHASIS_ATTENUATION; b *= EMPHASIS_ATTENUATION }
                if (emphasis and 0x2 != 0) { r *= EMPHASIS_ATTENUATION; b *= EMPHASIS_ATTENUATION }
                if (emphasis and 0x4 != 0) { r *= EMPHASIS_ATTENUATION; g *= EMPHASIS_ATTENUATION }
                (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
    }

    /**
     * Get RGB color for a given NES palette index (0-63).
     * Index is masked to 6 bits to handle mirroring.
     */
    fun getRgb(index: Int): Int = colors[index and 0x3F]

    /** RGB for [index] under the given 3-bit colour-emphasis field (PPUMASK bits 5-7). */
    fun getRgb(index: Int, emphasis: Int): Int = emphasisTable[emphasis and 0x7][index and 0x3F]
}
