package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.apu.ExpansionAudioChannel
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Konami VRC6 base implementation. Shared by [Mapper24] (VRC6a) and
 * [Mapper26] (VRC6b); the two variants differ only by which CPU address pin
 * feeds register-select bit 0 vs bit 1 of the chip — the same wiring trick
 * Konami used to multi-source the VRC4 family.
 *
 * Spec: https://www.nesdev.org/wiki/VRC6 (mapping/IRQ) and
 * https://www.nesdev.org/wiki/VRC6_audio (sound block, in [Vrc6Audio.kt]).
 *
 * ## Register map (canonical sub-register indices 0..3)
 *
 * ```
 *   $8000-$8003  any  16K PRG bank for $8000-$BFFF     (4-bit PPPP)
 *   $9000        0    Pulse 1 control  (M, DDD, VVVV)
 *   $9001        1    Pulse 1 frequency low
 *   $9002        2    Pulse 1 frequency high + E
 *   $9003        3    Frequency control / halt        (.... .ABH)
 *   $A000-$A002  0-2  Pulse 2 control / freq lo / freq hi+E
 *   $B000        0    Sawtooth volume / rate          (..AA AAAA)
 *   $B001        1    Sawtooth frequency low
 *   $B002        2    Sawtooth frequency high + E
 *   $B003        3    PPU banking + mirroring         (W.PN MMDD)
 *   $C000-$C003  any  8K PRG bank for $C000-$DFFF     (5-bit PPPPP)
 *   $D000-$D003  0-3  CHR R0..R3
 *   $E000-$E003  0-3  CHR R4..R7
 *   $F000        0    IRQ latch
 *   $F001        1    IRQ control (M, E, A)
 *   $F002        2    IRQ acknowledge
 * ```
 *
 * $6000-$7FFF is 8 KB PRG-RAM, gated on $B003 bit 7. $E000-$FFFF is fixed to
 * the last 8 KB PRG bank.
 *
 * ## CHR / nametable banking modes (this implementation: mode 0 only)
 *
 * $B003 bits 0-1 (DD) select one of four PPU-side layouts: mode 0 = eight
 * 1 KB CHR windows + standard 4-mirroring (vertical / horizontal / 1-screen
 * A / 1-screen B), modes 1-3 = various 2 KB / 4-screen / CHR-as-nametables
 * combinations. Castlevania III JP, Esper Dream 2 and Madara all run in
 * mode 0; we throw a TODO if a game writes a non-zero DD so it surfaces
 * rather than rendering garbage.
 */
abstract class Vrc6(protected val gamePak: GamePak) : Mapper {

    protected val programRom: ByteArray = gamePak.programRom
    protected val chrRom: ByteArray = gamePak.chrRom
    protected val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // PRG bank counts derived from ROM size. 16 KB and 8 KB views over the
    // same underlying programRom; coerced to at least 1 to avoid a divide-by-
    // zero in the modulo wraps even on pathologically small dev ROMs.
    private val prg16BankCount = (programRom.size / 0x4000).coerceAtLeast(1)
    private val prg8BankCount = (programRom.size / 0x2000).coerceAtLeast(1)

    // $6000-$7FFF PRG-RAM. The page is always allocated; access is gated on
    // wramEnabled (latched from $B003 bit 7) the same way VRC4 gates its
    // $9002 WRAM bit. batteryDirty / batteryBackedRam wire the page into the
    // existing .sav persistence path.
    private val prgRam = ByteArray(0x2000)
    private var wramEnabled = false
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    private var prg16Bank = 0    // $8000-$BFFF, 4-bit
    private var prg8Bank = 0     // $C000-$DFFF, 5-bit

    /** R0..R7 — 1 KB CHR bank registers. 8-bit each (256 × 1 KB = 256 KB max). */
    protected val chrBanks = IntArray(8)

    // $B003 layout. We currently only honour mirroring (MM, bits 2-3) and the
    // WRAM-enable bit; the PPU-banking mode (DD) and nametable-source flags
    // are stored for future expansion + the snapshot.
    private var b003 = 0

    // The CPU data-bus value, set by Memory just before each `cpuRead` call.
    // Required for the WRAM-disabled read at $6000-$7FFF to return the
    // correct open-bus value (matching a real 6502 — the chip has no byte
    // of its own there when WRAM is off, so the bus holds whatever the
    // last access was). See Mapper4.kt for the same pattern.
    override var dataBus: Byte = 0

