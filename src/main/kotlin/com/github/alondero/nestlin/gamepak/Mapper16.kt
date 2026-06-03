package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 16 (Bandai FCG / FCG-1 / FCG-2 / LZ93D50).
 *
 * The Bandai FCG is an MMC3-adjacent design: a 16-bit CPU-cycle-clocked scanline
 * counter, a flat 14-register programming model, and 1KB CHR granularity. It's
 * the chip behind most Bandai-published cartridges (Dragon Ball Z series, Saint
 * Seiya, Famicom Jump II, SD Gundam Gaiden).
 *
 * Register map — selected by the lower 4 bits of a write to `$6000-$7FFF`:
 *   `$0`..`$7`   CHR bank registers (eight 1KB windows at `$0000-$1FFF`).
 *   `$8`         PRG page select (low 4 bits select one of sixteen 16KB banks).
 *   `$9`         Mirroring (bits 0-1: 0=Vertical, 1=Horizontal,
 *                             2=1-screen lower, 3=1-screen upper).
 *   `$A`         IRQ control — bit 0 enables the IRQ; any write reloads the
 *                counter from the `$B:$C` latch.
 *   `$B`         IRQ reload latch — low byte.
 *   `$C`         IRQ reload latch — high byte.
 *   `$D`         EEPROM I²C control (submapper 5 / LZ93D50 only). Bits 5/6
 *                carry SCL / SDA of a 24C02 device.
 *
 * Submapper variants differ in *where* the register writes are decoded:
 *   - Submapper 0 (default): registers at `$6000-$7FFF`. PRG-ROM only at
 *     `$8000-$FFFF`; no PRG-RAM. This is what the iNES byte-6 mapper-id
 *     `0x10` decodes to when no submapper is supplied.
 *   - Submapper 4 (FCG-1/2): registers at `$8000-$FFFF`; `$6000-$7FFF` is
 *     normal PRG. IRQ writes `$B/$C` are *direct* (no latch) on this variant.
 *   - Submapper 5 (LZ93D50): registers at `$8000-$FFFF`; `$6000-$7FFF` is
 *     unused. `$D` is the EEPROM port.
 *
 * This implementation targets the default submapper 0 (register writes at
 * `$6000-$7FFF`). The submapper-4/submapper-5 decode is provided as a small
 * variant that the constructor selects based on `header.submapper`; the
 * EEPROM only initialises when submapper == 5.
 *
 * PRG layout (16KB granularity):
 *   `$8000-$BFFF` = switchable 16KB bank selected by `$8` (low 4 bits).
 *   `$C000-$FFFF` = the last 16KB bank (always fixed to `prgBankCount - 1`).
 *
 * CHR layout: eight 1KB windows, each with its own 1KB page (the 1KB stride
 * in `ppuRead` selects which register to use via `address ushr 10`).
 *
 * IRQ: a 16-bit counter that decrements once per CPU (M2) cycle when bit 0 of
 * `$A` is set. When the counter wraps from `$0000` to `$FFFF` an IRQ is
 * asserted. The counter reloads from the `$B:$C` latch on every write to
 * `$A`. Writing `$A` with bit 0 = 0 disables the IRQ and acknowledges any
 * pending request.
 */
