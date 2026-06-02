package com.github.alondero.nestlin.gamepak

/**
 * Mapper 25 — Konami VRC4b / VRC4d.
 *
 * Submapper address-pin layouts (note the SWAP — A0/A1 cross over):
 *   - VRC4b: A1 → sub-register bit 0, A0 → bit 1 (registers at $x000, $x002, $x001, $x003)
 *   - VRC4d: A3 → sub-register bit 0, A2 → bit 1 (registers at $x000, $x008, $x004, $x00C)
 *
 * As with the other VRC4 variants we OR both candidate decodes. VRC4b games
 * only touch bits 0-1 of the address and VRC4d games only touch bits 2-3, so
 * the OR is unambiguous in practice — and is what FCEUX and Nintaco use.
 *
 * The bit-swap (low pin → high index, high pin → low index) is the hallmark
 * of mapper 25 versus mapper 23; misdiagnose this and you'll silently mirror
 * the IRQ latch low/high writes the wrong way around.
 *
 * Games: Gradius II, Bio Miracle Bokutte Upa, Teenage Mutant Ninja Turtles
 * (Famicom), Ganbare Goemon Gaiden, Moero!! Pro Tennis.
 */
class Mapper25(gamePak: GamePak) : Vrc4(gamePak) {
    override val mapperId: Int = 25
    override fun decodeSubRegister(address: Int): Int {
        // VRC4b: A1 → bit 0, A0 → bit 1 (swapped).
        val b = ((address shr 1) and 0x01) or ((address and 0x01) shl 1)
        // VRC4d: A3 → bit 0, A2 → bit 1 (swapped).
        val d = ((address shr 3) and 0x01) or ((address shr 1) and 0x02)
        return b or d
    }
}
