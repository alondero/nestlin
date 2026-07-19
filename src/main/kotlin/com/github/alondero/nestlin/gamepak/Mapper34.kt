package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 34 — two unrelated boards sharing one iNES number:
 *  - **BNROM** (NES 2.0 submapper 2): single write-anywhere register at `$8000-$FFFF`,
 *    32 KB switchable PRG (low 3 bits), 8 KB switchable CHR (next 2 bits).
 *  - **NINA-001** (NES 2.0 submapper 1): three separate registers, 32 KB switchable
 *    PRG via `$7FFD` (bit 0), two independent 4 KB CHR windows via `$7FFE` (→ PPU
 *    `$0000-$0FFF`) and `$7FFF` (→ PPU `$1000-$1FFF`). 8 KB PRG-RAM at `$6000-$7FFF`.
 *
 * **Variant detection.** NES 2.0 submapper is authoritative (1 = NINA-001,
 * 2 = BNROM, anything else falls through). For plain iNES dumps with no
 * submapper byte, we fall back to a CHR-size heuristic: `>8 KB CHR` implies
 * NINA-001 (the 4 KB CHR bank pairs only make sense with enough ROM to fill
 * both windows) and `≤8 KB CHR` implies BNROM. Real-world commercial NINA-001
 * titles (Impossible Mission II etc.) carry ≥16 KB CHR, BNROM titles
 * (Deadly Towers) carry exactly 8 KB, so the heuristic is unambiguous in
 * practice. 0 KB CHR (CHR-RAM homebrew) defaults to BNROM — both variants
 * would expose the same CHR-RAM regardless of decode, and BNROM's
 * write-anywhere register is the more permissive decode.
 *
 * **PRG geometry** (shared): the entire 32 KB PRG window at `$8000-$FFFF`
 * switches as one unit (no fixed bank, unlike UNROM/Mapper 2). The NINA-001
 * PRG field is 1 bit (max 64 KB PRG, two 32 KB banks); the BNROM PRG field
 * is 3 bits (max 128 KB, four 32 KB banks).
 *
 * **CHR.** Both variants get the same 0 KB-CHR → 8 KB CHR-RAM fallback as
 * the other discrete-logic mappers (Mappers 2/3/7/11/33/71). When CHR is
 * present, BNROM exposes a single 8 KB window (2-bit bank select), NINA-001
 * exposes two independent 4 KB windows (4-bit bank select each, 16 banks max).
 *
 * **Mirroring.** Both variants use fixed mirroring from the iNES header
 * (solder-pad on real hardware). No software-controlled mirroring register.
 *
 * **PRG-RAM.** BNROM has none. NINA-001 has 8 KB at `$6000-$7FFF`. We expose
 * it via [batteryBackedRam] unconditionally; the Save RAM layer in
 * `SaveRam.kt` and `Nestlin.loadBatteryRam` gates actual `.sav` persistence
 * on `Header.hasBattery` (same convention as Mapper 10 and Mapper 16).
 *
 * **No IRQ.** Both variants lack an IRQ device.
 *
 * **Games:** Deadly Towers, etc. (BNROM); Impossible Mission II, etc. (NINA-001).
 */
class Mapper34(private val gamePak: GamePak, submapper: Int = 0) : Mapper {

    /**
     * Two boards, one mapper number. The read/write/ppuRead paths branch on this.
     * Detection happens once at construction so the per-cycle hot path is a single
     * `when` on an enum, not a repeated header/heuristic recheck.
     */
    private enum class Variant { BNROM, NINA001 }