class Mapper16(private val gamePak: GamePak, private val submapper: Int = 0) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val prgBankCount = programRom.size / 0x4000  // 16KB units
    private val chrMask = if (chrRom.isEmpty()) 0 else chrRom.size - 1

    // 14 FCG registers. Index = (write address) & 0x0F.
    private val regs = IntArray(14)

    // IRQ: 16-bit counter, decremented per CPU cycle when enabled.
    private var irqCounter = 0
    private var irqEnable = false
    private var irqPending = false

    // EEPROM appears only on the LZ93D50 submapper (5).
    private val eeprom: BandaiEeprom? = if (submapper == 5) BandaiEeprom() else null

    // Submapper-4 writes `$B/$C` directly to the counter (no latch).
    // Submapper-0 and submapper-5 use the latch-then-reload-on-$A model.
    private val directIrqWrites = (submapper == 4)

    override fun tickCpuCycle() {
        if (!irqEnable) return
        if (irqCounter == 0) irqPending = true
        irqCounter = (irqCounter - 1) and 0xFFFF
    }

    override fun isIrqPending(): Boolean = irqPending

    override fun cpuRead(address: Int): Byte {
        // LZ93D50 (submapper 5): register `$D` reads back the EEPROM's SCL/SDA
        // state. Submapper 0 has registers at $6000-$7FFF (so the read address
        // is $600D); submapper 5 has registers at $8000-$FFFF (so $800D).
        if (eeprom != null) {
            val eepromReg = if (submapper == 5) 0x800D else 0x600D
            if (address == eepromReg) return eeprom.read().toByte()
        }
        if (address < 0x8000) return 0
        val bank = if (address < 0xC000) (regs[0x8] and 0x0F) else (prgBankCount - 1)
        val offset = address and 0x3FFF
        return programRom[(bank * 0x4000 + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        // Register port location depends on submapper.
        val inRegWindow = if (directIrqWrites || submapper == 5) {
            address in 0x8000..0xFFFF
        } else {
            address in 0x6000..0x7FFF
        }
        if (inRegWindow) {
            writeRegister(address and 0x0F, v)
        }
        // Writes outside the register window are ignored.
    }

    private fun writeRegister(reg: Int, v: Int) {
        when (reg) {
            in 0..7 -> regs[reg] = v and 0xFF
            0x8 -> regs[0x8] = v and 0x0F
            0x9 -> regs[0x9] = v and 0x03
            0xA -> {
                irqEnable = (v and 0x01) != 0
                if (!irqEnable) irqPending = false
                // Any write to $A reloads the counter from the latch.
                irqCounter = ((regs[0xC] and 0xFF) shl 8) or (regs[0xB] and 0xFF)
            }
            0xB -> {
                regs[0xB] = v and 0xFF
                if (directIrqWrites) {
                    irqCounter = ((regs[0xC] and 0xFF) shl 8) or (regs[0xB] and 0xFF)
                }
            }
            0xC -> {
                regs[0xC] = v and 0xFF
                if (directIrqWrites) {
                    irqCounter = ((regs[0xC] and 0xFF) shl 8) or (regs[0xB] and 0xFF)
                }
            }
            0xD -> eeprom?.write(v)
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return 0
        val window = a ushr 10  // 1KB windows: bits 10-12 select window 0-7
        val bank = regs[window] and 0xFF
        return chrRom[(bank * 0x0400 + (a and 0x03FF)) and chrMask]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        // No CHR-RAM on standard mapper 16 boards. Some homebrew variants
        // (mapper 16 + CHR-RAM) could be supported here later.
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
        out.writeInt(irqCounter)
        out.writeBoolean(irqEnable)
        out.writeBoolean(irqPending)
        out.writeInt(submapper)
        if (eeprom != null) {
            out.writeBoolean(true)
            eeprom.saveState(out)
        } else {
            out.writeBoolean(false)
        }
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in regs.indices) regs[i] = input.readInt()
        irqCounter = input.readInt()
        irqEnable = input.readBoolean()
        irqPending = input.readBoolean()
        val savedSubmapper = input.readInt()
        val hasEeprom = input.readBoolean()
        if (hasEeprom && eeprom != null) {
            eeprom.loadState(input)
        }
        // savedSubmapper is informational; we keep the constructor-provided value.
        require(savedSubmapper == submapper) {
            "Mapper16 save state submapper mismatch: saved=$savedSubmapper, current=$submapper"
        }
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 16,
            type = "Bandai FCG (sub=$submapper)",
            banks = mapOf(
                "prgBank" to (regs[0x8] and 0x0F),
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
                "mirroring" to (regs[0x9] and 0x03)
            ),
            irqState = mapOf(
                "irqCounter" to irqCounter,
                "irqEnable" to if (irqEnable) 1 else 0,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = null
        )
    }
}
