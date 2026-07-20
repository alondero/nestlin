package com.github.alondero.nestlin.testutil

import com.github.alondero.nestlin.gamepak.GamePak

/**
 * Fluent builder for in-memory iNES / NES 2.0 test ROMs.
 *
 * Hand-built 16-byte headers in test fixtures have caused a recurring class of
 * bugs in this project. This builder owns every encoding decision so individual
 * tests can't get them wrong:
 *
 *  - **4-byte magic** `"NES\x1A"` — several test helpers historically wrote only
 *    3 bytes (or none), producing `BadHeaderException` once `GamePak` started
 *    validating (GH #16).
 *  - **Unit conversion** — header byte 4 counts *16KB* PRG units and byte 5
 *    counts *8KB* CHR units. Tests that stamp 8KB PRG banks or 1KB CHR pages
 *    repeatedly mixed these up. Here you give sizes in KB ([prgKb]/[chrKb]) and
 *    the builder divides; it refuses sizes that aren't a whole number of units.
 *  - **Mapper nibble split** — mapper bits 0-3 live in byte 6 bits 4-7, bits 4-7
 *    in byte 7 bits 4-7. Byte 7 bit 4 IS mapper bit D4 (NOT a "title present"
 *    flag — that misreading broke all NES 2.0 mapper-16+ ROM loading in PR #117).
 *  - **NES 2.0 encoding** — setting [submapper] (or a mapper number above 255)
 *    forces NES 2.0: byte 7 bits 2-3 = `0b10`, byte 8 = `(submapper shl 4) or
 *    ((mapper shr 8) and 0x0F)`. The submapper is the HIGH nibble; the LOW
 *    nibble is mapper bits 8-11. Getting these swapped decodes as a completely
 *    different mapper.
 *  - **Signed-byte hazard** — Kotlin header bytes are signed; the builder only
 *    ever converts *to* bytes (masking with `and 0xFF`), and round-trip tests go
 *    through the real `Header` decode which widens with `toUnsignedInt()`.
 *
 * Usage:
 * ```kotlin
 * val rom: ByteArray = testRom {
 *     mapper = 33
 *     prgKb = 128
 *     chrKb = 32
 *     stampPrgBanks(windowKb = 8)
 *     stampChrBanks(windowKb = 1)
 *     resetVector(0x8000)
 * }
 * // or, when a GamePak is wanted directly:
 * val pak: GamePak = testGamePak { mapper = 4; prgKb = 32; chrKb = 8 }
 * ```
 */
class TestRomBuilder {

    /** iNES mapper number, 0..4095. Values above 255 force NES 2.0 encoding. */
    var mapper: Int = 0

    /**
     * Submapper (NES 2.0 byte 8 high nibble), 0..15. Setting this to any
     * non-null value forces NES 2.0 encoding even for submapper 0, because a
     * test that *names* the submapper wants the NES 2.0 byte-8 path exercised.
     */
    var submapper: Int? = null

    /** Battery-backed PRG-RAM flag — byte 6 bit 1. */
    var battery: Boolean = false

    /** Mirroring — byte 6 bit 0 (0 = horizontal, 1 = vertical). */
    var verticalMirroring: Boolean = false

    /**
     * Four-screen VRAM flag — byte 6 bit 3 (`0x08`). When set the board wires an
     * extra 2 KB of nametable RAM and the H/V bit is ignored. See GH #105.
     */
    var fourScreen: Boolean = false

    /**
     * PRG ROM size in KB. Must be a multiple of 16 (the iNES PRG unit).
     * Re-assigning replaces [prg] with a fresh zeroed array of the new size.
     */
    var prgKb: Int = 16
        set(value) {
            require(value > 0 && value % 16 == 0) {
                "prgKb must be a positive multiple of 16 (iNES byte 4 counts 16KB units), got $value"
            }
            require(value / 16 <= 0xFF) { "prgKb/16 must fit one header byte, got ${value / 16} units" }
            field = value
            prg = ByteArray(value * 1024)
        }

    /**
     * CHR ROM size in KB. Must be a multiple of 8 (the iNES CHR unit); 0 means
     * CHR-RAM (no CHR data in the file). Re-assigning replaces [chr].
     */
    var chrKb: Int = 8
        set(value) {
            require(value >= 0 && value % 8 == 0) {
                "chrKb must be a non-negative multiple of 8 (iNES byte 5 counts 8KB units), got $value"
            }
            require(value / 8 <= 0xFF) { "chrKb/8 must fit one header byte, got ${value / 8} units" }
            field = value
            chr = ByteArray(value * 1024)
        }

