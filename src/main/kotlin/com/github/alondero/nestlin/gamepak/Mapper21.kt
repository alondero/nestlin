package com.github.alondero.nestlin.gamepak

/**
 * Mapper 21 — Konami VRC4a / VRC4c.
 *
 * Submapper address-pin layouts:
 *   - VRC4a: A1 → sub-register bit 0, A2 → bit 1 (registers at $x000, $x002, $x004, $x006)
 *   - VRC4c: A6 → sub-register bit 0, A7 → bit 1 (registers at $x000, $x040, $x080, $x0C0)
 *
 * We OR both decodings so the mapper accepts either submapper's address layout.
 * VRC4a games only set bits 1-2 of the address (bits 6-7 are zero) and VRC4c
 * games only set bits 6-7 (bits 1-2 are zero), so the OR never confuses one
 * for the other in practice.
 *
 * Games: Wai Wai World 2: SOS!! Parsley Jō, Ganbare Goemon Gaiden 2.
 */
class Mapper21(gamePak: GamePak) : Vrc4(gamePak) {
    override val mapperId: Int = 21
    override fun decodeSubRegister(address: Int): Int {
        val a = (address shr 1) and 0x03   // VRC4a: A1 → bit 0, A2 → bit 1
        val c = (address shr 6) and 0x03   // VRC4c: A6 → bit 0, A7 → bit 1
        return a or c
    }
}
