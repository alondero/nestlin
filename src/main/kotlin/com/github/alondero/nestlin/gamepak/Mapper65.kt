package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 65 (Irem H3001).
 *
 * Used by ~8-10 Irem titles including *R-Type*, *The Adventures of Rad Gravity*,
 * *Kickle Cubicle*, *Infiltrator*, and the Metal Storm / Spartan X / Spelunker
 * (Famicom) trio. The chip has a 16-bit down-counter for raster-timed IRQs,
 * software-controlled mirroring, 3 switchable 8 KB PRG windows, and 8
 * independently-selected 1 KB CHR pages.
 *
 * **Why this implementation does NOT match the issue spec verbatim.** The
 * GitHub issue (#133) describes the chip as "header-driven mirroring,
 * 4×8 KB PRG slots, no IRQ". The Mesen2 reference (`IremH3001.h`,
 * `InitMapper`/`WriteRegister`/`ProcessCpuClock`) disagrees on all three:
 *  - Mirroring is software-controlled at `$9001` (bit 7: 1=H, 0=V), with
 *    the iNES header value driving the *initial* state until the game
 *    writes `$9001`. (The same pattern Mapper 33 uses for its bit-6 latch.)
 *  - Only **3** PRG registers exist (`$8000`, `$A000`, `$C000`); the
 *    `$E000-$FFFF` window is fixed to the last bank. Power-on is
 *    pages 0/1/2/3 = banks 0/1/0xFE/-1 (per `InitMapper`).
 *  - The chip has a **16-bit down-counter** at `$9005:$9006`, enable bit at
 *    `$9003` (bit 7), reload+acknowledge at `$9004`. The counter
 *    decrements once per CPU cycle when enabled, asserts IRQ on zero, and
 *    then auto-disables (one-shot, no wrap). Wired via [tickCpuCycle].
 *
 * Per the new-mapper skill, Mesen2 wins when the issue disagrees — the
 * issue is design *intent*, not silicon. The Mesen2 source is the same
 * oracle our state-diff tests compare against, so implementing to it is
 * the only way to keep the Mesen2 byte-compare suite honest.
 *
 * **Register decode (mask 0xF000):** every write collapses to its 4 KB
 * page base, so `$8042` and `$8FFF` both hit the `$8000` register. Within
 * a page, the low bits select sub-registers:
 *  - `$9000` page: `$9001` (mirroring), `$9003` (IRQ enable), `$9004`
 *    (IRQ reload + ack), `$9005` (IRQ reload high), `$9006` (IRQ reload
 *    low). Other addresses in the page are silently ignored.
 *  - `$B000` page: `$B000`-`$B007` (low 3 bits) each select one of the
 *    8 1 KB CHR pages. Other addresses in the page are silently ignored.
 *
 * **PRG geometry (8 KB granularity, 4 pages):**
 *  - `$8000-$9FFF`: page 0, switchable via `$8000` (whole byte).
 *  - `$A000-$BFFF`: page 1, switchable via `$A000` (whole byte).
 *  - `$C000-$DFFF`: page 2, switchable via `$C000` (whole byte).
 *  - `$E000-$FFFF`: page 3, **fixed to the last bank** (no register).
 *
 * **CHR geometry (1 KB granularity, 8 pages):**
 *  - `$0000-$03FF`: page 0, via `$B000`.
 *  - `$0400-$07FF`: page 1, via `$B001`.
 *  - …
 *  - `$1C00-$1FFF`: page 7, via `$B007`.
 *
 * **Mirroring:** `$9001` bit 7 — 1 = Horizontal, 0 = Vertical. Initial
 * value is the iNES header's mirroring bit (so a game that never writes
 * `$9001` still renders correctly).
 *
 * **IRQ:** 16-bit reload value `$9005:$9006`. `$9003` bit 7 = counter
 * enable (also always clears any pending IRQ). `$9004` = reload counter
 * from the reload value (also always clears any pending IRQ). Counter
 * decrements once per CPU (M2) cycle when enabled. On hitting zero, IRQ
 * is asserted and the counter auto-disables — the chip is one-shot
 * (no wrap). Software must reload via `$9004` and re-enable via `$9003`
 * to re-arm.
 *
 * **No PRG-RAM, no expansion audio.**
 */