    private val variant: Variant = when {
        // NES 2.0 submapper is authoritative.
        gamePak.header.isNes20 && submapper == 1 -> Variant.NINA001
        gamePak.header.isNes20 && submapper == 2 -> Variant.BNROM
        // Plain iNES (or NES 2.0 with an unknown submapper): heuristic on CHR size.
        // NINA-001 needs >= 2 4KB CHR banks to do anything useful; BNROM never has more than 8KB.
        gamePak.chrRom.size > 0x2000 -> Variant.NINA001
        else -> Variant.BNROM
    }

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    // CHR bus: ROM when present, otherwise 8 KB of CHR-RAM. Same fallback pattern
    // as Mapper 2 / 3 / 7 / 11 / 33 / 71 so homebrew dumps don't crash the PPU read.
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // BNROM: single 32KB PRG bank (low 3 bits) + single 8KB CHR bank (next 2 bits).
    private var prgBank: Int = 0
    private var chrBank: Int = 0

    // NINA-001: PRG bank via $7FFD (bit 0, max 64KB PRG / 2 banks).
    // CHR bank 0 at PPU $0000-$0FFF via $7FFE (4-bit, max 64KB CHR / 16 banks).
    // CHR bank 1 at PPU $1000-$1FFF via $7FFF (4-bit, max 64KB CHR / 16 banks).
    private var ninaPrgBank: Int = 0
    private var ninaChrBank0: Int = 0
    private var ninaChrBank1: Int = 0

    // NINA-001's 8 KB PRG-RAM at $6000-$7FFF. Exposed via batteryBackedRam()
    // unconditionally; Nestlin.loadBatteryRam gates .sav persistence on the header's
    // battery flag. NINA-001 boards in the wild don't carry a battery (Impossible
    // Mission II has no save game), so the buffer is normally volatile.
    private val prgRam: ByteArray = ByteArray(0x2000)
    override var batteryDirty: Boolean = false