    /** Raw PRG data (size = prgKb * 1024) — mutate in place for custom stamping. */
    var prg: ByteArray = ByteArray(prgKb * 1024)
        private set

    /** Raw CHR data (size = chrKb * 1024) — mutate in place for custom stamping. */
    var chr: ByteArray = ByteArray(chrKb * 1024)
        private set

    /**
     * Stamps the first byte of every [windowKb]-sized PRG bank with that bank's
     * index (masked to 8 bits), so a mapper test can read the first byte of a
     * CPU window and assert exactly which bank is mapped there.
     */
    fun stampPrgBanks(windowKb: Int) = stampBanks(prg, windowKb, "PRG")

    /** As [stampPrgBanks] but for CHR banks. */
    fun stampChrBanks(windowKb: Int) = stampBanks(chr, windowKb, "CHR")

    private fun stampBanks(data: ByteArray, windowKb: Int, label: String) {
        require(windowKb > 0) { "windowKb must be positive" }
        val windowBytes = windowKb * 1024
        require(data.isNotEmpty()) { "$label is empty — set ${label.lowercase()}Kb before stamping" }
        require(data.size % windowBytes == 0) {
            "$label size ${data.size} is not a multiple of the ${windowKb}KB stamp window"
        }
        for (bank in 0 until data.size / windowBytes) {
            data[bank * windowBytes] = (bank and 0xFF).toByte()
        }
    }

    /**
     * Writes [address] little-endian at the file positions that the LAST 16KB
     * PRG bank exposes as the 6502 reset vector ($FFFC/$FFFD) — i.e. the last
     * 4th- and 3rd-from-end bytes of PRG. Most mappers fix the last bank at
     * $C000-$FFFF on power-up, so this is where a real ROM's reset vector lives.
     */
    fun resetVector(address: Int) {
        require(address in 0..0xFFFF) { "reset vector must be a 16-bit address, got $address" }
        prg[prg.size - 4] = (address and 0xFF).toByte()
        prg[prg.size - 3] = ((address shr 8) and 0xFF).toByte()
    }

    /** Assembles header + PRG + CHR into a complete iNES file image. */
    fun build(): ByteArray {
        require(mapper in 0..0xFFF) { "mapper must be 0..4095, got $mapper" }
        submapper?.let { require(it in 0..15) { "submapper must be 0..15, got $it" } }
        // Mapper numbers above 255 need byte 8 bits 0-3, which only exists in NES 2.0.
        val nes20 = submapper != null || mapper > 0xFF

        val header = ByteArray(16)
        // The full 4-byte magic. Helpers that set only "NES" (or nothing) were a
        // recurring source of BadHeaderException after GH #16.
        header[0] = 'N'.code.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte()
        header[3] = 0x1A
        header[4] = (prgKb / 16).toByte()  // 16KB PRG units
        header[5] = (chrKb / 8).toByte()   // 8KB CHR units (0 = CHR-RAM)
        header[6] = (((mapper and 0x0F) shl 4) or
            (if (battery) 0x02 else 0x00) or
            (if (fourScreen) 0x08 else 0x00) or
            (if (verticalMirroring) 0x01 else 0x00)).toByte()
        // Byte 7 bits 4-7 are mapper bits D4-D7 — bit 4 included (it is NOT a flag).
        // Bits 2-3 = 0b10 marks NES 2.0.
        header[7] = ((mapper and 0xF0) or (if (nes20) 0x08 else 0x00)).toByte()
        if (nes20) {
            // NES 2.0 byte 8: high nibble = submapper, low nibble = mapper bits 8-11.
            header[8] = ((((submapper ?: 0) and 0x0F) shl 4) or ((mapper shr 8) and 0x0F)).toByte()
        }
        return header + prg + chr
    }

    /** Builds the ROM image and loads it through the real [GamePak] constructor. */
    fun buildGamePak(displayName: String = "testrom.nes"): GamePak = GamePak(build(), displayName)
}

/** Builds a complete iNES file image. See [TestRomBuilder]. */
fun testRom(block: TestRomBuilder.() -> Unit): ByteArray = TestRomBuilder().apply(block).build()

/** Builds a [GamePak] directly. See [TestRomBuilder]. */
fun testGamePak(block: TestRomBuilder.() -> Unit): GamePak = TestRomBuilder().apply(block).buildGamePak()
