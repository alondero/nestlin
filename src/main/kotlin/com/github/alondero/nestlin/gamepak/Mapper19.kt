package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.apu.ExpansionAudioChannel
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 19 — Namco 163 (Namcot 163). Headline chip on
 * *Megami Tensei II* / *Kaijuu Monogatari*, *Rolling Thunder*, *Dream Master*,
 * *Final Lap*, *Star Wars* (Namco), *Splatterhouse: Wanpaku Graffiti*, and
 * roughly a dozen other Namco-published Famicom / NES titles from the late
 * '80s. The N163 combines MMC3-class fine-grained PRG/CHR banking with the
 * 8-channel wavetable synth that gives it its name.
 *
 * Spec: https://www.nesdev.org/wiki/Namco_163_audio
 * Reference: https://www.nesdev.org/wiki/Mapper_19 (the layout page; defer
 * to Mesen2's `Core/NES/Mappers/Namco/Namco163.h` for tiebreakers — the wiki
 * prose and Mesen occasionally disagree on edge cases like the $E000
 * "sound enable" polarity, and Mesen wins per the project's new-mapper
 * checklist).
 *
 * ## Register map (decoded via `addr and 0xF800`)
 *
 * ```
 *   $4800-$4FFF  Audio data port: read/write the 128-byte internal RAM at
 *                the address-port position (_ramPosition, 0..127). Auto-
 *                increments when the address port's bit 7 is set.
 *   $5000        IRQ counter low byte. Writing clears the pending IRQ.
 *   $5800        IRQ counter high byte. Writing clears the pending IRQ.
 *                High bit (bit 15) is the enable flag.
 *   $8000-$9FFF  CHR banks 0-3, 1KB each (registers at $8000, $8800,
 *                $9000, $9800). When value >= 0xE0 and !lowChrNtMode, the
 *                lower bit selects a nametable instead of CHR.
 *   $A000-$BFFF  CHR banks 4-7, 1KB each (registers at $A000, $A800,
 *                $B000, $B800). Same nametable extension via highChrNtMode.
 *   $C000-$D800  Extended CHR banks 8-11 (1KB each, into the internal
 *                CHR-RAM expansion). When value >= 0xE0, switches to a
 *                nametable via the lower bit.
 *   $E000        PRG bank 0 (8KB, low 6 bits) + sound enable (bit 6).
 *                Bit 6 = 1 disables the audio; bit 6 = 0 enables.
 *   $E800        PRG bank 1 (8KB, low 6 bits) + lowChrNtMode (bit 6) +
 *                highChrNtMode (bit 7) — once set, the $8000/$A000 nametable
 *                extension is DISABLED even when value >= 0xE0.
 *   $F000        PRG bank 2 (8KB, low 6 bits).
 *   $F800        Audio address port: low 7 bits = _ramPosition; bit 7 =
 *                _autoIncrement. Writing also sets _writeProtect.
 * ```
 *
 * ## PRG geometry (8KB granularity)
 *
 *  - `$6000-$7FFF` — 4 × 2KB PRG-RAM pages, gated by _writeProtect bits:
 *    bit 6 of the most recent $F800 = global write enable, bits 0-3 = per-page
 *    write protect (page i at $6000+i*$800). The Nestlin layer also gates
 *    .sav persistence on `Header.hasBattery` (see `batteryBackedRam()`).
 *  - `$8000-$9FFF` — PRG bank 0 (selected via $E000 bits 0-5).
 *  - `$A000-$BFFF` — PRG bank 1 (selected via $E800 bits 0-5).
 *  - `$C000-$DFFF` — PRG bank 2 (selected via $F000 bits 0-5).
 *  - `$E000-$FFFF` — last 8KB bank (always fixed; mesen wires this via
 *    `SelectPrgPage(3, -1)` at init).
 *
 * ## CHR geometry (1KB granularity)
 *
 *  - Standard 8 1KB windows at `$0000-$1FFF` (`$8000-$9FFF` and `$A000-$BFFF`
 *    register pairs). 0KB-CHR dumps get 12KB of CHR-RAM (8KB standard + 4KB
 *    extended) so the $C000-$D800 register range can also map something.
 *  - 4 extended 1KB windows at $C000-$D800 with nametable-extension
 *    semantics: value >= 0xE0 + !lowChrNtMode / !highChrNtMode → that 1KB
 *    window becomes nametable RAM at $2000 ($E0) or $2400 ($E1) instead of
 *    CHR. The chip's CHR-RAM is internal; this is the "nametable on top of
 *    CHR" mode the real hardware supports.
 *
 * ## IRQ
 *
 * 16-bit counter where bit 15 is the enable. Each CPU cycle, when the enable
 * is set and the low 15 bits are not yet 0x7FFF, the counter increments; when
 * the low 15 bits reach 0x7FFF the IRQ line is asserted. Writing either of
 * $5000/$5800 reloads the respective byte AND clears the pending IRQ.
 *
 * Why the high-bit-as-enable convention: it lets a single 16-bit write
 * ($5800 with the top bit set) atomically enable + reload, which the chip's
 * IRQ-coordination code relies on. Separating "reload" from "enable" would
 * need two writes and a race window.
 */
