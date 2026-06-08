package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 64 (Tengen RAMBO-1) — Tengen's unlicensed MMC3 derivative.
 *
 * RAMBO-1 looks like an MMC3 at the register level ($8000/$8001 bank
 * select+data, $A000 mirroring, $C000/$C001/$E000/$E001 IRQ) but its
 * *banking* differs from MMC3 in three ways that real games rely on, so
 * this class owns the PRG/CHR decode rather than inheriting Mapper4's.
 * The IRQ machinery, mirroring, and CHR-RAM fallback ARE inherited.
 *
 * Modelled byte-for-byte on Mesen2's `Rambo1.h` (the project's reference
 * oracle) — where the nesdev prose and Mesen disagree, Mesen wins because
 * the state-diff tests compare against it:
 *
 *   1. **4-bit register select.** `$8000` bits 0-3 select R0-R15 (MMC3 uses
 *      bits 0-2, R0-R7). R8/R9 are extra 1 KB CHR banks; R15 is a third
 *      switchable PRG bank.
 *   2. **Three switchable 8 KB PRG banks** (R6, R7, R15). PRG mode (bit 6):
 *        mode 0 → $8000=R6, $A000=R7, $C000=R15
 *        mode 1 → $8000=R15, $A000=R6, $C000=R7
 *      $E000 is always the last bank. (MMC3 has only R6/R7 and fixes one of
 *      $8000/$C000 to the second-to-last bank — RAMBO-1 never does.)
 *   3. **1 KB CHR mode** (bit 5, the "K" bit). When set, the $0000-$0FFF
 *      half is four independent 1 KB banks (R0, R8, R1, R9) instead of two
 *      2 KB banks (R0, R0+1, R1, R1+1). Bit 7 (CHR A12 inversion) XORs the
 *      1 KB page index with 4, swapping the $0000-$0FFF and $1000-$1FFF
 *      halves.
 *
 * The original implementation subclassed Mapper4 and inherited MMC3's
 * 3-bit decode, two-PRG-bank layout, and 2 KB-only CHR. That booted (the
 * register protocol is the same) but rendered garbage: Klax selects R15,
 * R8/R9, and K-mode (verified by `Mapper64KlaxBankTraceTest`), all of
 * which the MMC3 decode silently aliased or ignored, so the CPU jumped
 * into the wrong PRG bank and the title screen never appeared.
 *
 * IRQ: RAMBO-1 supports A12 (scanline) clocking like MMC3 and an alternate
 * CPU-cycle clock (selected by $C001 bit 0) that ticks the counter once
 * every 4 CPU cycles. The reload quirk ("Klax needs +1") lives in
 * [ScanlineCounter] via [ScanlineCounter.setReloadPlusOne].
 *
 * Games: ~12-15 Tengen titles — *Klax*, *Skull & Crossbones*, *Road
 * Runner*, *Rolling Thunder*, *Alien Syndrome*, *Toobin'*.
 */
class Mapper64(private val gamePak: GamePak) : Mapper4(gamePak) {

    // R0-R15. R0-R5/R8/R9 are CHR (1 KB units), R6/R7/R15 are PRG (8 KB
    // units). R10-R14 are unused on RAMBO-1 (Mesen ignores them too) but a
    // game may still latch values into them, so the array is full-width.
    private val reg = IntArray(16)
    private var regSelect = 0       // $8000 bits 0-3
    private var prgMode = false     // $8000 bit 6
    private var chrInvert = false   // $8000 bit 7
    private var chrMode1k = false   // $8000 bit 5 ("K")

    // CPU-cycle IRQ mode divides the CPU clock by 4 before ticking the
    // counter (Mesen: `_cpuClockCounter = (_cpuClockCounter + 1) & 3`).
    private var cpuClockCounter = 0

    init {
        // Tengen RAMBO-1 reload quirk (see ScanlineCounter): the IRQ counter
        // reloads to latch+1/latch+2 rather than plain latch.
        scanlineCounter.setReloadPlusOne(true)
    }

    // ---- Bank select / data ($8000 / $8001) ----

    override fun handleBankSelectWrite(value: Int) {
        regSelect = value and 0x0F
        prgMode = (value and 0x40) != 0
        chrInvert = (value and 0x80) != 0
        chrMode1k = (value and 0x20) != 0
    }

    override fun handleBankDataWrite(value: Int) {
        // Raw value stored; masking to ROM size happens at read time, exactly
        // like Mesen's SelectPrg/ChrPage. R10-R14 are stored but never read.
        reg[regSelect] = value and 0xFF
    }

    // ---- IRQ clock mode ($C001 bit 0) ----

    override fun handleIrqReloadWrite(value: Int) {
        super.handleIrqReloadWrite(value)
        scanlineCounter.setCpuCycleMode((value and 0x01) != 0)
    }

