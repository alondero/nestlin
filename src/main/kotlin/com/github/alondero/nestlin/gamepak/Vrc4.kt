package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Konami VRC4 base implementation. Shared by Mapper21 / Mapper23 / Mapper25.
 *
 * VRC4 is a single chip; Konami varied the cartridge PCB so that different CPU
 * address pins fed the chip's register-select inputs, producing four distinct
 * "submapper" address layouts. The iNES mapper number tells us only which pair
 * of submappers a cart belongs to:
 *
 *   - Mapper 21 = VRC4a (A1→A0, A2→A1) or VRC4c (A6→A0, A7→A1)
 *   - Mapper 23 = VRC4e (A2→A0, A3→A1) or VRC4f (A0→A0, A1→A1)
 *   - Mapper 25 = VRC4b (A1→A0, A0→A1) or VRC4d (A3→A0, A2→A1)
 *
 * Without NES 2.0 submapper information we can't choose between the two
 * candidates in each pair, so we OR together both decoded "sub-register"
 * indices: VRC4f writes only touch bits 0-1 of the address, VRC4e writes
 * only touch bits 2-3, etc., so the OR is unambiguous in practice. This is
 * the same approach used by FCEUX and Nintaco.
 *
 * Per the NESdev VRC4 page, the canonical register layout (using a 0..3
 * "sub-register index" derived from two address pins) is:
 *
 *   $8xxx (any sub): PRG bank select 0 — 8KB window at $8000 or $C000.
 *   $9xxx sub 0/1:   mirroring control (bits 0-1).
 *   $9xxx sub 2/3:   PRG swap mode (bit 0) + WRAM enable (bit 1).
 *   $Axxx (any sub): PRG bank select 1 — 8KB window at $A000.
 *   $Bxxx-$Exxx:     CHR bank low nibble (sub 0,2) and high 5 bits (sub 1,3)
 *                    for each of the eight 1KB CHR windows.
 *   $Fxxx sub 0:     IRQ latch low 4 bits.
 *   $Fxxx sub 1:     IRQ latch high 4 bits.
 *   $Fxxx sub 2:     IRQ control (M cycle/scanline, E enable, A enable-after-ack).
 *   $Fxxx sub 3:     IRQ acknowledge (copies A→E, clears pending IRQ).
 *
 * The IRQ counter (see [tickCpuCycle]) is an 8-bit value that increments each
 * CPU cycle (M=1) or each ~113⅔ CPU cycles via a 341-step prescaler (M=0).
 * When it wraps $FF → reload, an IRQ is raised. This is implemented per the
 * NESdev VRC IRQ page.
 */
abstract class Vrc4(protected val gamePak: GamePak) : Mapper {

    protected val programRom: ByteArray = gamePak.programRom
    protected val chrRom: ByteArray = gamePak.chrRom
    protected val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // 8KB PRG bank count; $E000-$FFFF is fixed to the last bank.
    private val prgBankCount = (programRom.size / 0x2000).coerceAtLeast(1)

    // 8KB PRG-RAM at $6000-$7FFF. WRAM enable bit at $9002 gates access; the
    // page is always allocated since cartridges with WRAM are common in VRC4.
    private val prgRam = ByteArray(0x2000)
    private var wramEnabled = false
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    // PRG: two switchable 8KB banks. prg0 lands at $8000 or $C000 depending on
    // prgSwapMode; prg1 always lands at $A000.
    private var prg0 = 0
    private var prg1 = 0
    private var prgSwapMode = false

    // CHR: eight 1KB banks, each with a 9-bit bank number assembled from a
    // 4-bit low nibble (even sub) and a 5-bit high nibble (odd sub).
    protected val chrBanks = IntArray(8)

    // Mirroring override from $9000-$9001. -1 = use header default.
    private var mirroringMode: Int = -1

    // VRC IRQ state.
    private var irqLatch = 0          // 8-bit reload value
    private var irqCounter = 0        // 8-bit
    private var irqPrescaler = 341    // scanline-mode integer prescaler (NESdev: 341, step -3)
    private var irqModeCycle = false  // M bit: true = clock per CPU cycle, false = scanline
    private var irqEnabled = false    // E bit
    private var irqEnableAfterAck = false  // A bit
    private var irqPending = false

