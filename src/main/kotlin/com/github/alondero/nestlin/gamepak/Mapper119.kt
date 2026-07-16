package com.github.alondero.nestlin.gamepak

import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 119 — Nintendo MMC6 (a.k.a. MMC3C variant).
 *
 * MMC6 is the chip behind *Metal Storm*, *StarTropics II: Zoda's Revenge*, and
 * ~6 other titles that need an MMC3-shaped mapper with a small on-board
 * work-RAM. The chip is mechanically identical to MMC3 for everything except
 * the PRG-RAM:
 *
 *  - **PRG / CHR / scanline IRQ** are bit-identical to MMC3, so we subclass
 *    [Mapper4] and only override the register-write hooks that diverge. The IRQ
 *    register reads at `$C000/$C001/$E000/$E001` return open-bus on Mesen2
 *    (its `MMC3::ReadRegister` falls through to the mapped PRG bank) — no
 *    special read-as-status logic is needed.
 *  - **Work RAM** is 1KB at `$7000-$73FF`, NOT 8KB at `$6000-$7FFF`. It is
 *    split into two 512-byte halves with **independent R/W control**:
 *    | Bank | Address      | $A001 read bit | $A001 write bit |
 *    |------|--------------|----------------|-----------------|
 *    | 0    | `$7000-$71FF`| bit 5          | bit 4           |
 *    | 1    | `$7200-$73FF`| bit 7          | bit 6           |
 *  - The 1KB block is gated by **bit 5 of `$8000`** (W enable). When clear,
 *    the entire `$7000-$73FF` range is open-bus on read and writes are discarded.
 *  - **No PRG-RAM at `$6000-$6FFF`** — real MMC6 silicon does not expose the
 *    classic MMC3 8KB PRG-RAM there. Reads return 0, writes are discarded.
 *
 * The Mesen2 oracle (`Core/NES/Mappers/Nintendo/MMC3.h` submapper-1 branch)
 * confirms the WRAM access split. The GitHub issue text for #136 mentioned a
 * "read-as-status" behavior on `$C000/$C001/$E000/$E001` — this is **not**
 * what Mesen2 implements (the base `ReadRegister` returns 0 and the address
 * falls through to the mapped PRG bank), so per the project's "Mesen wins"
 * oracle policy we mirror that. The original issue text was design intent,
 * not Mesen behavior, and RAMBO-1 / VRC4 / Mapper 65 have all shipped with
 * the Mesen oracle winning over prose.
 *
 * Games: ~8 titles — *Metal Storm* (the worked example), *StarTropics II:
 * Zoda's Revenge*, *Burai Fighter*, *Indiana Jones and the Last Crusade
 * (Taito)*, *Mighty Bomb Jack*, *Power Blade 2*, *Tetra Star: The Fighter*.
 */
class Mapper119(private val gamePak: GamePak) : Mapper4(gamePak) {

    /**
     * 1KB MMC6 work-RAM, exposed as two 512-byte banks. Bank 0 occupies
     * `$7000-$71FF` (index `[0]`), bank 1 occupies `$7200-$73FF` (index `[1]`).
     * Indexed by bank → 512-byte slice.
     */
    private val wrRam = Array(2) { ByteArray(0x200) }

    /** Latched from `$8000` bit 5. When false, all `$7000-$73FF` reads return 0 and writes are discarded. */
    private var wrRamEnabled = false

    /** R/W control latched from `$A001` bits 4-7: bit 4 = bank 0 write, 5 = bank 0 read, 6 = bank 1 write, 7 = bank 1 read. */
    private var wrRamControl = 0

    // ---- Bank select ($8000) and PRG-RAM protect ($A001) ----

    /**
     * MMC6 adds bit 5 = WRAM enable to the bank-select register. Bits 0-2
     * (register select), bit 6 (PRG mode), and bit 7 (CHR inversion) carry
     * over from MMC3 unchanged.
     */
    override fun handleBankSelectWrite(value: Int) {
        super.handleBankSelectWrite(value)
        wrRamEnabled = (value and 0x20) != 0
    }

    /**
     * MMC6 reassigns the four high bits of `$A001` to per-bank R/W control:
     * bit 4 = bank 0 write, bit 5 = bank 0 read, bit 6 = bank 1 write,
     * bit 7 = bank 1 read. We latch the high nibble for the [cpuRead] /
     * [cpuWrite] gate checks; calling `super` is harmless (the base class
     * would set `prgRamEnabled` from bit 7, but we never consult that
     * field — `cpuRead`/`cpuWrite` route $6000-$6FFF to nowhere).
     */
    override fun handlePrgRamProtectWrite(value: Int) {
        super.handlePrgRamProtectWrite(value)
        wrRamControl = value and 0xF0
    }

