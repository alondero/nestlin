package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 71 (Camerica / Codemasters, BF909x).
 *
 * Closely related to UNROM (mapper 2) but with a different register layout and
 * a software-switchable 1-screen mirroring mode.
 *
 * Register protocol (writes only; reads return open bus 0):
 *   - **Default mode (BF9096-style):** every write in `$8000-$FFFF` selects
 *     the 16 KB PRG bank mapped at `$8000-$BFFF` (the whole byte is the bank
 *     number, modulo PRG-bank count). `$C000-$FFFF` is fixed to the last bank.
 *     Mirroring follows the iNES header.
 *   - **Firehawk mode (BF9097-style):** auto-engaged the first time the game
 *     writes to `$9000`. From then on, writes to `$8000-$BFFF` and `$C000-$FFFF`
 *     have *different* meanings:
 *       - `$C000-$FFFF` selects the 16 KB PRG bank at `$8000-$BFFF` (whole
 *         byte; `$C000-$FFFF` stays fixed to the last bank).
 *       - `$8000-$BFFF` selects 1-screen mirroring: bit 4 = 0 → lower screen
 *         (nametable `$2000`), bit 4 = 1 → upper screen (nametable `$2400`).
 *         All other bits in this write are ignored.
 *
 * CHR: a single fixed 8 KB page at `$0000-$1FFF`. There is no CHR banking on
 * this chip. Some homebrew variants swap in CHR RAM; for now we treat an empty
 * CHR ROM as 8 KB of CHR RAM so single-CHR-RAM dumps render correctly.
 *
 * No IRQ. No PRG-RAM.
 *
 * Games: Micro Machines, Dizzy series, Linus Spacehead, Bee 52, Big Nose
 * the Caveman, Firehawk, and other Camerica / Codemasters unlicensed titles.
 */
class Mapper71(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom

    // CHR bus: backed by ROM when present, otherwise 8KB of CHR-RAM.
    // CHR RAM fallback for dumps that ship 0 KB of CHR (8 KB writable).
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    private val prgBankCount = programRom.size / 0x4000

    // $8000-$BFFF: switchable 16 KB bank. $C000-$FFFF is always `prgBankCount - 1`.
    private var prgBank = 0

    // BF9097 firehawk sub-mode. Latched on the first write to $9000; once set,
    // $8000-$BFFF writes switch mirroring instead of doing bank select.
    private var bf9097Mode = false

    // Live override of mirroring while in firehawk mode (null = fall back to header).
    private var firehawkMirrorUpper: Boolean? = null

    override fun cpuRead(address: Int): Byte {
        return when {
            address < 0x8000 -> 0
            address < 0xC000 -> {
                val offset = address - 0x8000
                programRom[(prgBank * 0x4000 + offset) % programRom.size]
            }
            else -> {
                val offset = address - 0xC000
                val lastBank = (prgBankCount - 1).coerceAtLeast(0)
                programRom[(lastBank * 0x4000 + offset) % programRom.size]
            }
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address < 0x8000) return

        // Latch firehawk mode on any $9000 write. The chip's auto-detect doesn't
        // care about the value; it only cares that the address was hit. (Mesen
        // matches this: writing *anything* to $9000 enables BF9097 behaviour.)
        if (address == 0x9000) {
            bf9097Mode = true
            return
        }

        val v = value.toUnsignedInt()
        if (address >= 0xC000 || !bf9097Mode) {
            // PRG bank select — whole byte, modulo bank count.
            prgBank = v % prgBankCount.coerceAtLeast(1)
        } else {
            // Firehawk mirroring — bit 4: 0 = lower, 1 = upper.
            firehawkMirrorUpper = (v and 0x10) != 0
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        return if (chrRom.isEmpty()) chrMemory.read(a) else chrRom[a]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) chrMemory.write(address and 0x1FFF, value)
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        firehawkMirrorUpper?.let {
            return if (it) Mapper.MirroringMode.ONE_SCREEN_UPPER
                   else Mapper.MirroringMode.ONE_SCREEN_LOWER
        }
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank)
        out.writeBoolean(bf9097Mode)
        out.writeBoolean(firehawkMirrorUpper == true)
        out.writeBoolean(chrRom.isEmpty())
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank = input.readInt()
        bf9097Mode = input.readBoolean()
        firehawkMirrorUpper = if (input.readBoolean()) true else null
        input.readBoolean()    // hasChrRam — chrMemory knows whether it has RAM
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 71,
            type = "Camerica BF909x",
            banks = mapOf("prgBank" to prgBank),
            registers = mapOf(
                "bf9097Mode" to if (bf9097Mode) 1 else 0,
                "firehawkMirrorUpper" to when (firehawkMirrorUpper) {
                    true -> 1
                    false -> 0
                    null -> -1
                }
            ),
            irqState = null,
            // Snapshot chrRam for debug display: extract via the peek seam.
            chrRam = if (chrRom.isEmpty()) ByteArray(0x2000) { i -> chrMemory.peek(i) } else null
        )
    }
}
