package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 22 — Konami VRC2a.
 *
 * The VRC2 chip is a stripped-down VRC4 with the same register address map
 * but a smaller feature set: no IRQ counter, no $9002 PRG-swap / WRAM-enable
 * register, no $6000-$7FFF WRAM (only a 1-bit latch), and mirroring reduced
 * to V/H only (bit 1 of $9000 is ignored). The silicon is VRC4-family;
 * Konami just wired the die with fewer pins populated.
 *
 * ## Address-pin decode
 *
 * VRC2a uses the VRC4b / Mapper 25 swap — CPU A1 feeds the chip's
 * sub-register bit 0 and CPU A0 feeds sub-register bit 1, so the four
 * sub-registers land at:
 *
 *   $x000 → sub 0    $x001 → sub 2    $x002 → sub 1    $x003 → sub 3
 *
 * This matches the nesdev wiki (INES Mapper 022) and Mesen2's
 * `Core/NES/Mappers/Konami/VRC2_4.h` oracle.
 *
 * ## Register map
 *
 *   $8000-$8003   PRG bank 0 (5-bit, 8KB window at $8000)
 *   $9000-$9001   Mirroring — bit 0 only (0=Vert, 1=Horiz); bit 1 ignored
 *   $9002-$9003   No-op on VRC2 (VRC4's swap-mode + WRAM-enable register)
 *   $A000-$A003   PRG bank 1 (5-bit, 8KB window at $A000)
 *   $B000-$E003   CHR bank selects — 8 × 1KB windows, low+high nibble
 *                 protocol as on VRC4
 *   $F000-$F003   No-op on VRC2 (VRC4's IRQ register set)
 *
 * $C000-$FFFF is fixed to the last 16KB PRG bank (the second-to-last and
 * last 8KB banks, in order). No swap mode.
 *
 * ## Quirks vs VRC4
 *
 * - **CHR bank shift:** the VRC2 spec stores CHR selects as 9-bit values
 *   but only the high 7 bits are wired to the chip. Mesen2 models this by
 *   right-shifting the page index by 1 in `UpdateState` — see
 *   `VRC2_4.h`: `if (_variant == VRC2a) { page >>= 1; }`. We apply the
 *   same shift in [ppuRead].
 * - **Mirroring limited to V/H:** bit 1 of $9000 is ignored (VRC2 lacks
 *   the 1-screen modes that VRC4 supports).
 * - **No IRQ:** `tickCpuCycle` and `isIrqPending` are no-ops.
 * - **No $6000-$7FFF WRAM:** reads return the 6502 open-bus value
 *   ([dataBus]); the VRC2 silicon has only a 1-bit latch at $6000-$6FFF
 *   that no commercial cartridge actually wires up.
 *
 * ## Games
 *
 * TwinBee 3 - Poko Poko Daimaou (Japan) is the only VRC2a title in the
 * local NO-INTRO library; other known VRC2a games (Takeshi no
 * Chōsenjō, Wai Wai World 2, etc.) would also boot once this lands.
 * Note that Akumajō Special: Boku Dracula-kun is Mapper 23 / VRC4, not
 * Mapper 22 / VRC2a, despite the GitHub issue text suggesting otherwise.
 */
class Mapper22(gamePak: GamePak) : Vrc4(gamePak) {
    override val mapperId: Int = 22

    /**
     * Override [dataBus] capture so [cpuRead] at $6000-$7FFF can return the
     * actual 6502 open-bus value (the VRC2 silicon has only a 1-bit latch
     * here that no commercial cartridge wires up). The base [Mapper] default
     * is a no-op setter; without this override, `mapper.dataBus = 0x48` in
     * tests would be discarded and `cpuRead(0x6000)` would always read 0.
     */
    override var dataBus: Byte = 0

    /**
     * VRC2a address-pin decode: CPU A1 → sub-bit 0, CPU A0 → sub-bit 1
     * (the VRC4b / Mapper 25 swap). $x000 → sub 0, $x001 → sub 2,
     * $x002 → sub 1, $x003 → sub 3.
     */
    override fun decodeSubRegister(address: Int): Int {
        return ((address shr 1) and 0x01) or ((address and 0x01) shl 1)
    }

    /**
     * VRC2 has no $6000-$7FFF WRAM. Reads return the 6502 open-bus
     * value ([dataBus]), which matches real hardware (the chip has only
     * a 1-bit latch here that no commercial cartridge wires up; writes
     * are accepted and discarded).
     */
    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) return dataBus
        return super.cpuRead(address)
    }

    /**
     * VRC2a mirroring supports only vertical / horizontal. The base
     * VRC4 `mirroringMode` accepts the full 4-mode encoding (0..3),
     * but the VRC2 silicon ties the 1-screen-mode bit (bit 1) to a
     * no-op. Masking bit 1 collapses values 2 and 3 (1-screen-lower /
     * upper) onto vertical, matching what the chip would have
     * selected. -1 (no $9000 write yet) still falls through to the
     * header default — must check for -1 before masking, since -1
     * AND 0x01 = 1 (all bits set), which would incorrectly return
     * Horizontal even when the header says Vertical.
     */
    override fun currentMirroring(): Mapper.MirroringMode {
        return if (mirroringMode == -1) {
            when (gamePak.header.mirroring) {
                Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
                Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
            }
        } else when (mirroringMode and 0x01) {
            0 -> Mapper.MirroringMode.VERTICAL
            else -> Mapper.MirroringMode.HORIZONTAL
        }
    }

    /**
     * VRC2 has no IRQ counter. The base VRC4 ticks its 8-bit increment-
     * to-wrap counter here every CPU cycle (or every 113⅔ cycles in
     * scanline mode) and asserts the IRQ line when it wraps; VRC2 just
     * ignores the counter entirely, so we no-op the hook.
     */
    override fun tickCpuCycle() {}

    /** VRC2 has no IRQ — the line is never asserted. */
    override fun isIrqPending(): Boolean = false

    /**
     * VRC2 has no $6000-$7FFF WRAM (only a 1-bit latch at $6000-$6FFF that
     * no commercial cartridge wires up), so there is nothing to battery-back.
     * The base VRC4 always exposes an 8KB WRAM via [batteryBackedRam]
     * (gated on the wramEnabled bit); for VRC2a we override to null so the
     * Save RAM persistence layer doesn't allocate an unused `.sav` file.
     */
    override fun batteryBackedRam(): ByteArray? = null

    /**
     * VRC2a CHR shift quirk: the chip only wires 7 of the 9 CHR-select
     * bits to the CHR ROM address lines, so the effective page index is
     * the stored 9-bit value right-shifted by 1 (Mesen2's
     * `VRC2_4.h::UpdateState`). The base [Vrc4.ppuRead] returns the
     * unshifted page; we apply the shift here.
     *
     * The CHR-RAM fallback for 0-CHR-ROM dumps is inherited unchanged.
     */
    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return chrMemory.read(a)
        val window = a shr 10
        val page = chrBanks[window] shr 1
        return chrRom[(page * 0x0400 + (a and 0x03FF)) % chrRom.size]
    }

    /**
     * VRC2 ignores the high two register addresses a VRC4 game would
     * use: $9002-$9003 (PRG-swap mode + WRAM enable) and $F000-$F003
     * (IRQ latch / control / ack). Writes to $6000-$7FFF are silently
     * dropped as well. Everything else — $8000 / $A000 PRG selects,
     * $9000-$9001 mirroring, $B000-$E003 CHR selects — delegates to
     * the base class.
     */
    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        if (address in 0x6000..0x7FFF) return
        if (address < 0x8000) return

        val sub = decodeSubRegister(address)
        when (address and 0xF000) {
            0x8000 -> prg0 = v and 0x1F     // 5-bit PRG bank
            0x9000 -> when (sub) {
                0, 1 -> mirroringMode = v and 0x03  // V/H mirror; bit 1 ignored in currentMirroring
                2, 3 -> {}  // No swap mode / no WRAM enable on VRC2 — ignore.
            }
            0xA000 -> prg1 = v and 0x1F
            in 0xB000..0xE000 -> writeChr(address and 0xF000, sub, v)
            0xF000 -> {}  // No IRQ counter on VRC2 — ignore $Fxxx writes.
        }
    }
}