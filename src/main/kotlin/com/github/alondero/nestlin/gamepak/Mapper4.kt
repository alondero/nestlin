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

    // Bank select register ($8000)
    private var bankSelect = 0
    // CHR/PRG inversion mode (bit 6 of $8000)
    private var chrPrgInvert = false
    // PRG banking mode (bit 7 of $8000)
    private var prgMode = false

    // IRQ registers
    private var irqLatch = 0    // $C000
    private var irqReload = false // $C001 trigger
    private var irqEnabled = false // $E000
    private var irqCounter = 0
    private var irqPending = false

    // Mirroring override from $A000 register (bit 0: 0=vertical, 1=horizontal)
    private var mirroringOverride: Mapper.MirroringMode? = null

    private fun clockMmc3Counter() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch
            irqReload = false
        } else {
            irqCounter--
        }
        if (irqCounter == 0 && irqEnabled) {
            irqPending = true
        }
    }

    override fun notifyA12Edge(rising: Boolean) {
        if (!rising) return
        clockMmc3Counter()
    }

    override fun cpuRead(address: Int): Byte {
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
                // Mirroring control (even address only)
                if ((address and 0x01) == 0) {
                    // Bit 0: 0=vertical, 1=horizontal
                    mirroringOverride = if ((valueInt and 0x01) != 0) {
                        Mapper.MirroringMode.HORIZONTAL
                    } else {
                        Mapper.MirroringMode.VERTICAL
                    }
                }
            }
            0xC000 -> {
                // IRQ latch (even) or IRQ reload (odd)
                if ((address and 0x01) == 0) {
                    irqLatch = valueInt
                } else {
                    // Reload flag is set; actual counter reload happens at next A12 rising edge
                    irqReload = true
                }
            }
            0xE000 -> {
                // IRQ disable (even) or enable (odd)
                if ((address and 0x01) == 0) {
                    irqEnabled = false
                    irqPending = false
                } else {
                    irqEnabled = true
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

        // MMC3 CHR banking:
        // Normal mode (chrPrgInvert=false): R0/R1 = 2KB, R2-R5 = 1KB
        // Inverted mode (chrPrgInvert=true): R2-R5 = four 1KB at $0000, R0/R1 = 2KB at $1000
        return if (chrPrgInvert) {
            // Inverted mode:
            // $0000-$03FF = R2 (1KB), $0400-$07FF = R3 (1KB)
            // $0800-$0BFF = R4 (1KB), $0C00-$0FFF = R5 (1KB)
            // $1000-$17FF = R0 (2KB), $1800-$1FFF = R1 (2KB)
            // CHR banks are 8KB units per NESdev MMC3 spec
            when (maskedAddress) {
                in 0x0000..0x03FF -> chrRom[(chrBanks[2] * 0x0400 + maskedAddress) % chrRom.size]
                in 0x0400..0x07FF -> chrRom[(chrBanks[3] * 0x0400 + (maskedAddress - 0x0400)) % chrRom.size]
                in 0x0800..0x0BFF -> chrRom[(chrBanks[4] * 0x0400 + (maskedAddress - 0x0800)) % chrRom.size]
                in 0x0C00..0x0FFF -> chrRom[(chrBanks[5] * 0x0400 + (maskedAddress - 0x0C00)) % chrRom.size]
                in 0x1000..0x17FF -> chrRom[((chrBanks[0] and 0xFE) * 0x0800 + (maskedAddress - 0x1000)) % chrRom.size]
                in 0x1800..0x1FFF -> chrRom[((chrBanks[1] and 0xFE) * 0x0800 + (maskedAddress - 0x1800)) % chrRom.size]
                else -> chrRom[maskedAddress % chrRom.size]
            }
        } else {
            // Normal mode:
            // $0000-$07FF = R0 (2KB), $0800-$0FFF = R1 (2KB)
            // $1000-$13FF = R2 (1KB), $1400-$17FF = R3 (1KB)
            // $1800-$1BFF = R4 (1KB), $1C00-$1FFF = R5 (1KB)
            // CHR banks are 8KB units per NESdev MMC3 spec
            when {
                maskedAddress < 0x0800 -> chrRom[((chrBanks[0] and 0xFE) * 0x0800 + maskedAddress) % chrRom.size]
                maskedAddress < 0x1000 -> chrRom[((chrBanks[1] and 0xFE) * 0x0800 + (maskedAddress - 0x0800)) % chrRom.size]
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
        irqPending = false
    }

    /**
     * Check if IRQ is pending (for CPU interrupt line).
     */
    override fun isIrqPending(): Boolean = irqPending

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
                "irqLatch" to irqLatch,
                "irqReload" to irqReload,
                "irqEnabled" to irqEnabled,
                "irqCounter" to irqCounter,
                "irqPending" to irqPending,
                "a12ToggleCount" to 0
            ),
            chrRam = chrRam?.copyOf()
        )
    }
}
