package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 153 (Bandai FCG variant).
 *
 * Mapper 153 is the same Bandai FCG silicon as mapper 16, wired at the board
 * level to add an 8KB PRG-RAM window at `$6000-$7FFF`. The `$D` register
 * becomes a small control register for that window:
 *   - bit 7 = 0: `$6000-$7FFF` reads from PRG-RAM, writes go to PRG-RAM.
 *   - bit 7 = 1: `$6000-$7FFF` reads from PRG-ROM (low 4 bits of `$D` select
 *                 a 16KB bank; only the low 8KB of that bank is visible).
 *
 * All other registers are identical to mapper 16:
 *   `$0`..`$7`   CHR bank registers (eight 1KB windows).
 *   `$8`         PRG page select for `$8000-$BFFF` (low 4 bits).
 *   `$9`         Mirroring (bits 0-1).
 *   `$A`         IRQ control — bit 0 enables; any write reloads from the latch.
 *   `$B`         IRQ latch low byte.
 *   `$C`         IRQ latch high byte.
 *   `$D`         $6000-$7FFF bank/PRG-RAM select (this mapper only).
 *
 * PRG layout (16KB granularity):
 *   `$8000-$BFFF` = switchable 16KB bank selected by `$8`.
 *   `$C000-$FFFF` = the last 16KB bank.
 *
 * The standard mapper-153 board is battery-backed (Famicom Jump II, etc.), so
 * when `Header.hasBattery` is set the PRG-RAM is exposed via `batteryBackedRam()`.
 */