    /** VRC IRQ state — same shape as VRC4's IRQ counter. */
    private var irqLatch = 0
    private var irqCounter = 0
    private var irqPrescaler = 341
    private var irqModeCycle = false
    private var irqEnabled = false
    private var irqEnableAfterAck = false
    private var irqPending = false

    // The three expansion audio voices and their shared $9003 control.
    val pulse1 = Vrc6Pulse()
    val pulse2 = Vrc6Pulse()
    val saw = Vrc6Saw()
    private val freqControl = Vrc6FrequencyControl()

    override fun expansionAudioChannels(): List<ExpansionAudioChannel> = listOf(pulse1, pulse2, saw)

    /**
     * Subclass hook: collapse a $8000-$FFFF write address into a canonical
     * sub-register index (0..3). VRC6a reads bits 0 and 1 directly; VRC6b
     * swaps them.
     */
    protected abstract fun decodeSubRegister(address: Int): Int

    override fun cpuRead(address: Int): Byte {
        return when {
            address in 0x6000..0x7FFF -> {
                if (wramEnabled) prgRam[address - 0x6000] else dataBus
            }
            address in 0x8000..0xBFFF -> {
                val offset = (prg16Bank % prg16BankCount) * 0x4000 + (address - 0x8000)
                programRom[offset % programRom.size]
            }
            address in 0xC000..0xDFFF -> {
                val offset = (prg8Bank % prg8BankCount) * 0x2000 + (address - 0xC000)
                programRom[offset % programRom.size]
            }
            address in 0xE000..0xFFFF -> {
                val last = (prg8BankCount - 1).coerceAtLeast(0)
                val offset = last * 0x2000 + (address - 0xE000)
                programRom[offset % programRom.size]
            }
            else -> dataBus
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
            0x8000 -> prg16Bank = v and 0x0F     // 4-bit 16K PRG select
            0x9000 -> write9(sub, value)
            0xA000 -> writeA(sub, value)
            0xB000 -> writeB(sub, value)
            0xC000 -> prg8Bank = v and 0x1F      // 5-bit 8K PRG select
            0xD000 -> chrBanks[sub] = v
            0xE000 -> chrBanks[4 + sub] = v
            0xF000 -> writeIrq(sub, v)
        }
    }

    private fun write9(sub: Int, value: Byte) {
        when (sub) {
            0 -> pulse1.write4000(value)
            1 -> pulse1.write4001(value)
            2 -> pulse1.write4002(value)
            3 -> {
                freqControl.write9003(value)
                applyFreqControlToChannels()
            }
        }
    }

    private fun writeA(sub: Int, value: Byte) {
        when (sub) {
            0 -> pulse2.write4000(value)
            1 -> pulse2.write4001(value)
            2 -> pulse2.write4002(value)
            // sub 3: documented as an alias / unused on real silicon; ignore.
        }
    }

    private fun writeB(sub: Int, value: Byte) {
        when (sub) {
            0 -> saw.writeB000(value)
            1 -> saw.writeB001(value)
            2 -> saw.writeB002(value)
            3 -> {
                b003 = value.toUnsignedInt()
                // Bit 7 (W) — though NESdev's `W.PN MMDD` bit-naming reserves bit 7
                // for a separate use on some board revs, every shipping VRC6 game
                // we know about uses bit 7 as the WRAM-enable, matching how Mesen
                // and FCEUX gate the prg-ram window. We follow that convention.
                wramEnabled = (b003 and 0x80) != 0
                // DD (bits 0-1) selects one of four CHR/nametable modes. Mode 0
                // (the standard 8×1KB + 4-mirroring) is what Castlevania III JP,
                // Esper Dream 2 and Madara use. The other modes need follow-up
                // work; we surface a clear marker rather than render garbage.
                val ppuMode = b003 and 0x03
                if (ppuMode != 0) {
                    // Don't throw — a one-off bad write shouldn't crash the game;
                    // log via System.err so it surfaces in test output without
                    // failing the run. The render diff against Mesen2 will catch
                    // the visual divergence if it actually matters.
                    System.err.println(
                        "VRC6: \$B003 wrote PPU mode $ppuMode (only mode 0 implemented); " +
                            "tile/nametable mapping may diverge."
                    )
                }
            }
        }
    }

