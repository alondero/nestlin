package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

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
 *
 * The class is `open` so stripped-down MMC3 derivatives (e.g. Mapper 64 /
 * Tengen RAMBO-1) can override the per-register write handlers without
 * duplicating the PRG / CHR decode or save-state code. The override
 * pattern is documented on each `protected open` write handler below.
 */
open class Mapper4(private val gamePak: GamePak) : Mapper {

    protected val programRom = gamePak.programRom
    protected val chrRom = gamePak.chrRom
    protected val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    // PRG bank count (8KB units)
    protected val prgBankCount = programRom.size / 0x2000

    // 8KB PRG banks
    protected var prgBank6 = 0  // $8000-$9FFF
    protected var prgBankA = 1  // $A000-$BFFF (R7, always switchable)
    // $C000-$DFFF uses prgBankCount-2 when prgMode=0, prgBank6 when prgMode=1
    // $E000-$FFFF always last bank (fixed)

    // 2KB CHR banks (R0, R1) and 1KB CHR banks (R2-R5)
    protected var chrBanks = IntArray(6) { 0 }

    // PRG RAM ($6000-$7FFF). Exposed as `protected` so subclasses (e.g. Mapper
    // 64) can write through it directly when they remove the enable/protect
    // gating that MMC3 exposes at $A001.
    protected val prgRam = ByteArray(0x2000)
    private var prgRamEnabled = true
    private var prgRamWriteProtect = false
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    // Bank select register ($8000)
    protected var bankSelect = 0
    // CHR/PRG inversion mode (bit 6 of $8000)
    private var chrPrgInvert = false
    // PRG banking mode (bit 7 of $8000)
    private var prgMode = false

    // IRQ counter (MMC3 scanline IRQ). Exposed as `protected` so stripped-
    // down derivatives (Mapper 64 / Tengen RAMBO-1) can install the
    // (latch + 1) reload quirk via `scanlineCounter.setReloadPlusOne(true)`.
    protected val scanlineCounter = ScanlineCounter()

    // Mirroring override from $A000 register (bit 0: 0=vertical, 1=horizontal)
    private var mirroringOverride: Mapper.MirroringMode? = null

    // The CPU data-bus value, set by Memory just before each `cpuRead`
    // call. Subclasses that want open-bus reads (HES NTD-8 / Mapper 113)
    // can read this from their `cpuRead`. The default Mapper property
    // is 0, so mappers that don't override stay 0-on-open-bus.
    override var dataBus: Byte = 0

