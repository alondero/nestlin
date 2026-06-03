package com.github.alondero.nestlin.gamepak

/**
 * Mapper 23 — Konami VRC4e / VRC4f.
 *
 * Submapper address-pin layouts:
 *   - VRC4f: A0 → sub-register bit 0, A1 → bit 1 (registers at $x000, $x001, $x002, $x003)
 *   - VRC4e: A2 → sub-register bit 0, A3 → bit 1 (registers at $x000, $x004, $x008, $x00C)
 *
 * As with [Mapper21], we OR the two candidate decodes; VRC4f games only set
 * bits 0-1 of the address and VRC4e games only set bits 2-3, so the OR
 * uniquely identifies the intended register.
 *
 * Games: Wai Wai World, Salamander, Crisis Force, Parodius Da! — and the
 * unlicensed mapper-23 hacks of various Konami titles.
 */
class Mapper23(gamePak: GamePak) : Vrc4(gamePak) {
    override val mapperId: Int = 23
    override fun decodeSubRegister(address: Int): Int {
        val f = address and 0x03                       // VRC4f: A0, A1
        val e = (address shr 2) and 0x03               // VRC4e: A2, A3
        return f or e
    }
}