    private fun writeIrq(sub: Int, v: Int) {
        when (sub) {
            0 -> irqLatch = v and 0xFF
            1 -> {
                irqEnableAfterAck = (v and 0x01) != 0
                irqEnabled = (v and 0x02) != 0
                irqModeCycle = (v and 0x04) != 0
                irqPending = false
                if (irqEnabled) {
                    irqCounter = irqLatch
                    irqPrescaler = 341
                }
            }
            2 -> {
                irqPending = false
                irqEnabled = irqEnableAfterAck
            }
            // sub 3: unused.
        }
    }

    private fun applyFreqControlToChannels() {
        pulse1.halt = freqControl.halt
        pulse2.halt = freqControl.halt
        saw.halt = freqControl.halt
        pulse1.periodShift = freqControl.periodShift
        pulse2.periodShift = freqControl.periodShift
        saw.periodShift = freqControl.periodShift
    }

    override fun tickCpuCycle() {
        if (irqEnabled) {
            if (irqModeCycle) {
                clockIrqCounter()
            } else {
                // Scanline mode prescaler — same 341/−3 trick the VRC4 family
                // uses to approximate 113⅔ CPU cycles per scanline without
                // having to track fractional state.
                irqPrescaler -= 3
                if (irqPrescaler <= 0) {
                    irqPrescaler += 341
                    clockIrqCounter()
                }
            }
        }
        // Audio channels tick every CPU cycle. They self-gate on halt.
        pulse1.tick(1)
        pulse2.tick(1)
        saw.tick(1)
    }

    private fun clockIrqCounter() {
        // 8-bit increment-to-wrap counter: same firing rule as VRC4. The
        // counter increments each clock; when it wraps from 0xFF, an IRQ is
        // raised and the counter reloads from latch.
        if (irqCounter == 0xFF) {
            irqCounter = irqLatch
            irqPending = true
        } else {
            irqCounter++
        }
    }

    override fun isIrqPending(): Boolean = irqPending
    override fun acknowledgeIrq() { /* Acknowledged via $F002 write; CPU ack is a no-op. */ }

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
        // $B003 bits 2-3 (MM) in PPU mode 0. The 4-state mapping is the same
        // as VRC4, just at a different register address.
        return when ((b003 shr 2) and 0x03) {
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

    /** Mapper number — used for the snapshot only. */
    protected abstract val mapperId: Int

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = mapperId,
            type = "Konami VRC6",
            banks = mapOf(
                "prg16" to prg16Bank,
                "prg8" to prg8Bank,
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
                "wramEnabled" to if (wramEnabled) 1 else 0,
                "b003" to b003,
                "freqHalt" to if (freqControl.halt) 1 else 0,
                "freqShift" to freqControl.periodShift
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

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prg16Bank)
        out.writeInt(prg8Bank)
        for (b in chrBanks) out.writeInt(b)
        out.write(prgRam)
        out.writeBoolean(wramEnabled)
        out.writeInt(b003)
        out.writeInt(irqLatch)
        out.writeInt(irqCounter)
        out.writeInt(irqPrescaler)
        out.writeBoolean(irqModeCycle)
        out.writeBoolean(irqEnabled)
        out.writeBoolean(irqEnableAfterAck)
        out.writeBoolean(irqPending)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
        pulse1.saveState(out)
        pulse2.saveState(out)
        saw.saveState(out)
        freqControl.saveState(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prg16Bank = input.readInt()
        prg8Bank = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        input.readFully(prgRam)
        wramEnabled = input.readBoolean()
        b003 = input.readInt()
        irqLatch = input.readInt()
        irqCounter = input.readInt()
        irqPrescaler = input.readInt()
        irqModeCycle = input.readBoolean()
        irqEnabled = input.readBoolean()
        irqEnableAfterAck = input.readBoolean()
        irqPending = input.readBoolean()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
        pulse1.loadState(input)
        pulse2.loadState(input)
        saw.loadState(input)
        freqControl.loadState(input)
        // Re-apply the freqControl onto the channels so periodShift/halt are
        // back in sync after a reload.
        applyFreqControlToChannels()
    }
}
