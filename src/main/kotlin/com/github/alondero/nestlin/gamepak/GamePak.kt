package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.BadHeaderException
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.toUnsignedInt
import java.util.zip.CRC32

private const val TEST_ROM_CRC = 0x9e179d92
private const val INES_HEADER_SIZE = 16
private const val PRG_BANK_BYTES = 16384
private const val CHR_BANK_BYTES = 8192

class GamePak(data: ByteArray, displayName: String = "") {

    init {
        // Issue #16: validate the iNES header BEFORE any slice/copy, so a
        // corrupt or truncated ROM throws a descriptive BadHeaderException
        // instead of leaking ArrayIndexOutOfBoundsException from copyOfRange.
        // Order matters: length first (otherwise reading data[3] is unsafe),
        // then the 4-byte magic ("NES"), then the declared PRG/CHR sizes.
        requireValidHeader(data)
    }

    val header = Header(data.copyOfRange(0, INES_HEADER_SIZE))
    // The display name comes from the filename. There is NO in-ROM title field at
    // offset 0x10 in iNES / NES 2.0 — that is the start of PRG ROM. Reading it as a
    // title (gated on byte 7 bit 4, which is actually mapper bit D4) rendered PRG
    // opcodes as text and surfaced as a "question mark" titlebar for mapper-16 games
    // like Crayon Shin-chan. See GH #118.
    val name: String = displayName
    val programRom: ByteArray
    val chrRom: ByteArray
    val crc: CRC32

    /**
     * Effective timing region for this cartridge. The header is the authority when
     * it carries positive evidence; otherwise we fall back to the NO-INTRO region
     * marker in the ROM name, then default to NTSC. A user-facing override (see
     * `EmulatorConfig.regionOverride`) is applied later, in [com.github.alondero.nestlin.Nestlin].
     */
    val region: Region = header.detectedRegion
        ?: forceNtscMappers(header.mapper)
        ?: regionFromName(name)
        ?: Region.NTSC

    /**
     * Some mappers are *only* found on NTSC pirate / clone hardware even when
     * the NO-INTRO filename implies a PAL region. The HES NTD-8 / PT-554A
     * (mapper 113) is the canonical example: HES Australia sold their
     * multicarts into a PAL TV market, but the silicon itself is NTSC — so
     * the `(australia)` filename is a *where-sold* marker, not a *timing*
     * one. Forcing NTSC matches the real cartridge's frame rate, palette,
     * and CPU:PPU ratio.
     *
     * This is a hardware-accuracy override, NOT a boot fix: both HES games
     * boot fine under either region once the mapper register decode is
     * correct (see Mapper113.cpuWrite and issue #163 — the garbled title
     * screen there was a decode bug, never a PAL-vs-NTSC timing issue).
     * See [RegionDetectionTest.\`mapper 113 is NTSC even when filename has
     * australia marker\`] for the regression.
     *
     * Only mappers that are *provably* NTSC-only silicon get an override
     * here — not all "Australia" games.
     */
    private fun forceNtscMappers(mapper: Int): Region? = when (mapper) {
        113 -> Region.NTSC
        else -> null
    }

    init {
        // Use toUnsignedInt() because Header.programRomSize / chrRomSize are
        // raw Byte fields. Without it, header.programRomSize = 0xFF (i.e. 255
        // PRG banks, the spec-allowed max) sign-extends to -1 and the bound
        // goes negative -- copyOfRange then throws IllegalArgumentException
        // instead of our BadHeaderException, leaking the wrong exception class
        // for exactly one input value per field. The validator above already
        // treats the byte as unsigned; this just keeps the slice consistent.
        val prgBanks = header.programRomSize.toUnsignedInt()
        val chrBanks = header.chrRomSize.toUnsignedInt()
        programRom = data.copyOfRange(INES_HEADER_SIZE, INES_HEADER_SIZE + PRG_BANK_BYTES * prgBanks)
        chrRom = data.copyOfRange(INES_HEADER_SIZE + programRom.size, INES_HEADER_SIZE + programRom.size + CHR_BANK_BYTES * chrBanks)
        crc = CRC32().apply { update(data)}
    }

