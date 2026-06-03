package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.toUnsignedInt
import java.util.zip.CRC32

private const val TEST_ROM_CRC = 0x9e179d92

class GamePak(data: ByteArray, displayName: String = "") {

    val header = Header(data.copyOfRange(0, 16))
    val name: String = when {
        data.size >= 144 && data[7].toUnsignedInt() and 0x10 != 0 -> {
            String(data.copyOfRange(0x10, 0x10 + 128)).trim().replace('\u0000', ' ').trim()
        }
        displayName.isNotEmpty() -> displayName
        else -> ""
    }
    val programRom: ByteArray
    val chrRom: ByteArray
    val crc: CRC32

    /**
     * Effective timing region for this cartridge. The header is the authority when
     * it carries positive evidence; otherwise we fall back to the NO-INTRO region
     * marker in the ROM name, then default to NTSC. A user-facing override (see
     * `EmulatorConfig.regionOverride`) is applied later, in [com.github.alondero.nestlin.Nestlin].
     */
    val region: Region = header.detectedRegion ?: regionFromName(name) ?: Region.NTSC

    init {
        programRom = data.copyOfRange(16, 16 + 16384 * header.programRomSize)
        chrRom = data.copyOfRange(16 + programRom.size, 16 + programRom.size + 8192 * header.chrRomSize)
        crc = CRC32().apply { update(data)}
    }

    fun isTestRom() = crc.value == TEST_ROM_CRC

    fun createMapper(): Mapper = when (header.mapper) {
        0 -> Mapper0(this)
        1 -> Mapper1(this)
        2 -> Mapper2(this)
        3 -> Mapper3(this)
        4 -> Mapper4(this)
        5 -> Mapper5(this)
        7 -> Mapper7(this)
        9 -> Mapper9(this)
        10 -> Mapper10(this)
        11 -> Mapper11(this)
        21 -> Mapper21(this)
        23 -> Mapper23(this)
        25 -> Mapper25(this)
        34 -> Mapper34(this)
        66 -> Mapper66(this)
        69 -> Mapper69(this)
        71 -> Mapper71(this)
        206 -> Mapper206(this)
        else -> throw UnsupportedOperationException("Mapper ${header.mapper} not implemented")
    }

    override fun toString(): String {
        return "ROM Size: ${programRom.size}, VROM Size: ${chrRom.size}\nMapper: ${header.mapper}\nCRC32: ${crc.value}"
    }

    companion object {
        // NO-INTRO region markers. PAL countries imply PAL timing; the NTSC list
        // wins ties (a ROM tagged both, e.g. multi-region, is treated as NTSC).
        private val ntscMarkers = listOf("(usa", "(u)", "(japan", "(j)", "(jp", "(world", "(ntsc", "(brazil")
        private val palMarkers = listOf(
            "(europe", "(e)", "(eur", "(pal", "(australia", "(france", "(germany",
            "(italy", "(spain", "(sweden", "(netherlands", "(uk", "(scandinavia"
        )

        /** Region implied by a NO-INTRO-style ROM name, or null if it carries no region marker. */
        fun regionFromName(name: String): Region? {
            val lower = name.lowercase()
            if (ntscMarkers.any { lower.contains(it) }) return Region.NTSC
            if (palMarkers.any { lower.contains(it) }) return Region.PAL
            return null
        }
    }
}

class Header(headerData: ByteArray) {

    val programRomSize = headerData[4]
    val programRamSize = headerData[8]
    val chrRomSize = headerData[5]
    val mapper: Int = headerData[6].toUnsignedInt() shr(4) or (headerData[7].toUnsignedInt() and 0xF0)
    val mirroring: Mirroring = if (headerData[6].toUnsignedInt() and 0x01 == 0) Mirroring.HORIZONTAL else Mirroring.VERTICAL
    val hasBattery: Boolean = headerData[6].toUnsignedInt() and 0x02 != 0

    /** True when bits 2-3 of byte 7 are `10`, marking this as a NES 2.0 header. */
    val isNes20: Boolean = (headerData[7].toUnsignedInt() and 0x0C) == 0x08

    /**
     * Region implied by header metadata, or null when the header gives no positive
     * evidence (the common case for plain iNES dumps, where we defer to the filename).
     *
     * NES 2.0 byte 12 bits 0-1: 0=NTSC, 1=PAL, 2=both (undecided), 3=Dendy (PAL-family).
     * Plain iNES byte 9 bit 0: 1=PAL; 0 is *not* treated as evidence of NTSC because
     * the bit is almost universally left clear, including on PAL cartridges.
     */
    val detectedRegion: Region? = when {
        isNes20 -> when (headerData[12].toUnsignedInt() and 0x03) {
            0 -> Region.NTSC
            1 -> Region.PAL
            3 -> Region.PAL
            else -> null      // 2 = "both" → undecided
        }
        (headerData[9].toUnsignedInt() and 0x01) != 0 -> Region.PAL
        else -> null
    }

    enum class Mirroring {
        HORIZONTAL, VERTICAL
    }

}