class Mapper65(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    // CHR bus: backed by ROM when present, otherwise 8KB of CHR-RAM.
    // The Irem H3001 games all ship CHR ROM, but the fallback costs
    // nothing and keeps homebrew or truncated dumps from crashing the
    // PPU read path.
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // 8 KB PRG bank count. `coerceAtLeast(1)` so a malformed 0-PRG ROM
    // can't make the fixed-bank read at $E000 compute a negative index.
    private val prgBankCount = (programRom.size / 0x2000).coerceAtLeast(1)

    // Power-on PRG page banks. Per Mesen2's `InitMapper`:
    //   page 0 ( $8000) = 0
    //   page 1 ( $A000) = 1
    //   page 2 ( $C000) = 0xFE   // mod prgBankCount at read time
    //   page 3 ( $E000) = -1     // fixed to last bank, not in this array
    // The 0xFE start value mirrors the chip's power-on behaviour; with a
    // realistic 256 KB (32-bank) ROM it selects bank 0xFE directly, and
    // for smaller ROMs it wraps via the modulo in cpuRead.
    private val prgBanks = intArrayOf(0, 1, 0xFE)

    // CHR: eight 1 KB pages, individually addressed at $B000-$B007. The
    // written byte is the 1 KB bank index (modulo CHR-bank count at read
    // time, so oversized writes just wrap).
    private val chrBanks = IntArray(8)

    // Mirroring lives in bit 7 of the most recent $9001 write. The
    // iNES header wires the initial value so a game that never writes
    // $9001 still renders correctly.
    private var horizontalMirroring: Boolean =
        gamePak.header.mirroring == Header.Mirroring.HORIZONTAL

    // IRQ state. Per Mesen2 `IremH3001.h`:
    //   - `_irqEnabled` is a single bit, set by $9003 bit 7, cleared on
    //     the decrement-to-zero fire (one-shot).
    //   - `_irqCounter` is 16 bits; decrements every CPU cycle when
    //     enabled; stops at 0 (no wrap).
    //   - `_irqReloadValue` is 16 bits; written via $9005 (high) and
    //     $9006 (low); copied into `_irqCounter` on $9004 write.
    //   - Writing either $9003 or $9004 also clears a pending IRQ.
    private var irqEnabled = false
    private var irqCounter = 0
    private var irqReloadValue = 0
    private var irqPending = false

    override fun tickCpuCycle() {
        // Per Mesen2 `ProcessCpuClock`: decrement when enabled, fire on
        // zero, then auto-disable (one-shot — the chip does NOT wrap).
        if (!irqEnabled) return
        irqCounter--
        if (irqCounter == 0) {
            irqEnabled = false
            irqPending = true
        }
    }

    override fun isIrqPending(): Boolean = irqPending

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0
        // Four 8 KB pages; bits 13-14 of the address pick which window.
        return when (address and 0xE000) {
            0x8000 -> programRom[(prgBanks[0] * 0x2000 + (address - 0x8000)) % programRom.size]
            0xA000 -> programRom[(prgBanks[1] * 0x2000 + (address - 0xA000)) % programRom.size]
            0xC000 -> programRom[(prgBanks[2] * 0x2000 + (address - 0xC000)) % programRom.size]
            else -> {
                // 0xE000: last 8 KB bank, fixed by the chip (no register).
                val lastBank = (prgBankCount - 1).coerceAtLeast(0)
                programRom[(lastBank * 0x2000 + (address - 0xE000)) % programRom.size]
            }
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        // Address-decode mask is 0xF000 (per Mesen2 `IremH3001_WriteMask`):
        // the high 4 bits pin the page; low 12 bits are either sub-register
        // select (within $9000 / $B000) or fully don't-care ($8000 / $A000 /
        // $C000 are single-register pages).
        val v = value.toUnsignedInt()
        when (address and 0xF000) {
            0x8000 -> prgBanks[0] = v
            0x9000 -> when (address and 0x00FF) {
                // $9000 (the wiki's "PRG bank layout" register) and $9002
                // are not handled by Mesen2's `WriteRegister` — silently
                // ignored.
                0x01 -> horizontalMirroring = (v and 0x80) != 0
                0x03 -> {
                    irqEnabled = (v and 0x80) != 0
                    irqPending = false   // any $9003 write also acks
                }
                0x04 -> {
                    irqCounter = irqReloadValue
                    irqPending = false   // any $9004 write also acks
                }
                0x05 -> irqReloadValue = (irqReloadValue and 0x00FF) or (v shl 8)
                0x06 -> irqReloadValue = (irqReloadValue and 0xFF00) or v
            }
            0xA000 -> prgBanks[1] = v
            0xB000 -> {
                // 8 1 KB CHR pages. Low 3 bits select the register; bits
                // 3-11 alias (so e.g. $B105 and $BA05 both hit bank 5).
                chrBanks[address and 0x07] = v
            }
            0xC000 -> prgBanks[2] = v
            // $D000, $E000, $F000 pages have no registers.
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return chrMemory.read(a)
        // Eight 1 KB CHR windows. Integer division picks the bank, modulo
        // gives the offset inside the bank. `% chrRom.size` keeps an
        // out-of-range bank (game writes a bank number bigger than the ROM)
        // from indexing past the array; on hardware the upper address bits
        // would be open bus, but mirroring is a safer behaviour for a
        // test fixture.
        val bankIndex = chrBanks[a ushr 10]
        val offsetInBank = a and 0x03FF
        return chrRom[(bankIndex * 0x0400 + offsetInBank) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) chrMemory.write(address and 0x1FFF, value)
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return if (horizontalMirroring) Mapper.MirroringMode.HORIZONTAL
        else Mapper.MirroringMode.VERTICAL
    }

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        for (b in prgBanks) out.writeInt(b)
        for (b in chrBanks) out.writeInt(b)
        out.writeBoolean(horizontalMirroring)
        out.writeBoolean(irqEnabled)
        out.writeInt(irqCounter)
        out.writeInt(irqReloadValue)
        out.writeBoolean(irqPending)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in prgBanks.indices) prgBanks[i] = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        horizontalMirroring = input.readBoolean()
        irqEnabled = input.readBoolean()
        irqCounter = input.readInt()
        irqReloadValue = input.readInt()
        irqPending = input.readBoolean()
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 65,
            type = "Irem H3001",
            banks = mapOf(
                "prgBank0" to prgBanks[0],
                "prgBank1" to prgBanks[1],
                "prgBank2" to prgBanks[2],
                "chrBank0" to chrBanks[0],
                "chrBank1" to chrBanks[1],
                "chrBank2" to chrBanks[2],
                "chrBank3" to chrBanks[3],
                "chrBank4" to chrBanks[4],
                "chrBank5" to chrBanks[5],
                "chrBank6" to chrBanks[6],
                "chrBank7" to chrBanks[7]
            ),
            registers = mapOf(
                "horizontalMirroring" to if (horizontalMirroring) 1 else 0
            ),
            irqState = mapOf(
                "irqEnabled" to if (irqEnabled) 1 else 0,
                "irqCounter" to irqCounter,
                "irqReloadValue" to irqReloadValue,
                "irqPending" to if (irqPending) 1 else 0
            ),
            // Snapshot chrRam for debug display: extract via the peek seam.
            chrRam = chrMemory.snapshotBytes()
        )
    }
}
