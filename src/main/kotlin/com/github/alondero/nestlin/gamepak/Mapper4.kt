package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 4 (MMC3/TxROM) - Bank switching with scanline IRQ.
 *
 * MMC3 is one of the most sophisticated discrete-logic mappers, featuring:
 * - Dual register pairs (select at even address, data at odd address)
 * - PRG banking with configurable modes ($8000-$9FFF and $A000-$BFFF switchable)
 * - CHR banking with 2KB/1KB granularity
 * - Scanline IRQ via PPU A12 edge detection
 *
 * Games: Mega Man 4-6, Contra, Kirby's Adventure, StarTropics, Crystalis, etc.
 */
class Mapper4(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // PRG bank count (8KB units)
    private val prgBankCount = programRom.size / 0x2000

    // 8KB PRG banks
    private var prgBank6 = 0  // $8000-$9FFF
    private var prgBankA = 1  // $A000-$BFFF (R7, always switchable)
    // $C000-$DFFF uses prgBankCount-2 when prgMode=0, prgBank6 when prgMode=1
    // $E000-$FFFF always last bank (fixed)

    // 2KB CHR banks (R0, R1) and 1KB CHR banks (R2-R5)
    private var chrBanks = IntArray(6) { 0 }

    // PRG RAM ($6000-$7FFF)
    private val prgRam = ByteArray(0x2000)
    private var prgRamEnabled = true
    private var prgRamWriteProtect = false

    // Bank select register ($8000)
    private var bankSelect = 0
    // CHR/PRG inversion mode (bit 6 of $8000)
    private var chrPrgInvert = false
    // PRG banking mode (bit 7 of $8000)
    private var prgMode = false

    // IRQ counter (MMC3 scanline IRQ)
    private val scanlineCounter = ScanlineCounter()

    // Mirroring override from $A000 register (bit 0: 0=vertical, 1=horizontal)
    private var mirroringOverride: Mapper.MirroringMode? = null

    override fun notifyA12Edge(rising: Boolean) {
        if (!rising) return
        scanlineCounter.clock()
    }

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            return if (prgRamEnabled) prgRam[address - 0x6000] else 0
        }
        if (address < 0x8000) return 0

        return when (address and 0xE000) {
            0x8000 -> {
                // $8000-$9FFF: second-to-last (fixed) in mode 1, R6 (prgBank6, switchable) in mode 0
                val bank = if (prgMode) (prgBankCount - 2) else prgBank6
                programRom[(bank * 0x2000 + (address - 0x8000)) % programRom.size]
            }
            0xA000 -> {
                // $A000-$BFFF: always R7 (prgBankA)
                programRom[(prgBankA * 0x2000 + (address - 0xA000)) % programRom.size]
            }
            0xC000 -> {
                // $C000-$DFFF: R8 (second-to-last) or R6 depending on prgMode
                val bank = if (prgMode) prgBank6 else (prgBankCount - 2)
                programRom[(bank * 0x2000 + (address - 0xC000)) % programRom.size]
            }
            0xE000 -> {
                // $E000-$FFFF: always last bank (fixed)
                programRom[((prgBankCount - 1) * 0x2000 + (address - 0xE000)) % programRom.size]
            }
            else -> 0
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x6000..0x7FFF) {
            if (prgRamEnabled && !prgRamWriteProtect) {
                prgRam[address - 0x6000] = value
            }
            return
        }
        if (address < 0x8000) return

        val addrLow = address and 0xE000
        val valueInt = value.toUnsignedInt()

        when (addrLow) {
            0x8000 -> {
                // Bank select (even address) or Bank data (odd address)
                if ((address and 0x01) == 0) {
                    // Even address: Bank select register
                    bankSelect = valueInt and 0x07
                    // Bit 7 = CHR A12 inversion, bit 6 = PRG bank mode (MMC3 spec)
                    prgMode = (valueInt and 0x40) != 0
                    chrPrgInvert = (valueInt and 0x80) != 0
                } else {
                    // Odd address: Bank data
                    setBank(bankSelect, valueInt)
                }
            }
            0xA000 -> {
                if ((address and 0x01) == 0) {
                    // Even address: Mirroring control
                    // Bit 0: 0=vertical, 1=horizontal
                    mirroringOverride = if ((valueInt and 0x01) != 0) {
                        Mapper.MirroringMode.HORIZONTAL
                    } else {
                        Mapper.MirroringMode.VERTICAL
                    }
                } else {
                    // Odd address: PRG RAM protect ($A001)
                    // Bit 7: PRG RAM chip enable (0: disable, 1: enable)
                    // Bit 6: PRG RAM write protect (0: allow, 1: deny)
                    prgRamEnabled = (valueInt and 0x80) != 0
                    prgRamWriteProtect = (valueInt and 0x40) != 0
                }
            }
            0xC000 -> {
                // IRQ latch (even) or IRQ reload (odd)
                if ((address and 0x01) == 0) {
                    scanlineCounter.writeLatch(valueInt)
                } else {
                    // Reload flag is set; actual counter reload happens at next A12 rising edge
                    scanlineCounter.triggerReload()
                }
            }
            0xE000 -> {
                // IRQ disable (even) or enable (odd)
                if ((address and 0x01) == 0) {
                    scanlineCounter.setEnabled(false)
                } else {
                    scanlineCounter.setEnabled(true)
                }
            }
        }
    }

    private fun setBank(bankIndex: Int, value: Int) {
        when {
            chrPrgInvert -> {
                // Inverted mode: R0-R5 are six 1KB banks, R6-R7 unused for CHR
                when (bankIndex) {
                    in 0..5 -> chrBanks[bankIndex] = value
                    6 -> prgBank6 = value and 0x3F
                    7 -> prgBankA = value and 0x3F
                }
            }
            else -> {
                // Normal mode: R0-R1 are 2KB, R2-R5 are 1KB, R6-R7 are PRG
                when (bankIndex) {
                    0, 1 -> chrBanks[bankIndex] = value
                    in 2..5 -> chrBanks[bankIndex] = value
                    6 -> prgBank6 = value and 0x3F
                    7 -> prgBankA = value and 0x3F
                }
            }
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF

        if (chrRom.isEmpty()) {
            return chrRam!![maskedAddress]
        }

        // MMC3 CHR banking (per NESdev MMC3 wiki):
        //   R0/R1 select 2 KB CHR banks. The bank number is given in 1 KB
        //   units with bit 0 ignored, so the multiplier is 0x400, NOT 0x800.
        //   R2-R5 select 1 KB banks directly (multiplier 0x400).
        //   Bit 7 of $8000 swaps the 2 KB and 1 KB windows between halves.
        return if (chrPrgInvert) {
            when (maskedAddress) {
                in 0x0000..0x03FF -> chrRom[(chrBanks[2] * 0x0400 + maskedAddress) % chrRom.size]
                in 0x0400..0x07FF -> chrRom[(chrBanks[3] * 0x0400 + (maskedAddress - 0x0400)) % chrRom.size]
                in 0x0800..0x0BFF -> chrRom[(chrBanks[4] * 0x0400 + (maskedAddress - 0x0800)) % chrRom.size]
                in 0x0C00..0x0FFF -> chrRom[(chrBanks[5] * 0x0400 + (maskedAddress - 0x0C00)) % chrRom.size]
                in 0x1000..0x17FF -> chrRom[((chrBanks[0] and 0xFE) * 0x0400 + (maskedAddress - 0x1000)) % chrRom.size]
                in 0x1800..0x1FFF -> chrRom[((chrBanks[1] and 0xFE) * 0x0400 + (maskedAddress - 0x1800)) % chrRom.size]
                else -> chrRom[maskedAddress % chrRom.size]
            }
        } else {
            when {
                maskedAddress < 0x0800 -> chrRom[((chrBanks[0] and 0xFE) * 0x0400 + maskedAddress) % chrRom.size]
                maskedAddress < 0x1000 -> chrRom[((chrBanks[1] and 0xFE) * 0x0400 + (maskedAddress - 0x0800)) % chrRom.size]
                maskedAddress < 0x1400 -> chrRom[(chrBanks[2] * 0x0400 + (maskedAddress - 0x1000)) % chrRom.size]
                maskedAddress < 0x1800 -> chrRom[(chrBanks[3] * 0x0400 + (maskedAddress - 0x1400)) % chrRom.size]
                maskedAddress < 0x1C00 -> chrRom[(chrBanks[4] * 0x0400 + (maskedAddress - 0x1800)) % chrRom.size]
                maskedAddress < 0x2000 -> chrRom[(chrBanks[5] * 0x0400 + (maskedAddress - 0x1C00)) % chrRom.size]
                else -> chrRom[maskedAddress % chrRom.size]
            }
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            chrRam!![address and 0x1FFF] = value
        }
        // CHR ROM is read-only
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return mirroringOverride ?: when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    /**
     * Acknowledge pending IRQ (called after CPU handles the interrupt).
     */
    override fun acknowledgeIrq() {
        scanlineCounter.acknowledgeIrq()
    }

    /**
     * Check if IRQ is pending (for CPU interrupt line).
     */
    override fun isIrqPending(): Boolean = scanlineCounter.isIrqPending()

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 4,
            type = "MMC3/TxROM",
            banks = mapOf(
                "prgBank6" to prgBank6,
                "prgBankA" to prgBankA,
                "chrBankR0" to chrBanks[0],
                "chrBankR1" to chrBanks[1],
                "chrBankR2" to chrBanks[2],
                "chrBankR3" to chrBanks[3],
                "chrBankR4" to chrBanks[4],
                "chrBankR5" to chrBanks[5]
            ),
            registers = mapOf(
                "bankSelect" to bankSelect,
                "chrPrgInvert" to if (chrPrgInvert) 1 else 0,
                "prgMode" to if (prgMode) 1 else 0
            ),
            irqState = mapOf(
                "irqLatch" to scanlineCounter.irqLatchValue(),
                "irqEnabled" to if (scanlineCounter.isIrqEnabled()) 1 else 0,
                "irqCounter" to scanlineCounter.irqCounterValue(),
                "irqPending" to if (scanlineCounter.isIrqPending()) 1 else 0,
                "a12ToggleCount" to 0
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = prgRam.copyOf()
        )
    }
}