    // ---- $7000-$73FF WRAM read/write, $6000-$6FFF open-bus ----

    /**
     * MMC6 PRG-RAM is **not** at `$6000-$7FFF` like MMC3 — it's at
     * `$7000-$73FF` (1KB, two banked halves). Reads below `$7000` are
     * open-bus (real hardware returns the floating CPU data bus; we return
     * 0, consistent with how Mapper 113 / Mapper 64 treat unused regions).
     *
     * PRG banking for `$8000-$FFFF` is identical to MMC3 — `super.cpuRead`
     * handles it.
     */
    override fun cpuRead(address: Int): Byte {
        if (address in 0x7000..0x73FF) {
            if (!wrRamEnabled) return 0
            val bank = (address shr 9) and 0x01       // 0 for $7000-$71FF, 1 for $7200-$73FF
            if ((wrRamControl and BANK_READ_BITS[bank]) == 0) return 0
            return wrRam[bank][address and 0x1FF]
        }
        if (address < 0x7000) return 0                  // $4020-$6FFF open bus (no $6000 PRG-RAM on MMC6)
        return super.cpuRead(address)                   // $8000-$FFFF: standard MMC3 decode
    }

    /**
     * Mirror of [cpuRead] for writes. The 1KB WRAM block at `$7000-$73FF`
     * is writable only when the `$8000` enable bit is set AND the bank-W bit
     * is set. Writes below `$6000` are silently discarded (no expansion
     * hardware on MMC6). Writes at `$6000-$6FFF` are dropped because MMC6
     * has no PRG-RAM there (we never delegate to `super.cpuWrite` for this
     * range, so the base's 8KB PRG-RAM buffer is never written).
     */
    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x7000..0x73FF) {
            if (!wrRamEnabled) return
            val bank = (address shr 9) and 0x01
            if ((wrRamControl and BANK_WRITE_BITS[bank]) == 0) return
            wrRam[bank][address and 0x1FF] = value
            return
        }
        if (address < 0x8000) return                    // $4020-$7FFF: silently discarded (no expansion, no $6000 PRG-RAM)
        super.cpuWrite(address, value)                  // $8000-$FFFF: standard MMC3 banking + IRQ register writes
    }

    private companion object {
        // Per the MMC6 datasheet: each 512-byte WRAM bank has its own
        // R/W control bit in `$A001`. Bank 0 = bit 5 (R) / bit 4 (W).
        // Bank 1 = bit 7 (R) / bit 6 (W). Indexed by bank.
        val BANK_READ_BITS = intArrayOf(0x20, 0x80)
        val BANK_WRITE_BITS = intArrayOf(0x10, 0x40)
    }

    /**
     * The 8KB battery buffer that Mapper4 returns is unused by MMC6 (we
     * own the 1KB WRAM and never read $6000-$6FFF). Returning null stops
     * the save-RAM file from being created for MMC6 games.
     */
    override fun batteryBackedRam(): ByteArray? = null

    // ---- Save state ----
    // The base class saveState/loadState writes the 8KB prgRam buffer and the
    // prgRamEnabled / prgRamWriteProtect flags. Those bytes are unused by
    // MMC6 (we return null from batteryBackedRam and override the read/write
    // paths). To keep save-state forward-compat with future Mapper4 versions
    // we consume them anyway, then append our own MMC6-only fields.

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.write(wrRam[0])
        out.write(wrRam[1])
        out.writeBoolean(wrRamEnabled)
        out.writeInt(wrRamControl)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        input.readFully(wrRam[0])
        input.readFully(wrRam[1])
        wrRamEnabled = input.readBoolean()
        wrRamControl = input.readInt()
    }

    override fun snapshot(): MapperStateSnapshot {
        val base = super.snapshot()
        return base.copy(
            mapperId = 119,
            type = "MMC6",
            registers = base.registers + mapOf(
                "wrRamEnabled" to if (wrRamEnabled) 1 else 0,
                "wrRamControl" to wrRamControl
            ),
            // The 1KB WRAM, packed as 2 × 512-byte slices.
            prgRam = wrRam[0] + wrRam[1]
        )
    }
}