class Mapper153(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val prgBankCount = programRom.size / 0x4000  // 16KB units
    private val chrMask = if (chrRom.isEmpty()) 0 else chrRom.size - 1

    // CHR bus: the canonical mapper-153 board (Famicom Jump II) is CHR-RAM. When
    // the iNES header reports 0 KB CHR, expose 8 KB of writable CHR-RAM; otherwise
    // the eight CHR bank registers drive banked CHR-ROM reads (the synthetic /
    // CHR-ROM configuration exercised by the unit tests).
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // 512KB PRG (Famicom Jump II) can't be addressed by the 4-bit $8 register
    // alone. On mapper 153 the eight "CHR bank" registers ($0-$7) are repurposed:
    // their bit 0 is latched as PRG A18, selecting the active 256KB half. The outer
    // bank applies to the switchable AND the fixed windows. Only meaningful on a
    // 512KB board (nesdev "INES Mapper 153").
    private val is512kPrg = prgBankCount > 16
    private var prgOuterBank = 0   // 0 or 1 (bit 0 latched from a $0-$7 write)

    // 14 FCG registers.
    private val regs = IntArray(14)

    // 8KB PRG-RAM at $6000-$7FFF when `$D` bit 7 = 0.
    private val prgRam = ByteArray(0x2000)
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = if (gamePak.header.hasBattery) prgRam else null

    // IRQ: 16-bit counter, decremented per CPU cycle when enabled.
    private var irqCounter = 0
    private var irqEnable = false
    private var irqPending = false

    override fun tickCpuCycle() {
        if (!irqEnable) return
        if (irqCounter == 0) irqPending = true
        irqCounter = (irqCounter - 1) and 0xFFFF
    }

    override fun isIrqPending(): Boolean = irqPending

    /** PRG A18 as a 16KB-bank offset: 0 or 16, only on a 512KB board. */
    private fun prgOuter16(): Int = if (is512kPrg) (prgOuterBank shl 4) else 0

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            // $D bit 7 selects the source.
            return if ((regs[0xD] and 0x80) == 0) {
                prgRam[address - 0x6000]
            } else {
                val bank = prgOuter16() or (regs[0xD] and 0x0F)
                programRom[(bank * 0x4000 + (address - 0x6000)) % programRom.size]
            }
        }
        if (address < 0x8000) return 0
        val bank = when {
            address < 0xC000 -> prgOuter16() or (regs[0x8] and 0x0F)  // switchable
            is512kPrg -> prgOuter16() or 0x0F                          // fixed: last 16KB of the half
            else -> prgBankCount - 1                                   // fixed: whole-ROM last bank
        }
        val offset = address and 0x3FFF
        return programRom[(bank * 0x4000 + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        when (address) {
            in 0x6000..0x7FFF -> {
                // Register write (lower 4 bits select register).
                writeRegister(address and 0x0F, v)
                // If the current $D bit 7 is clear, also write through to PRG-RAM.
                // (Some games do this; games that don't expect PRG-RAM writes
                //  to be ignored will set bit 7 = 1 to mask them off.)
                if ((regs[0xD] and 0x80) == 0) {
                    prgRam[address - 0x6000] = value
                    batteryDirty = true
                }
            }
        }
    }

    private fun writeRegister(reg: Int, v: Int) {
        when (reg) {
            in 0..7 -> {
                regs[reg] = v and 0xFF
                // Registers $0-$7 additionally latch PRG A18 (bit 0) on 512KB boards.
                prgOuterBank = v and 0x01
            }
            0x8 -> regs[0x8] = v and 0x0F
            0x9 -> regs[0x9] = v and 0x03
            0xA -> {
                irqEnable = (v and 0x01) != 0
                if (!irqEnable) irqPending = false
                irqCounter = ((regs[0xC] and 0xFF) shl 8) or (regs[0xB] and 0xFF)
            }
            0xB -> regs[0xB] = v and 0xFF
            0xC -> regs[0xC] = v and 0xFF
            0xD -> regs[0xD] = v and 0xFF
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        // CHR-RAM board (Famicom Jump II): reads come from the 8KB CHR-RAM. The
        // $0-$7 registers are PRG A18 latches here, NOT CHR bank selects.
        if (chrRom.isEmpty()) return chrMemory.read(a)
        val window = a ushr 10
        val bank = regs[window] and 0xFF
        return chrRom[(bank * 0x0400 + (a and 0x03FF)) and chrMask]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // CHR-RAM is writable; CHR-ROM writes are dropped.
        if (chrRom.isEmpty()) chrMemory.write(address and 0x1FFF, value)
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (regs[0x9] and 0x03) {
            0 -> Mapper.MirroringMode.VERTICAL
            1 -> Mapper.MirroringMode.HORIZONTAL
            2 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
            3 -> Mapper.MirroringMode.ONE_SCREEN_UPPER
            else -> when (gamePak.header.mirroring) {
                Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
                Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
            }
        }
    }

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        for (r in regs) out.writeInt(r)
        out.write(prgRam)
        out.writeInt(irqCounter)
        out.writeBoolean(irqEnable)
        out.writeBoolean(irqPending)
        out.writeInt(prgOuterBank)
        // CHR-RAM bytes (0 bytes when CHR-ROM-backed, so CHR-ROM saves are unchanged).
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in regs.indices) regs[i] = input.readInt()
        input.readFully(prgRam)
        irqCounter = input.readInt()
        irqEnable = input.readBoolean()
        irqPending = input.readBoolean()
        prgOuterBank = input.readInt()
        chrMemory.deserialize(input)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 153,
            type = "Bandai FCG-153",
            banks = mapOf(
                "prgBank" to (regs[0x8] and 0x0F),
                "prg6000Bank" to (regs[0xD] and 0x0F),
                "chrBank0" to regs[0],
                "chrBank1" to regs[1],
                "chrBank2" to regs[2],
                "chrBank3" to regs[3],
                "chrBank4" to regs[4],
                "chrBank5" to regs[5],
                "chrBank6" to regs[6],
                "chrBank7" to regs[7]
            ),
            registers = mapOf(
                "mirroring" to (regs[0x9] and 0x03),
                "dReg" to (regs[0xD] and 0xFF)
            ),
            irqState = mapOf(
                "irqCounter" to irqCounter,
                "irqEnable" to if (irqEnable) 1 else 0,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = null,
            prgRam = prgRam.copyOf()
        )
    }
}