    override fun notifyA12Edge(rising: Boolean) {
        if (!rising) return
        scanlineCounter.clock()
    }

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            return if (prgRamEnabled) prgRam[address - 0x6000] else 0
        }
        if (address < 0x8000) return 0  // $4020-$5FFF is open bus on standard MMC3

        return when (address and 0xE000) {
            0x8000 -> {
                // $8000-$9FFF: second-to-last (fixed) in mode 1, R6 (prgBank6, switchable) in mode 0
                val bank = if (prgMode) (prgBankCount - 2).coerceAtLeast(0) else prgBank6
                programRom[(bank * 0x2000 + (address - 0x8000)) % programRom.size]
            }
            0xA000 -> {
                // $A000-$BFFF: always R7 (prgBankA)
                programRom[(prgBankA * 0x2000 + (address - 0xA000)) % programRom.size]
            }
            0xC000 -> {
                // $C000-$DFFF: R8 (second-to-last) or R6 depending on prgMode
                val bank = if (prgMode) prgBank6 else (prgBankCount - 2).coerceAtLeast(0)
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
            handlePrgRamWrite(address, value)
            return
        }
        if (address < 0x8000) return

        // `address and 0xE001` preserves bit 0 (bank-select vs bank-data) and
        // bits 13-15 (the $8/$A/$C/$E block), dropping the middle address bits.
        // Equivalent to the original `address and 0xE000` + low-bit test, but
        // a single expression lets each handler map to one branch.
        val v = value.toUnsignedInt()
        when (address and 0xE001) {
            0x8000 -> handleBankSelectWrite(v)
            0x8001 -> handleBankDataWrite(v)
            0xA000 -> handleMirroringWrite(v)
            0xA001 -> handlePrgRamProtectWrite(v)
            0xC000 -> handleIrqLatchWrite(v)
            0xC001 -> handleIrqReloadWrite(v)
            0xE000 -> handleIrqDisableWrite()
            0xE001 -> handleIrqEnableWrite()
        }
    }

    /**
     * $6000-$7FFF PRG-RAM write. Default behaviour respects the $A001
     * enable + write-protect bits. Stripped-down derivatives without PRG-RAM
     * at all (e.g. Mapper 64 / Tengen RAMBO-1) override this to discard
     * the write — they typically also override [cpuRead] to return 0.
     */
    protected open fun handlePrgRamWrite(address: Int, value: Byte) {
        if (prgRamEnabled && !prgRamWriteProtect) {
            prgRam[address - 0x6000] = value
            batteryDirty = true
        }
    }

    /**
     * $8000 bank-select write. Bits 0-2 pick R0-R7; bit 6 is the PRG mode
     * (when set, R6 and the second-to-last bank are swapped) and bit 7 is
     * CHR A12 inversion. Subclasses with hardwired modes (Mapper 206) can
     * call `super` with `value and 0x3F` so the mode bits never latch —
     * Mapper 64 (Tengen RAMBO-1) is a faithful clone and uses the modes
     * unchanged, so it does NOT override this.
     */
    protected open fun handleBankSelectWrite(value: Int) {
        bankSelect = value and 0x07
        prgMode = (value and 0x40) != 0
        chrPrgInvert = (value and 0x80) != 0
    }

    /** $8001 bank-data write. Forwards to [setBank] with the current select. */
    protected open fun handleBankDataWrite(value: Int) {
        setBank(bankSelect, value)
    }

    /** $A000 mirroring write. Bit 0: 0=vertical, 1=horizontal. */
    protected open fun handleMirroringWrite(value: Int) {
        mirroringOverride = if ((value and 0x01) != 0) {
            Mapper.MirroringMode.HORIZONTAL
        } else {
            Mapper.MirroringMode.VERTICAL
        }
    }

    /** $A001 PRG-RAM protect. Bit 7 = enable, bit 6 = write-protect. */
    protected open fun handlePrgRamProtectWrite(value: Int) {
        prgRamEnabled = (value and 0x80) != 0
        prgRamWriteProtect = (value and 0x40) != 0
    }

    /** $C000 IRQ latch. */
    protected open fun handleIrqLatchWrite(value: Int) {
        scanlineCounter.writeLatch(value)
    }

    /** $C001 IRQ reload. Reload happens on next clock tick (A12 edge or
     *  CPU cycle, depending on mode). The `value` parameter carries the
     *  full write byte — Tengen RAMBO-1 uses bit 0 to select CPU-cycle
     *  mode vs the default A12 mode (see Mapper64). */
    protected open fun handleIrqReloadWrite(value: Int) {
        scanlineCounter.triggerReload()
    }

    /** $E000 IRQ disable. */
    protected open fun handleIrqDisableWrite() {
        scanlineCounter.setEnabled(false)
    }

    /** $E001 IRQ enable. */
    protected open fun handleIrqEnableWrite() {
        scanlineCounter.setEnabled(true)
    }

    /**
     * Force PRG mode and CHR A12 inversion back to mode 0. Subclasses with
     * hardwired modes use this from their `loadState` so a save state
     * produced by a mode-aware sibling (e.g. a real MMC3 image) can't
     * desync the banking after load.
     */
    protected fun resetBankingModes() {
        prgMode = false
        chrPrgInvert = false
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
            return chrMemory.read(maskedAddress)
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
            chrMemory.write(address and 0x1FFF, value)
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

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank6)
        out.writeInt(prgBankA)
        for (b in chrBanks) out.writeInt(b)
        out.write(prgRam)
        out.writeBoolean(prgRamEnabled)
        out.writeBoolean(prgRamWriteProtect)
        out.writeInt(bankSelect)
        out.writeBoolean(chrPrgInvert)
        out.writeBoolean(prgMode)
        scanlineCounter.saveState(out)
        out.writeInt(mirroringOverride?.ordinal ?: -1)
        chrMemory.serialize(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank6 = input.readInt()
        prgBankA = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        input.readFully(prgRam)
        prgRamEnabled = input.readBoolean()
        prgRamWriteProtect = input.readBoolean()
        bankSelect = input.readInt()
        chrPrgInvert = input.readBoolean()
        prgMode = input.readBoolean()
        scanlineCounter.loadState(input)
        val mirrorOrd = input.readInt()
        mirroringOverride = if (mirrorOrd < 0) null else Mapper.MirroringMode.values()[mirrorOrd]
        chrMemory.deserialize(input)
    }

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
            // Snapshot chrRam for debug display: extract via the peek seam.
            chrRam = chrMemory.snapshotBytes(),
            prgRam = prgRam.copyOf()
        )
    }
}
