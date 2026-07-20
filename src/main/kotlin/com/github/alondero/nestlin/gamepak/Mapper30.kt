package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 30 (UNROM 512) - RetroUSB's modern UNROM extension. Up to 512 KiB
 * of PRG ROM with one switchable 16 KiB window, 32 KiB of CHR-RAM, and
 * dynamic mirroring that depends on a NES 2.0 submapper or iNES byte 6.
 *
 * The board is the de-facto modern standard for NES homebrew (Lizard,
 * The Meowtrix, etc.), so the implementation here is intentionally
 * straightforward: there is no IRQ counter, no audio expansion, no flash
 * save, no LED register. Mirroring is the only soft state.
 *
 * ## Register layout
 *
 * A single register is written to any address in `$8000-$FFFF`. The byte's
 * bit fields are:
 *
 * | Bits  | Field                | Meaning                                        |
 * |-------|----------------------|------------------------------------------------|
 * | 0-4   | PRG bank (5 bits)    | 16 KiB page at CPU `$8000-$BFFF`               |
 * | 5-6   | CHR-RAM bank (2 bits)| 8 KiB page at PPU `$0000-$1FFF`                |
 * | 7     | Mirroring toggle     | See below — only consulted when enabled       |
 *
 * PRG layout matches Mesen2's `SelectPrgPage(0, value & 0x1F)` /
 * `SelectPrgPage(1, -1)`: page 0 at `$8000-$BFFF` is switchable, page 1 at
 * `$C000-$FFFF` is hard-wired to the LAST 16 KiB of PRG (the reset vector
 * always lives there).
 *
 * CHR-RAM is 32 KiB (four 8 KiB pages) with the bank selected from bits
 * 5-6. All writes hit RAM — there is no CHR-ROM in any submapper.
 *
 * ## Mirroring decode (per Mesen2 `UnRom512.h`)
 *
 * The board exposes three flavours of mirroring depending on the submapper
 * and iNES byte 6 (`bits 3,0`):
 *
 * | Submapper 3 | `byte6 & 0x09 == 0`        | `byte6 & 0x09 == 1`        | `byte6 & 0x09 == 8`        | `byte6 & 0x09 == 9`        |
 * |-------------|----------------------------|----------------------------|----------------------------|----------------------------|
 * | bit 7 → H/V | bit 7 → H/V (always)       | bit 7 → ignored            | bit 7 → ignored            | bit 7 → ignored            |
 * | default V   | default H                  | default V                  | default ScreenA + bit7 A↔B | default FourScreens + CHR  |
 *
 * The nesdev wiki lists byte-6 decode as `00=V, 01=H` (the inverse of the
 * standard iNES bit-0 convention) — Mesen2 disagrees and is the oracle;
 * see memory entry `claudemd-mapper-list-must-track-new-mappers-2026-06-11`
 * and the new-mapper skill's "wiki prose vs Mesen: Mesen wins" rule.
 *
 * Four-screen mode maps the last 8 KiB of CHR-RAM to PPU `$2000-$3EFF`
 * (the InfiniteNesLives variation). We route that via
 * [readNametableOverride] / [writeNametableOverride]; the standard CIRAM
 * path stays active for the palette at `$3F00-$3FFF`.
 *
 * ## Submapper-specific notes
 *
 * - **Submapper 4** declares an LED register at `$8000-$BFFF`. Mesen2
 *   removes that range from the register-write decoder — the writes are
 *   no-ops, matching "currently not emulated". We do the same here.
 * - **Battery-equipped submapper 0/1/4** with writes to `$8000-$BFFF`
 *   would route to a flash SST39SF040 chip in Mesen2. We don't emulate
 *   flash here; Nestlin's battery path is `$6000-$7FFF` PRG-RAM, which
 *   UNROM 512 does not have (Mesen2 returns `GetSaveRamSize() == 0`).
 *   Net effect: battery saves do nothing, which matches the behaviour
 *   most homebrew games expect.
 */