    override fun cpuRead(address: Int): Byte {
        return when (variant) {
            Variant.NINA001 -> when {
                // NINA-001: 8 KB PRG-RAM at $6000-$7FFF. The 0x1FFF mask handles the
                // 8KB window cleanly ($7FFF - $6000 + 1 = $2000).
                address in 0x6000..0x7FFF -> prgRam[(address - 0x6000) and 0x1FFF]
                // All 32 KB of PRG is switchable at $8000-$FFFF.
                address in 0x8000..0xFFFF -> {
                    val bankOffset = ninaPrgBank * 0x8000
                    val offset = address - 0x8000
                    programRom[(bankOffset + offset) % programRom.size]
                }
                else -> 0
            }
            Variant.BNROM -> {
                if (address < 0x8000) return 0
                val bankOffset = prgBank * 0x8000
                val offset = address - 0x8000
                programRom[(bankOffset + offset) % programRom.size]
            }
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        when (variant) {
            Variant.NINA001 -> when (address) {
                // NINA-001's three discrete registers. Writes outside these addresses
                // are silently dropped (the chip doesn't decode them).
                0x7FFD -> ninaPrgBank = value.toUnsignedInt() and 0x01   // 1 bit / 64KB max
                0x7FFE -> ninaChrBank0 = value.toUnsignedInt() and 0x0F  // 4 bits / 16 4KB banks
                0x7FFF -> ninaChrBank1 = value.toUnsignedInt() and 0x0F
                // Writes to $6000-$7FFF go to PRG-RAM. Always accepted (NINA-001 has
                // no write-protect bit, unlike MMC3 / Sunsoft).
                in 0x6000..0x7FFF -> {
                    prgRam[(address - 0x6000) and 0x1FFF] = value
                    batteryDirty = true
                }
            }
            Variant.BNROM -> {
                // BNROM's single register fires on any write in $8000-$FFFF.
                // Low 3 bits select the 32 KB PRG bank; next 2 bits select the 8 KB CHR bank.
                // Bits 5-7 are unused on real BNROM hardware (the board lacks wiring
                // for them); modulo at read time keeps oversized writes harmless.
                if (address in 0x8000..0xFFFF) {
                    val v = value.toUnsignedInt()
                    prgBank = v and 0x07
                    chrBank = (v shr 3) and 0x03
                }
            }
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF
        return when (variant) {
            Variant.NINA001 -> {
                if (chrRom.isEmpty()) {
                    chrMemory.read(maskedAddress)
                } else {
                    val bank = if (maskedAddress < 0x1000) ninaChrBank0 else ninaChrBank1
                    val base = if (maskedAddress < 0x1000) 0 else 0x1000
                    chrRom[(bank * 0x1000 + (maskedAddress - base)) % chrRom.size]
                }
            }
            Variant.BNROM -> {
                if (chrRom.isEmpty()) {
                    chrMemory.read(maskedAddress)
                } else {
                    // Single 8 KB CHR bank. chrRom.size modulo handles oversized
                    // bank writes (e.g. a 2-bit field selecting bank 3 on an 8KB ROM
                    // would index 24 KB out — modulo wraps to bank 0 of a second copy).
                    chrRom[(chrBank * 0x2000 + maskedAddress) % chrRom.size]
                }
            }
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            // CHR RAM is writable; CHR ROM ignores writes.
            chrMemory.write(address and 0x1FFF, value)
        }
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    /**
     * Expose the NINA-001 PRG-RAM for `.sav` persistence. BNROM has no PRG-RAM
     * so we return null in that case (the SaveRam layer will then skip the
     * load/save round-trip). The Nestlin layer (`Nestlin.loadBatteryRam`)
     * additionally gates persistence on `Header.hasBattery`, so an NINA-001
     * dump without the battery bit set will still allocate the buffer but
     * never write it to disk.
     */
    override fun batteryBackedRam(): ByteArray? = when (variant) {
        Variant.NINA001 -> prgRam
        Variant.BNROM -> null
    }

    /**
     * Save state version 3 — bumped from 2 because:
     *   - The variant field is new (selects the read/write decode at load time).
     *   - NINA-001 needs separate chrBank0/chrBank1 fields.
     *   - NINA-001 PRG-RAM is new.
     * Old v2 save states for this mapper will be rejected as incompatible.
     */
    override val saveStateVersion: Int = 3

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        // Variant first so loadState can dispatch before reading the rest.
        out.writeInt(variant.ordinal)
        out.writeInt(prgBank)
        out.writeInt(chrBank)
        out.writeInt(ninaPrgBank)
        out.writeInt(ninaChrBank0)
        out.writeInt(ninaChrBank1)
        out.write(prgRam)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        // Read the variant back and verify it matches this instance's variant.
        // If a save state was made with a different decode (BNROM <-> NINA-001),
        // refuse to corrupt it — the underlying bank registers mean different
        // things in each variant.
        val loadedVariantOrdinal = input.readInt()
        if (loadedVariantOrdinal != variant.ordinal) {
            throw com.github.alondero.nestlin.SaveState.IncompatibleSaveStateException(
                "Mapper34 variant mismatch: save state was ${Variant.values()[loadedVariantOrdinal]} " +
                    "but this cartridge's header decodes as $variant"
            )
        }
        prgBank = input.readInt()
        chrBank = input.readInt()
        ninaPrgBank = input.readInt()
        ninaChrBank0 = input.readInt()
        ninaChrBank1 = input.readInt()
        input.readFully(prgRam)
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot {
        return when (variant) {
            Variant.BNROM -> MapperStateSnapshot(
                mapperId = 34,
                type = "BNROM",
                banks = mapOf(
                    "variant" to 0,
                    "prgBank" to prgBank,
                    "chrBank" to chrBank
                ),
                registers = emptyMap(),
                irqState = null,
                chrRam = chrMemory.snapshotBytes(),
                prgRam = null
            )
            Variant.NINA001 -> MapperStateSnapshot(
                mapperId = 34,
                type = "NINA-001",
                banks = mapOf(
                    "variant" to 1,
                    "prgBank" to ninaPrgBank,
                    "chrBank0" to ninaChrBank0,
                    "chrBank1" to ninaChrBank1
                ),
                registers = emptyMap(),
                irqState = null,
                chrRam = chrMemory.snapshotBytes(),
                prgRam = prgRam.copyOf()
            )
        }
    }
}