    /**
     * Subclass hook: collapse a $8000-$FFFF write address into a canonical
     * sub-register index (0..3). Each VRC4 submapper picks two address pins
     * for the index; the OR-of-both-submappers fallback is what concrete
     * mappers actually do.
     */
    protected abstract fun decodeSubRegister(address: Int): Int

    override fun cpuRead(address: Int): Byte {
        return when {
            address in 0x6000..0x7FFF -> {
                if (wramEnabled) prgRam[address - 0x6000] else 0
            }
            // PRG bank 0 is at $8000 (swap mode 0) or $C000 (swap mode 1).
            address in 0x8000..0x9FFF -> {
                val bank = if (prgSwapMode) (prgBankCount - 2).coerceAtLeast(0) else prg0
                programRom[(bank * 0x2000 + (address - 0x8000)) % programRom.size]
            }
            address in 0xA000..0xBFFF -> {
                programRom[(prg1 * 0x2000 + (address - 0xA000)) % programRom.size]
            }
            address in 0xC000..0xDFFF -> {
                val bank = if (prgSwapMode) prg0 else (prgBankCount - 2).coerceAtLeast(0)
                programRom[(bank * 0x2000 + (address - 0xC000)) % programRom.size]
            }
            address in 0xE000..0xFFFF -> {
                val last = (prgBankCount - 1).coerceAtLeast(0)
                programRom[(last * 0x2000 + (address - 0xE000)) % programRom.size]
            }
            else -> 0
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        if (address in 0x6000..0x7FFF) {
            if (wramEnabled) {
                prgRam[address - 0x6000] = value
                batteryDirty = true
            }
            return
        }
        if (address < 0x8000) return

        val sub = decodeSubRegister(address)
        when (address and 0xF000) {
            0x8000 -> prg0 = v and 0x1F     // 5-bit PRG bank (32 × 8KB = 256KB max)
            0x9000 -> write9(sub, v)
            0xA000 -> prg1 = v and 0x1F
            in 0xB000..0xE000 -> writeChr(address and 0xF000, sub, v)
            0xF000 -> writeIrq(sub, v)
        }
    }

    private fun write9(sub: Int, v: Int) {
        when (sub) {
            // $9000 / $9001 → mirroring (bits 0-1).
            0, 1 -> mirroringMode = v and 0x03
            // $9002 / $9003 → PRG swap mode (bit 0) + WRAM enable (bit 1).
            //
            // The NESdev wiki documents two slightly different bit layouts that
            // appear on real boards; the universally safe decode is "swap = bit 1,
            // wram = bit 0" for $9002 and "swap = bit 0, wram = bit 1" for $9003,
            // but in practice every VRC4 game writes both at once with bit 1 set
            // for WRAM-enable. We mirror Mesen here: take swap from bit 0 and
            // WRAM-enable from bit 1, regardless of which sub-register fired.
            2, 3 -> {
                prgSwapMode = (v and 0x02) != 0
                wramEnabled = (v and 0x01) != 0
            }
        }
    }

    private fun writeChr(group: Int, sub: Int, v: Int) {
        // Each $B/$C/$D/$E group hosts two 1KB CHR registers; sub picks which
        // register and whether we're writing the 4-bit low nibble or the 5-bit
        // high nibble of that register's 9-bit bank index.
        val bankBase = ((group shr 12) - 0xB) * 2       // $B → 0, $C → 2, $D → 4, $E → 6
        val bank = bankBase + (sub shr 1)               // sub 0/1 → bankBase, sub 2/3 → bankBase+1
        val high = (sub and 0x01) != 0
        chrBanks[bank] = if (high) {
            (chrBanks[bank] and 0x0F) or ((v and 0x1F) shl 4)
        } else {
            (chrBanks[bank] and 0x1F0) or (v and 0x0F)
        }
    }

    private fun writeIrq(sub: Int, v: Int) {
        when (sub) {
            // $F000: IRQ latch low 4 bits.
            0 -> irqLatch = (irqLatch and 0xF0) or (v and 0x0F)
            // $F001: IRQ latch high 4 bits.
            1 -> irqLatch = (irqLatch and 0x0F) or ((v and 0x0F) shl 4)
            // $F002: IRQ control. Writing always acknowledges a pending IRQ
            // and resets the prescaler. With E=1, the counter is reloaded from
            // the latch. M selects cycle (1) vs scanline (0) mode.
            2 -> {
                irqEnableAfterAck = (v and 0x01) != 0
                irqEnabled = (v and 0x02) != 0
                irqModeCycle = (v and 0x04) != 0
                irqPending = false
                if (irqEnabled) {
                    irqCounter = irqLatch
                    irqPrescaler = 341
                }
            }
            // $F003: IRQ acknowledge. Copies A→E; counter and prescaler unchanged.
            3 -> {
                irqPending = false
                irqEnabled = irqEnableAfterAck
            }
        }
    }

    override fun tickCpuCycle() {
        if (!irqEnabled) return
        if (irqModeCycle) {
            clockIrqCounter()
        } else {
            // Scanline mode: prescaler approximates 113⅔ CPU cycles per
            // scanline by starting at 341 and subtracting 3 each cycle.
            // When it crosses zero, we step the prescaler back up by 341
            // and clock the counter.
            irqPrescaler -= 3
            if (irqPrescaler <= 0) {
                irqPrescaler += 341
                clockIrqCounter()
            }
        }
    }

    private fun clockIrqCounter() {
        if (irqCounter == 0xFF) {
            irqCounter = irqLatch
            irqPending = true
        } else {
            irqCounter++
        }
    }

    override fun isIrqPending(): Boolean = irqPending

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return chrRam!![a]
        val bank = chrBanks[a shr 10]
        return chrRom[(bank * 0x0400 + (a and 0x03FF)) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) chrRam!![address and 0x1FFF] = value
    }