class Mapper19(private val gamePak: GamePak) : Mapper {

    private val programRom: ByteArray = gamePak.programRom
    private val chrRom: ByteArray = gamePak.chrRom
    // 12KB of internal CHR-RAM (8KB for the standard 1KB×8 + 4KB for the
    // extended 1KB×4 at $C000-$D800). The Mesen2 reference splits this into
    // the "CHR" page pool + the "extended CHR/nametable" page pool; for our
    // model a single 12KB buffer is enough because reads from both go through
    // the same `ppuRead` dispatch.
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x3000) else null

    // 4 × 2KB PRG-RAM pages at $6000-$7FFF. _writeProtect gates writes per
    // page; reads are always live.
    private val prgRam = ByteArray(0x2000)
    private var prgRamGlobalWriteEnable = false
    private var prgRamPageWriteProtectMask = 0   // bit i set = page i is write-protected
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    // 3 × 8KB switchable PRG banks (registers 0-2). $E000-$FFFF is always
    // the last 8KB bank (no register).
    private var prgBank0 = 0
    private var prgBank1 = 0
    private var prgBank2 = 0

    // 12 × 1KB CHR bank registers. Banks 0-7 are the standard pattern-table
    // windows; banks 8-11 are the extended range at $C000-$D800 and also
    // hold the nametable-extension state (value & 0x01 selects lower/upper
    // nametable when value >= 0xE0).
    private val chrBanks = IntArray(12)

    // PPU-nametable mode flags. When set, the corresponding $8000/$A000
    // range is "locked" out of the value>=0xE0 nametable-extend trick — the
    // value is always treated as a CHR bank number.
    private var lowChrNtMode = false   // bit 6 of $E800
    private var highChrNtMode = false  // bit 7 of $E800

    // IRQ counter (16 bits). High bit is the enable.
    private var irqCounter = 0
    private var irqPending = false

    // The audio side. Owns the 128-byte internal RAM (channel registers +
    // wavetable samples) and the 8-channel wavetable synth. Registered with
    // the APU via [expansionAudioChannels] in the standard Mapper pattern
    // (Memory.kt's `readCartridge` iterates the list and calls
    // `apu.registerExpansionChannel`).
    val audio = Namco163Audio()

    override fun expansionAudioChannels(): List<ExpansionAudioChannel> = listOf(audio)

    // The data-bus value, captured for future open-bus reads. The default
    // Mapper.dataBus is 0, so reads at $4020-$5FFF fall through to it; we
    // keep the field here for symmetry with VRC6 and the documented
    // Mind Blower Pak / Mapper 113 pattern, even though N163's $4800-$5FFF
    // is the audio data port + IRQ registers (both well-defined reads, no
    // open-bus hole).
    override var dataBus: Byte = 0

    // ---- CPU bus ------------------------------------------------------------

    override fun cpuRead(address: Int): Byte {
        return when (address) {
            in 0x4800..0x4FFF -> audio.readDataPort()
            in 0x5000..0x57FF -> (irqCounter and 0x00FF).toByte()
            in 0x5800..0x5FFF -> ((irqCounter ushr 8) and 0xFF).toByte()
            in 0x6000..0x7FFF -> prgRam[address - 0x6000]
            in 0x8000..0x9FFF -> {
                val bank = (prgBank0 and 0x3F) % prgBank8Count
                programRom[bank * 0x2000 + (address - 0x8000)]
            }
            in 0xA000..0xBFFF -> {
                val bank = (prgBank1 and 0x3F) % prgBank8Count
                programRom[bank * 0x2000 + (address - 0xA000)]
            }
            in 0xC000..0xDFFF -> {
                val bank = (prgBank2 and 0x3F) % prgBank8Count
                programRom[bank * 0x2000 + (address - 0xC000)]
            }
            in 0xE000..0xFFFF -> {
                // Last 8KB bank, no register. Same modulo so a malformed
                // <8KB PRG (truncated dump) doesn't read out of bounds.
                val bank = (prgBank8Count - 1).coerceAtLeast(0)
                programRom[bank * 0x2000 + (address - 0xE000)]
            }
            else -> dataBus
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        when {
            address in 0x4800..0x4FFF -> audio.writeDataPort(value)
            address in 0x5000..0x57FF -> {
                irqCounter = (irqCounter and 0xFF00) or v
                irqPending = false
            }
            address in 0x5800..0x5FFF -> {
                irqCounter = (irqCounter and 0x00FF) or (v shl 8)
                irqPending = false
            }
            address in 0x6000..0x7FFF -> {
                // Write-protect: bit 6 of the most recent $F800 = global
                // write-enable; bits 0-3 = per-page write-protect mask.
                // A page is writable only when global AND its bit is clear.
                val pageIndex = (address - 0x6000) ushr 11   // 0..3
                val protectedByMask = (prgRamPageWriteProtectMask shr pageIndex) and 0x01 != 0
                if (prgRamGlobalWriteEnable && !protectedByMask) {
                    prgRam[address - 0x6000] = value
                    batteryDirty = true
                }
            }
            address < 0x8000 -> Unit   // open bus, no effect
            else -> writeRegister(address, v)
        }
    }

    private fun writeRegister(address: Int, v: Int) {
        // CHR bank registers. Mesen2 decodes via `addr & 0xF800` for the
        // 8-bank and 4-bank ranges, then `addr - 0x8000) >> 11` for the
        // bank index within the range. The 0xE0+ value semantics (CHR or
        // nametable) gate on the chrNtMode flags.
        when {
            address in 0x8000..0x9FFF -> {
                val idx = (address - 0x8000) ushr 11
                writeChrBankRegister(idx, v, allowNametableExtend = !lowChrNtMode)
            }
            address in 0xA000..0xBFFF -> {
                val idx = ((address - 0xA000) ushr 11) + 4
                writeChrBankRegister(idx, v, allowNametableExtend = !highChrNtMode)
            }
            address in 0xC000..0xC7FF -> {
                // $C000 is the start of the extended CHR/namespacing range.
                // Mesen2's `bankNumber = ((addr - 0xC000) >> 11) + 8` puts
                // $C000..$C7FF at CHR bank 8. The register value also
                // (auto-)selects variant — writes to >= $C800 force Namco163.
                writeExtendedChrBank(8, v)
            }
            address in 0xC800..0xCFFF -> writeExtendedChrBank(9, v)
            address in 0xD000..0xD7FF -> writeExtendedChrBank(10, v)
            address in 0xD800..0xDFFF -> writeExtendedChrBank(11, v)
            address in 0xE000..0xE7FF -> {
                prgBank0 = v and 0x3F
                audio.writeSoundEnable(v)
            }
            address in 0xE800..0xEFFF -> {
                prgBank1 = v and 0x3F
                lowChrNtMode = (v and 0x40) != 0
                highChrNtMode = (v and 0x80) != 0
            }
            address in 0xF000..0xF7FF -> prgBank2 = v and 0x3F
            address in 0xF800..0xFFFF -> {
                // $F800 — audio address port + write-protect register.
                // Low 7 bits → _ramPosition. Bit 7 → _autoIncrement.
                audio.writeAddressPort(v)
                // Write-protect encoding (per Mesen2's UpdateSaveRamAccess):
                //   bit 6 = global write enable
                //   bits 0-3 = per-page write-protect mask (1 = protect)
                prgRamGlobalWriteEnable = (v and 0x40) != 0
                prgRamPageWriteProtectMask = v and 0x0F
            }
        }
    }

    private fun writeChrBankRegister(idx: Int, v: Int, allowNametableExtend: Boolean) {
        // Nametable extend: real hardware lets the cartridge point a 1KB
        // CHR window at $2000 (lower) or $2400 (upper) by writing a value
        // with the top bits set. The mapper tracks this as a per-bank flag
        // (we encode "this bank is a nametable" in the high bit of the
        // stored bank value to keep the data model uniform). Mesen2 wires
        // the same intent via `SelectChrPage(bankNumber, value & 0x01,
        // ChrMemoryType::NametableRam)`.
        chrBanks[idx] = if (allowNametableExtend && v >= 0xE0) {
            // Encode: 0x100 + (v & 0x01) → bit 8 set = nametable, bit 0 = which.
            0x100 or (v and 0x01)
        } else {
            v and 0xFF
        }
    }

    private fun writeExtendedChrBank(idx: Int, v: Int) {
        // Same as the standard 1KB CHR banks but the registers live in the
        // $C000-$DFFF range and have no "nametable extend" gating via
        // chrNtMode — they ALWAYS honor the value>=0xE0 nametable trick.
        // (Mesen2's writes here also force the variant to Namco163.)
        chrBanks[idx] = if (v >= 0xE0) 0x100 or (v and 0x01) else v and 0xFF
    }

    // PRG bank count derived from ROM size, modulo-defended against a
    // 0-length PRG (pathological truncated dump).
    private val prgBank8Count = (programRom.size / 0x2000).coerceAtLeast(1)

    // ---- PPU bus ------------------------------------------------------------

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        val bankIndex = a ushr 10   // 0..7
        val bank = chrBanks[bankIndex]
        if (bank and 0x100 != 0) {
            // Nametable-extension: bit 0 selects the lower ($2000) or
            // upper ($2400) nametable. We don't have a real nametable
            // backing store wired to the mapper (the PPU's own internal
            // nametable RAM is the source of truth), so we return a
            // deterministic placeholder. Real cartridges use this in
            // conjunction with extended CHR-RAM to give the PPU a 4-screen
            // arrangement; surfacing it as 0 is honest about not modelling
            // that — but the standard 8-bank decode below covers the games
            // the regression test exercises.
            //
            // TODO(extended-nametable): plumb through to the PPU's
            // nametable RAM when a game actually uses this mode. Until
            // then, log so a divergence surfaces rather than silently
            // rendering wrong tiles.
            System.err.println(
                "Mapper 19: nametable-extended CHR bank $bankIndex read at PPU " +
                    "\$${address.toString(16)}; not yet wired to nametable RAM."
            )
            return 0
        }
        val rom = chrRom
        return if (rom.isNotEmpty()) {
            val offset = (bank and 0xFF) * 0x0400 + (a and 0x03FF)
            rom[offset % rom.size]
        } else {
            chrRam!![bankIndex * 0x0400 + (a and 0x03FF)]
        }
    }

    override fun ppuWrite(address: Int, value: Byte) {
        val a = address and 0x1FFF
        val bankIndex = a ushr 10
        val bank = chrBanks[bankIndex]
        if (bank and 0x100 != 0) {
            // Same TODO as ppuRead — nametable-extended CHR windows aren't
            // currently backed by the PPU's nametable RAM.
            return
        }
        if (chrRom.isEmpty()) {
            chrRam!![bankIndex * 0x0400 + (a and 0x03FF)] = value
        }
    }

    // ---- Mirroring ----------------------------------------------------------

    override fun currentMirroring(): Mapper.MirroringMode {
        // N163 has no software-controlled mirroring register — the cartridge
        // board wires the nametable pins to one of the four fixed
        // arrangements. Defer to the iNES header.
        return when (gamePak.header.mirroring) {
            Header.Mirroring.HORIZONTAL -> Mapper.MirroringMode.HORIZONTAL
            Header.Mirroring.VERTICAL -> Mapper.MirroringMode.VERTICAL
        }
    }

    // ---- IRQ ---------------------------------------------------------------

    override fun tickCpuCycle() {
        audio.tick(1)
        // IRQ counter is the "high bit = enable, low 15 bits = countdown"
        // pattern documented at the top. We only advance when enabled and
        // the low 15 bits haven't yet hit 0x7FFF; the fire check is then
        // inside the same step (matches Mesen2's `ProcessCpuClock`).
        if ((irqCounter and 0x8000) != 0 && (irqCounter and 0x7FFF) != 0x7FFF) {
            irqCounter++
            if ((irqCounter and 0x7FFF) == 0x7FFF) {
                irqPending = true
            }
        }
    }

    override fun isIrqPending(): Boolean = irqPending
    override fun acknowledgeIrq() {
        // $5000 / $5800 writes are what clear the IRQ in the real chip —
        // CPU's implicit ACK (BRK / RTI) doesn't. See Mapper.kt's comment
        // for why we don't no-op this on mappers that have explicit ack
        // registers; in N163 the ack path is the address writes, and the
        // `MapRead`'s IRQ-vector fetch doesn't need a separate signal.
    }

    // ---- Snapshot ----------------------------------------------------------

    override fun snapshot(): MapperStateSnapshot {
        val banks = mutableMapOf(
            "prgBank0" to prgBank0,
            "prgBank1" to prgBank1,
            "prgBank2" to prgBank2
        )
        for (i in 0 until 12) banks["chrBank$i"] = chrBanks[i]
        return MapperStateSnapshot(
            mapperId = 19,
            type = "Namco 163",
            banks = banks,
            registers = mapOf(
                "lowChrNtMode" to if (lowChrNtMode) 1 else 0,
                "highChrNtMode" to if (highChrNtMode) 1 else 0,
                "prgRamGlobalWriteEnable" to if (prgRamGlobalWriteEnable) 1 else 0,
                "prgRamPageWriteProtectMask" to prgRamPageWriteProtectMask
            ),
            irqState = mapOf(
                "irqCounter" to irqCounter,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = prgRam.copyOf()
        )
    }

    // ---- Save state --------------------------------------------------------

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(prgBank0)
        out.writeInt(prgBank1)
        out.writeInt(prgBank2)
        for (b in chrBanks) out.writeInt(b)
        out.writeBoolean(lowChrNtMode)
        out.writeBoolean(highChrNtMode)
        out.writeInt(irqCounter)
        out.writeBoolean(irqPending)
        out.writeBoolean(prgRamGlobalWriteEnable)
        out.writeInt(prgRamPageWriteProtectMask)
        out.write(prgRam)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
        audio.saveState(out)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        prgBank0 = input.readInt()
        prgBank1 = input.readInt()
        prgBank2 = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        lowChrNtMode = input.readBoolean()
        highChrNtMode = input.readBoolean()
        irqCounter = input.readInt()
        irqPending = input.readBoolean()
        prgRamGlobalWriteEnable = input.readBoolean()
        prgRamPageWriteProtectMask = input.readInt()
        input.readFully(prgRam)
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
        audio.loadState(input)
    }
}