class Mapper30(private val gamePak: GamePak, private val submapper: Int = 0) : Mapper {

    private val programRom = gamePak.programRom
    // 16 KiB PRG pages (UNROM-512's PRG window is one 16 KiB page).
    private val prgBankCount = (programRom.size / 0x4000).coerceAtLeast(1)
    private val lastPrgBank = prgBankCount - 1

    // 32 KiB CHR-RAM (four 8 KiB pages). UNROM 512 has no CHR-ROM variant.
    private val chrRam = ByteArray(0x8000)

    // Register bit fields. PRG and CHR banks are 5-bit / 2-bit respectively;
    // mirroringBit is `bit 7` of the last write OR the initial-state default.
    private var prgBank = 0
    private var chrBank = 0
    // For submapper 3 (H/V toggle) the initial state is VERTICAL per Mesen2,
    // so we seed mirroringBit = 1. For 1-screen switchable modes (bit 7 = A/B
    // toggle) the initial state is ScreenA (bit 7 = 0). See [currentMirroring].
    private var mirroringBit = if (submapper == 3) 1 else 0

    /**
     * Whether bit 7 of the last write is consulted for mirroring. Submapper
     * 3 always consults it (H/V toggle). For other submappers, it's enabled
     * only when iNES byte 6 selects the "1-screen switchable" flavour
     * (`bits 3,0 == 10` → fourScreen=1, mirroring=H). Header-fixed modes
     * (H, V, FourScreens) ignore bit 7.
     */
    private val enableMirroringBit: Boolean =
        submapper == 3 ||
        (gamePak.header.fourScreen && gamePak.header.mirroring == Header.Mirroring.HORIZONTAL)

    /**
     * Resolved mirroring for the "fixed" modes — cached so [currentMirroring]
     * doesn't have to recompute it on every PPU frame. The dynamic 1-screen
     * mode reads `mirroringBit` instead.
     */
    private val fixedMirroring: Mapper.MirroringMode? = when {
        enableMirroringBit -> null  // 1-screen switchable → computed dynamically
        submapper == 3 -> Mapper.MirroringMode.VERTICAL  // initial V for sub-3
        // Header decode (Mesen2's `switch(_romInfo.Header.Byte6 & 0x09)`):
        // 0 → H, 1 → V, 9 → FourScreens (CHR-backed, see nametable override below).
        else -> when (gamePak.header.byte6Flags and 0x09) {
            1 -> Mapper.MirroringMode.VERTICAL
            9 -> Mapper.MirroringMode.ONE_SCREEN_LOWER  // placeholder; nametable override claims it
            else -> Mapper.MirroringMode.HORIZONTAL
        }
    }

    // ---------------------------------------------------------------------
    // CPU bus
    // ---------------------------------------------------------------------

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        // The two PRG windows sit at different addresses ($8000 vs $C000),
        // so the per-bank base has to follow the window — subtracting 0x8000
        // unconditionally would shift $C000 reads into the wrong 16 KiB bank.
        val (bankIndex, windowBase) = if (address < 0xC000) {
            prgBank to 0x8000
        } else {
            lastPrgBank to 0xC000
        }
        // Modulo so a 5-bit PRG-bank write that overruns the available ROM
        // (e.g. bank 31 on a 256 KiB cart) still resolves cleanly, matching
        // Mesen2's `SelectPrgPage` page-mask semantics.
        return programRom[(bankIndex * 0x4000 + (address - windowBase)) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address < 0x8000) return
        // Submapper 4's LED register at $8000-$BFFF: Mesen2 removes those
        // addresses from the write range (LEDs are not emulated). Mirror
        // that here so the register doesn't change underneath the LED.
        if (submapper == 4 && address < 0xC000) return