    override fun currentMirroring(): Mapper.MirroringMode {
        return when (mirroringMode) {
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
        out.writeInt(prg0)
        out.writeInt(prg1)
        out.writeBoolean(prgSwapMode)
        for (b in chrBanks) out.writeInt(b)
        out.write(prgRam)
        out.writeBoolean(wramEnabled)
        out.writeInt(mirroringMode)
        out.writeInt(irqLatch)
        out.writeInt(irqCounter)
        out.writeInt(irqPrescaler)
        out.writeBoolean(irqModeCycle)
        out.writeBoolean(irqEnabled)
        out.writeBoolean(irqEnableAfterAck)
        out.writeBoolean(irqPending)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prg0 = input.readInt()
        prg1 = input.readInt()
        prgSwapMode = input.readBoolean()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        input.readFully(prgRam)
        wramEnabled = input.readBoolean()
        mirroringMode = input.readInt()
        irqLatch = input.readInt()
        irqCounter = input.readInt()
        irqPrescaler = input.readInt()
        irqModeCycle = input.readBoolean()
        irqEnabled = input.readBoolean()
        irqEnableAfterAck = input.readBoolean()
        irqPending = input.readBoolean()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }

    /**
     * Subclass hook: which mapper number this concrete class corresponds to
     * (21 / 23 / 25). Used purely for [snapshot] / debugging.
     */
    protected abstract val mapperId: Int

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = mapperId,
            type = "Konami VRC4",
            banks = mapOf(
                "prg0" to prg0,
                "prg1" to prg1,
                "chr0" to chrBanks[0],
                "chr1" to chrBanks[1],
                "chr2" to chrBanks[2],
                "chr3" to chrBanks[3],
                "chr4" to chrBanks[4],
                "chr5" to chrBanks[5],
                "chr6" to chrBanks[6],
                "chr7" to chrBanks[7]
            ),
            registers = mapOf(
                "prgSwapMode" to if (prgSwapMode) 1 else 0,
                "wramEnabled" to if (wramEnabled) 1 else 0,
                "mirroring" to mirroringMode
            ),
            irqState = mapOf(
                "irqLatch" to irqLatch,
                "irqCounter" to irqCounter,
                "irqPrescaler" to irqPrescaler,
                "irqModeCycle" to if (irqModeCycle) 1 else 0,
                "irqEnabled" to if (irqEnabled) 1 else 0,
                "irqEnableAfterAck" to if (irqEnableAfterAck) 1 else 0,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = prgRam.copyOf()
        )
    }
}
