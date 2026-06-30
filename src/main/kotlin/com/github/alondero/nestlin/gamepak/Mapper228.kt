package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 228 (Action 52 / Active Enterprises "Cheetahmen").
 *
 * The infamous 52-game multicart. Three things make this board unlike every
 * other mapper in Nestlin:
 *
 * 1. **The written value is almost ignored.** Only bits 0-1 of `value`
 *    contribute (the low 2 bits of the CHR bank). Bits 2-7 are discarded.
 * 2. **The address *is* the bank-select.** PRG bank, CHR bank high nibble,
 *    PRG mode and mirroring are all decoded from `addr`, not `value`.
 * 3. **Three PRG chips with a hole.** The cart has three 512KB PRG chips
 *    wired as chip 0, 1 and **3** — chip 2 is physically absent. See the
 *    PRG section below for how the dump packs around that hole.
 *
 * **Oracle.** Mesen2's `Core/NES/Mappers/Unlicensed/ActionEnterprises.h` is
 * the canonical reference and it is *startlingly* short — the entire register
 * decode is:
 *
 * ```cpp
 * uint8_t chipSelect = (addr >> 11) & 0x03;
 * if(chipSelect == 3) chipSelect = 2;
 * uint8_t prgPage = ((addr >> 6) & 0x1F) | (chipSelect << 5);
 * if(addr & 0x20) { SelectPrgPage(0, prgPage); SelectPrgPage(1, prgPage); }
 * else            { SelectPrgPage(0, prgPage & 0xFE); SelectPrgPage(1, (prgPage & 0xFE) + 1); }
 * SelectChrPage(0, ((addr & 0x0F) << 2) | (value & 0x03));
 * SetMirroringType(addr & 0x2000 ? Horizontal : Vertical);
 * ```
 *
 * **Register decode (`cpuWrite`, addresses $8000-$FFFF):**
 *
 *  | Source bits          | Field                                            |
 *  |----------------------|--------------------------------------------------|
 *  | `addr` A11-A12       | `chipSelect` (2 bits)                            |
 *  | `addr` A6-A10        | PRG page within chip (5 bits)                    |
 *  | `addr` A5 (0x20)     | PRG mode: 1 = 32KB (both windows = page), 0 = adjacent 16KB pair |
 *  | `addr` A0-A3 (0x0F)  | CHR bank high 4 bits                             |
 *  | `value` D0-D1        | CHR bank low 2 bits                              |
 *  | `addr` A13 (0x2000)  | Mirroring: 1 = Horizontal, 0 = Vertical          |
 *
 * **PRG geometry — three chips, one hole, and why we deviate from Mesen by a hair.**
 *
 * Physically: chip 0 = first 512KB, chip 1 = second 512KB, chip 2 = *not
 * present*, chip 3 = third 512KB. On real hardware a read while chip 2 is
 * selected returns open bus. The .nes dump only stores the three chips that
 * exist, contiguously: chip 0 at file offset $000010, chip 1 at $080010,
 * chip 3 at $100010 — i.e. a flat 96 × 16KB PRG region with the third chip's
 * data sitting in PRG banks 64-95 (where "chip 2" *would* index).
 *
 * Mesen reconciles the dump's packing with the hardware's hole by remapping
 * `chipSelect 3 → 2` **before** computing the page. That makes both `chipSelect
 * == 2` and `chipSelect == 3` resolve to `prgPage 64-95`, so the third chip's
 * data (banks 64-95) is reachable via `chipSelect 3` — the value real games
 * actually write. The cost is that Mesen returns that same third-chip data for
 * a literal `chipSelect == 2`, where hardware would return open bus.
 *
 * Nestlin keeps Mesen's remap **for indexing** (so chip 3 reaches banks 64-95,
 * keeping every game that lives on the third chip playable) but additionally
 * flags a literal `chipSelect == 2` write as open bus, so a chip-2 *read*
 * returns the data bus (hardware-faithful per the nesdev wiki). No real Action
 * 52 game ever selects chip 2, so this is invisible to the Mesen2 state-diff
 * regression (which only exercises the game's real accesses) yet honours both
 * the wiki and the original issue's open-bus requirement. The issue's prose
 * ("chip-3 reads return open bus") was self-contradictory — it would have
 * orphaned the third chip and broken ~17 games; the source resolves it.
 *
 * PRG windows are 16KB: window 0 = $8000-$BFFF (page `prgBank0`), window 1 =
 * $C000-$FFFF (page `prgBank1`).
 *
 * **CHR geometry:** one 8KB window at PPU $0000-$1FFF; 6-bit bank, 0-63.
 *
 * **Initial state.** Mesen's `InitMapper` *and* `Reset` both call
 * `WriteRegister(0x8000, 0)`; without it the menu hangs on a black screen.
 * We replay that in `init` (chip 0, page 0 → `prgBank0 = 0`, `prgBank1 = 1`,
 * `chrBank = 0`, vertical mirroring). Nestlin has no per-mapper reset hook
 * (`Nestlin.powerReset` resets only the CPU and does not reconstruct the
 * mapper), so the soft-reset replay is not modelled — it is unnecessary for
 * cold boot, which is where games depend on the $00 write.
 *
 * **No PRG-RAM, no IRQ, no audio expansion.** Reads below $8000 are open bus.
 * The disputed 16-bit "RAM" at $4020-$4023 noted on the wiki is not emulated
 * (Nestopia omits it too; a real cart has no such RAM).
 */
class Mapper228(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    // CHR bus: backed by ROM when present (always, for Action 52's 512KB CHR),
    // otherwise an 8KB CHR-RAM fallback so a malformed/CHR-less dump can't crash
    // the PPU read path.
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // PRG: two 16KB windows. prgBank0 -> $8000-$BFFF, prgBank1 -> $C000-$FFFF.
    private var prgBank0 = 0
    private var prgBank1 = 0

    // True when the most recent register write selected the physically-absent
    // chip 2 (literal chipSelect == 2). A chip-2 read returns open bus. Set/cleared
    // on every register write; both windows share it because one write always
    // selects one chip for the whole $8000-$FFFF space.
    private var prgOpenBus = false

    // CHR: single 8KB window, 6-bit bank.
    private var chrBank = 0

    // Mirroring latched from addr bit 13 on each register write. The real
    // power-on value is set by init()'s cpuWrite($8000,0) (which decodes to
    // vertical) before any read, so this is just a placeholder until that runs
    // — the iNES header mirroring is irrelevant to this board.
    private var horizontalMirroring = false

    // Open-bus source. Memory sets this just before each cpuRead (see Memory.get);
    // the default Mapper.dataBus getter returns 0, so a real field is required to
    // capture it for chip-2 / sub-$8000 open-bus reads.
    override var dataBus: Byte = 0

    init {
        // Mesen's InitMapper: establish default banking. Games depend on this.
        cpuWrite(0x8000, 0)
    }

    override fun cpuRead(address: Int): Byte {
        // No PRG-RAM and nothing mapped below $8000: open bus.
        if (address < 0x8000) return dataBus
        // Physically-absent chip 2 selected: open bus (no game does this).
        if (prgOpenBus) return dataBus
        // Defensive: a malformed 0-PRG dump (iNES byte 4 = 0) leaves programRom
        // empty and would make the `% programRom.size` below divide by zero.
        // Mirrors ppuRead's `chrRom.isEmpty()` guard — open bus is the safe read.
        if (programRom.isEmpty()) return dataBus
        return if (address and 0x4000 == 0) {
            // $8000-$BFFF
            programRom[(prgBank0 * 0x4000 + (address - 0x8000)) % programRom.size]
        } else {
            // $C000-$FFFF
            programRom[(prgBank1 * 0x4000 + (address - 0xC000)) % programRom.size]
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address < 0x8000) return

        val rawChip = (address ushr 11) and 0x03
        // Literal chip 2 is the absent chip → reads become open bus.
        prgOpenBus = (rawChip == 2)
        // Remap 3 → 2 for INDEXING: the dump packs the third physical chip's data
        // into PRG banks 64-95, the slot chip-index 2 addresses.
        val chipForIndex = if (rawChip == 3) 2 else rawChip
        val prgPage = ((address ushr 6) and 0x1F) or (chipForIndex shl 5)

        if (address and 0x20 != 0) {
            // 32KB mode: the same 16KB page in both windows.
            prgBank0 = prgPage
            prgBank1 = prgPage
        } else {
            // Adjacent-pair mode: even page low, odd page high.
            prgBank0 = prgPage and 0xFE
            prgBank1 = (prgPage and 0xFE) + 1
        }

        // CHR bank: 4 bits from addr A0-A3, low 2 bits from value D0-D1.
        chrBank = ((address and 0x0F) shl 2) or (value.toUnsignedInt() and 0x03)

        horizontalMirroring = (address and 0x2000) != 0
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return chrMemory.read(a)
        // 8KB CHR window. % chrRom.size keeps an out-of-range bank index (e.g. a
        // test driving a bank past the ROM) from indexing off the end; on hardware
        // the upper address bits would be open bus, but wrap is the safer fixture.
        return chrRom[(chrBank * 0x2000 + a) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) chrMemory.write(address and 0x1FFF, value)
    }

    override fun currentMirroring(): Mapper.MirroringMode =
        if (horizontalMirroring) Mapper.MirroringMode.HORIZONTAL
        else Mapper.MirroringMode.VERTICAL

    override val saveStateVersion: Int = 1

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank0)
        out.writeInt(prgBank1)
        out.writeInt(chrBank)
        out.writeBoolean(prgOpenBus)
        out.writeBoolean(horizontalMirroring)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank0 = input.readInt()
        prgBank1 = input.readInt()
        chrBank = input.readInt()
        prgOpenBus = input.readBoolean()
        horizontalMirroring = input.readBoolean()
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot =
        MapperStateSnapshot(
            mapperId = 228,
            type = "Action 52 / Active Enterprises",
            banks = mapOf(
                "prgBank0" to prgBank0,
                "prgBank1" to prgBank1,
                "chrBank" to chrBank
            ),
            registers = mapOf(
                "horizontalMirroring" to if (horizontalMirroring) 1 else 0,
                "prgOpenBus" to if (prgOpenBus) 1 else 0
            ),
            irqState = null,
            chrRam = chrMemory.snapshotBytes()
        )
}
