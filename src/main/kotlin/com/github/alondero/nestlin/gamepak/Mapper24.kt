package com.github.alondero.nestlin.gamepak

/**
 * Mapper 24 — Konami VRC6a (Castlevania III: Akumajou Densetsu JP, Madara,
 * Esper Dream 2 — wait, Esper Dream 2 is actually VRC6b/Mapper 26. Akumajou
 * Densetsu is the canonical VRC6a title).
 *
 * Address-pin wiring: A0 → sub-register bit 0, A1 → sub-register bit 1. The
 * canonical register layout from NESdev wiki ("$x000, $x001, $x002, $x003")
 * therefore presents identically to the chip.
 */
class Mapper24(gamePak: GamePak) : Vrc6(gamePak) {
    override val mapperId: Int = 24
    override fun decodeSubRegister(address: Int): Int = address and 0x03
}
