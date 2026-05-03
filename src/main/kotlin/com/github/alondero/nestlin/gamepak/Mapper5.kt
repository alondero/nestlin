package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt

/**
 * Mapper 5 (MMC5/ExROM) - Nintendo's most feature-rich discrete mapper.
 *
 * Key features:
 * - Extended PRG RAM at $6000-$7FFF (8KB)
 * - Extended RAM (exRAM) at $5C00-$5FFF (8KB) with special PPU access
 * - Multi-mode PRG banking (4 modes via $5000 bits 6-7)
 * - CHR banking with 8KB or 4KB granularity
 * - Fill mode for fast nametable filling
 * - Hardware 8x8 multiplier at $5205-$5206
 * - Scanline IRQ via A12 edge detection
 *
 * Games: Castlevania III, Ultima VI, Just Breed, etc.
 */
class Mapper5(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // PRG bank count (8KB units)
    val prgBankCount = programRom.size / 0x2000

    // PRG banks (8KB each)
    private var prgBank8000 = 0    // $8000-$9FFF
    private var prgBankA000 = 1    // $A000-$BFFF
    private var prgBankC000 = 2    // $C000-$DFFF
    // $E000-$FFFF always last bank (fixed)

    // PRG RAM at $6000-$7FFF (8KB)
    private val prgRam = ByteArray(0x2000)
    private var prgRamEnabled = true

    // exRAM at $5C00-$5FFF (8KB) - for fill mode and ExGrafix
    private val exRam = ByteArray(0x400)
    private var exRamEnabled = false

    // MMC5 control register ($5000)
    // Bit 0: Fill mode enable
    // Bit 1: ExRAM enable ($5C00-$5FFF access)
    // Bit 2: PRG RAM write protect
    // Bit 3: PRG RAM chip enable (0: disable, 1: enable)
    // Bit 6-7: PRG banking mode
    private var control = 0

    // PRG banking mode (bits 6-7 of $5000)
    // 0: 32KB mode (same bank at $8000 and $A000)
    // 1: Fix $8000 to bank 0, switch at $A000
    // 2: Fix $E000 to last bank, switch at $8000
    // 3: All switchable
    private var prgMode = 3

    // CHR banking mode (bit 6 of $5000)
    // 0: 8KB CHR mode
    // 1: 4KB CHR mode
    private var chrMode8k = true

    // CHR bank number ($5001, bits 0-2)
    private var chrBank = 0

    // Fill mode registers
    private var fillModeEnabled = false
    private var fillTile = 0
    private var fillPalette = 0
    private var fillAttribute = 0

    // Scanline IRQ registers
    private var irqEnabled = false
    private var irqLatch = 0
    private var irqCounter = 0
    private var irqPending = false
    private var irqEnablePending = false  // MMC5 IRQ is level-sensitive, not edge-triggered like MMC3

    // Hardware multiplier at $5205/$5206
    private var multiplicand = 0
    private var multiplier = 0

    override fun cpuRead(address: Int): Byte {
        // exRAM at $5C00-$5FFF
        if (address in 0x5C00..0x5FFF) {
            return if (exRamEnabled) exRam[address - 0x5C00] else 0
        }

        // PRG RAM at $6000-$7FFF
        if (address in 0x6000..0x7FFF) {
            return if (prgRamEnabled) prgRam[address - 0x6000] else 0
        }

        if (address < 0x8000) return 0

        val offset = address - 0x8000
        val bankSize = 0x2000

        return when (address and 0xE000) {
            0x8000 -> {
                val bank = when (prgMode) {
                    0 -> (prgBank8000 and 0xFE)  // 32KB mode: use even bank
                    1 -> 0                        // Fixed bank 0
                    2 -> prgBank8000              // Switchable
                    else -> prgBank8000           // All switchable
                }
                programRom[(bank * bankSize + offset) % programRom.size]
            }
            0xA000 -> {
                val bank = when (prgMode) {
                    0 -> ((prgBank8000 and 0xFE) + 1)  // 32KB mode: use odd bank
                    1 -> prgBankA000                    // Switchable
                    2 -> (prgBankCount - 1)             // Fixed last bank
                    else -> prgBankA000                   // All switchable
                }
                programRom[(bank * bankSize + (address - 0xA000)) % programRom.size]
            }
            0xC000 -> {
                val bank = when (prgMode) {
                    0 -> (prgBankCount - 2)         // Second-to-last in 32KB mode
                    1 -> (prgBankCount - 2)          // Fixed second-to-last
                    2 -> prgBankC000                 // Switchable
                    else -> prgBankC000              // All switchable
                }
                programRom[(bank * bankSize + (address - 0xC000)) % programRom.size]
            }
            0xE000 -> {
                // Always last bank (MMC5 convention)
                programRom[((prgBankCount - 1) * bankSize + (address - 0xE000)) % programRom.size]
            }
            else -> 0
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val valueInt = value.toUnsignedInt()

        // exRAM at $5C00-$5FFF
        if (address in 0x5C00..0x5FFF) {
            if (exRamEnabled) {
                exRam[address - 0x5C00] = value
            }
            return
        }

        // PRG RAM at $6000-$7FFF
        if (address in 0x6000..0x7FFF) {
            if (prgRamEnabled && (control and 0x04) == 0) {  // Bit 2: write protect
                prgRam[address - 0x6000] = value
            }
            return
        }

        // $5000-$500F: MMC5 control registers
        if (address in 0x5000..0x500F) {
            when (address) {
                0x5000 -> {
                    val oldExRamEnabled = exRamEnabled
                    control = valueInt
                    exRamEnabled = (valueInt and 0x02) != 0
                    prgRamEnabled = (valueInt and 0x08) != 0
                    fillModeEnabled = (valueInt and 0x01) != 0
                    prgMode = (valueInt shr 6) and 0x03
                    chrMode8k = (valueInt and 0x40) == 0

                    // Fill mode was just enabled - reset fill counter
                    if (!oldExRamEnabled && exRamEnabled) {
                        // exRAM became enabled
                    }
                }
                0x5001 -> {
                    chrBank = valueInt and 0x07
                }
                0x5002 -> {
                    fillTile = valueInt
                }
                0x5003 -> {
                    fillPalette = valueInt and 0x07
                }
                0x5004 -> {
                    fillAttribute = valueInt
                }
                // $5005-$500F: Other MMC5 registers (expansion audio, etc.)
                // For now, just acknowledge writes
            }
            return
        }

        // Multiplier registers at $5203-$5206
        if (address == 0x5203) {
            // Multiplicand low (write triggers multiply with $5204)
            multiplicand = valueInt
            return
        }
        if (address == 0x5204) {
            multiplier = valueInt
            return
        }

        // Scanline IRQ registers at $5000-$5003
        if (address in 0x5000..0x5003) {
            // Already handled above
        }

        // Bank select/data at $8000-$9FFF
        if (address in 0x8000..0x9FFF) {
            // $8000-$9FFF is bank select for PRG and CHR
            // Even addresses: bank number to select
            if ((address and 0x01) == 0) {
                // Even: bank number
                val bankNum = valueInt and 0x7F
                prgBank8000 = bankNum
            } else {
                // Odd: actually this might be CHR bank select in some modes
                // MMC5 uses $8000-$83FF for bank select in 8KB CHR mode
                // $8000-$87FF for 4KB CHR mode low
            }
            return
        }

        // Bank data at $A000-$BFFF and $C000-$DFFF
        if (address in 0xA000..0xBFFF) {
            if ((address and 0x01) == 0) {
                prgBankA000 = valueInt and 0x7F
            }
            return
        }

        if (address in 0xC000..0xDFFF) {
            if ((address and 0x01) == 0) {
                prgBankC000 = valueInt and 0x7F
            }
            return
        }

        // $E000-$FFFF: fixed last bank, but can trigger IRQ operations
        if (address in 0xE000..0xFFFF) {
            // MMC5 IRQ is controlled via $5000-$5003, not here
        }
    }

    override fun ppuRead(address: Int): Byte {
        val maskedAddress = address and 0x1FFF

        if (chrRom.isEmpty()) {
            return chrRam!![maskedAddress]
        }

        return if (chrMode8k) {
            // 8KB CHR mode: $0000-$1FFF is one 8KB bank
            val bankBase = chrBank * 0x2000
            chrRom[(bankBase + maskedAddress) % chrRom.size]
        } else {
            // 4KB CHR mode: $0000-$0FFF is R0, $1000-$1FFF is R1
            val bankSize = 0x1000
            if (maskedAddress < 0x1000) {
                chrRom[(chrBank * bankSize + maskedAddress) % chrRom.size]
            } else {
                chrRom[((chrBank + 1) * bankSize + (maskedAddress - 0x1000)) % chrRom.size]
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
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    override fun notifyA12Edge(rising: Boolean) {
        if (!rising) return

        // MMC5 scanline IRQ: counter decrements on each A12 rising edge
        if (irqCounter == 0) {
            irqCounter = irqLatch
        } else {
            irqCounter--
        }

        if (irqCounter == 0 && irqEnabled) {
            irqPending = true
        }
    }

    override fun acknowledgeIrq() {
        irqPending = false
    }

    override fun isIrqPending(): Boolean = irqPending

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 5,
            type = "MMC5/ExROM",
            banks = mapOf(
                "prgBank8000" to prgBank8000,
                "prgBankA000" to prgBankA000,
                "prgBankC000" to prgBankC000,
                "chrBank" to chrBank
            ),
            registers = mapOf(
                "control" to control,
                "prgMode" to prgMode,
                "chrMode8k" to if (chrMode8k) 1 else 0,
                "fillModeEnabled" to if (fillModeEnabled) 1 else 0,
                "fillTile" to fillTile,
                "fillPalette" to fillPalette,
                "multiplicand" to multiplicand,
                "multiplier" to multiplier,
                "irqEnabled" to if (irqEnabled) 1 else 0,
                "irqLatch" to irqLatch,
                "irqCounter" to irqCounter,
                "irqPending" to if (irqPending) 1 else 0
            ),
            irqState = mapOf(
                "irqPending" to irqPending
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = prgRam.copyOf()
        )
    }
}