    /**
     * Reject ROMs that are not a valid iNES file before any header field is
     * dereferenced. Without this, a truncated or garbage file produces
     * `ArrayIndexOutOfBoundsException` (from `data.copyOfRange(16, ...)`) or
     * a silent default-zero `header.programRomSize` / `header.chrRomSize` —
     * both useless to the user. BadHeaderException is the project's existing
     * typed signal for this exact class of failure (see RomUtils.validate).
     */
    private fun requireValidHeader(data: ByteArray) {
        if (data.size < INES_HEADER_SIZE) {
            throw BadHeaderException(
                "ROM is ${data.size} bytes; iNES header requires at least $INES_HEADER_SIZE bytes"
            )
        }
        if (data[0] != 0x4E.toByte() || data[1] != 0x45.toByte() ||
            data[2] != 0x53.toByte() || data[3] != 0x1A.toByte()) {
            throw BadHeaderException(
                "Missing iNES header magic \"NES\\x1A\" (got bytes " +
                    "${data[0].toUnsignedInt()}, ${data[1].toUnsignedInt()}, " +
                    "${data[2].toUnsignedInt()}, ${data[3].toUnsignedInt()})"
            )
        }
        // Read the declared sizes straight from the raw bytes (NOT from
        // header.programRomSize etc.) so the messages can cite the literal
        // value the user sees in the header. The numbers are bytes 4 and 5.
        val prgBanks = data[4].toUnsignedInt()
        val chrBanks = data[5].toUnsignedInt()
        val declaredPrgBytes = prgBanks.toLong() * PRG_BANK_BYTES
        val declaredChrBytes = chrBanks.toLong() * CHR_BANK_BYTES
        val availableForPrg = data.size - INES_HEADER_SIZE
        if (declaredPrgBytes > availableForPrg) {
            throw BadHeaderException(
                "Header declares $prgBanks 16KB PRG bank(s) (${declaredPrgBytes} bytes) " +
                    "but ROM is only $data.size bytes ($availableForPrg bytes after the header)"
            )
        }
        val availableForChr = availableForPrg - declaredPrgBytes
        if (declaredChrBytes > availableForChr) {
            throw BadHeaderException(
                "Header declares $chrBanks 8KB CHR bank(s) (${declaredChrBytes} bytes) " +
                    "but only $availableForChr bytes remain in the ROM after the header and PRG"
            )
        }
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
        16 -> Mapper16(this, header.submapper)
        18 -> Mapper18(this)
        19 -> Mapper19(this)
        21 -> Mapper21(this)
        23 -> Mapper23(this)
        24 -> Mapper24(this)
        25 -> Mapper25(this)
        26 -> Mapper26(this)
        33 -> Mapper33(this)
        34 -> Mapper34(this)
        64 -> Mapper64(this)
        65 -> Mapper65(this)
        66 -> Mapper66(this)
        68 -> Mapper68(this)
        69 -> Mapper69(this)
        71 -> Mapper71(this)
        113 -> Mapper113(this)
        153 -> Mapper153(this)
        206 -> Mapper206(this)
        228 -> Mapper228(this)
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

    /** True when bits 2-3 of byte 7 are `10`, marking this as a NES 2.0 header. */
    val isNes20: Boolean = (headerData[7].toUnsignedInt() and 0x0C) == 0x08

    // Mapper number (NESdev iNES / NES 2.0):
    //   bits 0-3  = byte 6 bits 4-7
    //   bits 4-7  = byte 7 bits 4-7  (bit 4 IS mapper bit D4 in both iNES and
    //               NES 2.0 — there is no "title-present" flag here)
    //   bits 8-11 = byte 8 bits 0-3  (NES 2.0 only; byte 8 bits 4-7 are the
    //               submapper and must NOT leak into the mapper number)
    // In plain iNES, byte 8 is the PRG-RAM size, so it is excluded.
    val mapper: Int = (headerData[6].toUnsignedInt() ushr 4) or
        (headerData[7].toUnsignedInt() and 0xF0) or
        (if (isNes20) ((headerData[8].toUnsignedInt() and 0x0F) shl 8) else 0)
    val mirroring: Mirroring = if (headerData[6].toUnsignedInt() and 0x01 == 0) Mirroring.HORIZONTAL else Mirroring.VERTICAL
    val hasBattery: Boolean = headerData[6].toUnsignedInt() and 0x02 != 0

    /**
     * Submapper number from the NES 2.0 header byte 8 (high nibble). For
     * iNES 1.0 headers this is byte 8 of the header (the "PRG-RAM size" byte),
     * which is unrelated; we treat it as 0 unless the header is actually NES 2.0.
     *
     * Mappers that ship multiple board variants (mapper 16 = FCG-1/2/LZ93D50;
     * mapper 4 = MMC3 vs MMC6) read this to pick the right register layout.
     */
    val submapper: Int = if (isNes20) (headerData[8].toUnsignedInt() ushr 4) and 0x0F else 0

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
