package com.github.alondero.nestlin.gamepak

/**
 * Mapper 26 — Konami VRC6b (Esper Dream 2: Aratanaru Tatakai, Mouryou Senki
 * Madara).
 *
 * Address-pin wiring: A1 → sub-register bit 0, A0 → sub-register bit 1 — i.e.
 * the low two address bits are swapped before reaching the chip relative to
 * VRC6a/Mapper 24. To the CPU this appears as registers at "$x000, $x002,
 * $x001, $x003" instead of "$x000, $x001, $x002, $x003".
 *
 * Implementation: project the CPU address back onto the canonical sub-register
 * index 0..3 by swapping bits 0 and 1, so the rest of [Vrc6] sees the same
 * layout as VRC6a.
 */
class Mapper26(gamePak: GamePak) : Vrc6(gamePak) {
    override val mapperId: Int = 26
    override fun decodeSubRegister(address: Int): Int =
        ((address and 0x01) shl 1) or ((address and 0x02) ushr 1)
}