        val v = value.toUnsignedInt()
        prgBank = v and 0x1F
        chrBank = (v shr 5) and 0x03
        mirroringBit = (v shr 7) and 0x01
    }

    // ---------------------------------------------------------------------
    // PPU bus (CHR)
    // ---------------------------------------------------------------------

    override fun ppuRead(address: Int): Byte {
        val masked = address and 0x1FFF
        return chrRam[(chrBank * 0x2000) + masked]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        chrRam[(chrBank * 0x2000) + (address and 0x1FFF)] = value
    }

    // ---------------------------------------------------------------------
    // Mirroring
    // ---------------------------------------------------------------------

    override fun currentMirroring(): Mapper.MirroringMode {
        // Submapper 3 flips bit 7 between H and V (Mesen2:
        // `value & 0x80 ? Vertical : Horizontal`). All other dynamic-1-screen
        // boards toggle bit 7 between ScreenA and ScreenB.
        if (submapper == 3) {
            return if (mirroringBit == 0) {
                Mapper.MirroringMode.HORIZONTAL
            } else {
                Mapper.MirroringMode.VERTICAL
            }
        }
        if (fixedMirroring != null) return fixedMirroring
        // 1-screen switchable mode: bit 7 toggles between ScreenA (0) and
        // ScreenB (1).
        return if (mirroringBit == 0) {
            Mapper.MirroringMode.ONE_SCREEN_LOWER
        } else {
            Mapper.MirroringMode.ONE_SCREEN_UPPER
        }
    }

    /**
     * Four-screen mode (iNES byte 6 bits 3,0 == 11) maps PPU `$2000-$3EFF`
     * to the LAST 8 KiB of CHR-RAM (offset 0x6000 in the 32 KiB buffer).
     * Wiring this through the nametable override hook keeps the palette at
     * `$3F00-$3FFF` on the standard CIRAM-free path (PpuInternalMemory
     * routes palette directly to PaletteRam, bypassing this hook).
     *
     * The full 4 KiB nametable window ($2000-$2FFF) maps to the FIRST 4 KiB
     * of that 8 KiB backing, preserving the four 1 KiB NT pages (NT0/NT1 at
     * 0x000/0x400, NT2/NT3 at 0x800/0xC00). An 11-bit mask (`0x7FF`) would
     * alias NT2 onto NT0 and NT3 onto NT1 — a real board never does that.
     */
    override fun readNametableOverride(address: Int): Byte? {
        if (fixedMirroring != Mapper.MirroringMode.ONE_SCREEN_LOWER) return null
        if (address !in 0x2000..0x2FFF) return null
        // The `ONE_SCREEN_LOWER` placeholder above is overloaded to flag
        // "four-screen mode active"; resolve here.
        val ntOffset = (address - 0x2000) and 0xFFF
        return chrRam[0x6000 + ntOffset]
    }

    override fun writeNametableOverride(address: Int, value: Byte): Boolean {
        if (fixedMirroring != Mapper.MirroringMode.ONE_SCREEN_LOWER) return false
        if (address !in 0x2000..0x2FFF) return false
        val ntOffset = (address - 0x2000) and 0xFFF
        chrRam[0x6000 + ntOffset] = value
        return true
    }

    // ---------------------------------------------------------------------
    // Snapshot / save state
    // ---------------------------------------------------------------------

    override fun snapshot(): MapperStateSnapshot = MapperStateSnapshot(
        mapperId = 30,
        type = "UNROM512",
        banks = mapOf(
            "prgBank" to prgBank,
            "chrBank" to chrBank,
            "mirroringBit" to mirroringBit
        ),
        registers = emptyMap(),
        irqState = null,
        // UNROM 512 has CHR-RAM, not CHR-ROM — surface the full 32 KiB for
        // the debug overlay. Match the buffer size exactly (8K for UxROM,
        // 12K for N163, 32K here) so callers don't have to know the layout.
        chrRam = ByteArray(chrRam.size) { i -> chrRam[i] },
        prgRam = null
    )

    override val saveStateVersion: Int = 1

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        out.writeInt(mirroringBit)
        out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank = input.readInt()
        chrBank = input.readInt()
        mirroringBit = input.readInt()
        input.readFully(chrRam)
    }
}