    /** Clock the IRQ counter once every 4 CPU cycles when in CPU-cycle mode. */
    override fun tickCpuCycle() {
        if (scanlineCounter.isCpuCycleMode()) {
            cpuClockCounter = (cpuClockCounter + 1) and 0x03
            if (cpuClockCounter == 0) scanlineCounter.clock()
        }
    }

    /**
     * A12 (scanline) clock source. The two RAMBO-1 IRQ clock sources are
     * mutually exclusive: in CPU-cycle mode the counter is driven only by
     * [tickCpuCycle], so A12 rising edges must be ignored (otherwise the
     * counter would be clocked from both and fire far too often).
     */
    override fun notifyA12Edge(rising: Boolean) {
        if (scanlineCounter.isCpuCycleMode()) return
        super.notifyA12Edge(rising)
    }

    // ---- $A001 / PRG-RAM: RAMBO-1 has none ----

    override fun handlePrgRamProtectWrite(value: Int) {}            // no PRG-RAM to gate
    override fun handlePrgRamWrite(address: Int, value: Byte) {}    // discard
    override fun batteryBackedRam(): ByteArray? = null

    // ---- PRG read ($8000-$FFFF; everything below is open bus / 0) ----

    override fun cpuRead(address: Int): Byte {
        if (address < 0x8000) return 0   // no PRG-RAM ($6000-$7FFF), no expansion ($4020-$5FFF)

        val bank8k = when (address and 0xE000) {
            0x8000 -> if (prgMode) reg[15] else reg[6]
            0xA000 -> if (prgMode) reg[6] else reg[7]
            0xC000 -> if (prgMode) reg[7] else reg[15]
            else   -> prgBankCount - 1   // $E000-$FFFF: last bank, fixed
        }
        val base = (bank8k % prgBankCount) * 0x2000
        return programRom[(base + (address and 0x1FFF)) % programRom.size]
    }

    // ---- CHR read ($0000-$1FFF) ----

    override fun ppuRead(address: Int): Byte {
        if (chrRom.isEmpty()) return chrRam!![address and 0x1FFF]

        val a = address and 0x1FFF
        val inv = if (chrInvert) 4 else 0
        val slot = (a / 0x400) xor inv   // physical 1 KB page -> logical slot
        val bank1k = when (slot) {
            0 -> reg[0]
            1 -> if (chrMode1k) reg[8] else reg[0] + 1
            2 -> reg[1]
            3 -> if (chrMode1k) reg[9] else reg[1] + 1
            4 -> reg[2]
            5 -> reg[3]
            6 -> reg[4]
            else -> reg[5]               // slot 7
        }
        return chrRom[(bank1k * 0x400 + (a and 0x3FF)) % chrRom.size]
    }

    // ---- Save state ----
    // super.saveState/loadState handles the version byte, scanline counter,
    // mirroring override, and CHR-RAM. The MMC3 banking fields it also writes
    // are unused by RAMBO-1 but harmless; our own register file follows.

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        for (r in reg) out.writeInt(r)
        out.writeInt(regSelect)
        out.writeBoolean(prgMode)
        out.writeBoolean(chrInvert)
        out.writeBoolean(chrMode1k)
        out.writeInt(cpuClockCounter)
        // The CPU-cycle IRQ mode flag lives in the (shared) ScanlineCounter,
        // whose own save format we don't want to perturb for MMC3 mappers, so
        // persist it here in Mapper64's block instead.
        out.writeBoolean(scanlineCounter.isCpuCycleMode())
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in reg.indices) reg[i] = input.readInt()
        regSelect = input.readInt()
        prgMode = input.readBoolean()
        chrInvert = input.readBoolean()
        chrMode1k = input.readBoolean()
        cpuClockCounter = input.readInt()
        scanlineCounter.setCpuCycleMode(input.readBoolean())
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 64,
            type = "Tengen RAMBO-1",
            banks = mapOf(
                "prgBank6" to reg[6],
                "prgBankA" to reg[7],
                "prgBankF" to reg[15],
                "chrBankR0" to reg[0],
                "chrBankR1" to reg[1],
                "chrBankR2" to reg[2],
                "chrBankR3" to reg[3],
                "chrBankR4" to reg[4],
                "chrBankR5" to reg[5],
                "chrBankR8" to reg[8],
                "chrBankR9" to reg[9]
            ),
            registers = mapOf(
                "regSelect" to regSelect,
                "prgMode" to if (prgMode) 1 else 0,
                "chrPrgInvert" to if (chrInvert) 1 else 0,
                "chrMode1k" to if (chrMode1k) 1 else 0
            ),
            irqState = mapOf(
                "irqLatch" to scanlineCounter.irqLatchValue(),
                "irqEnabled" to if (scanlineCounter.isIrqEnabled()) 1 else 0,
                "irqCounter" to scanlineCounter.irqCounterValue(),
                "irqPending" to if (scanlineCounter.isIrqPending()) 1 else 0,
                "a12ToggleCount" to 0
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = null   // No PRG-RAM on real hardware.
        )
    }
}
