package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 69 (Sunsoft FME-7 / Sunsoft 5A / 5B).
 *
 * The FME-7 uses a two-register command/parameter protocol:
 *   - Command register  ($8000-$9FFF): low 4 bits select which command to run.
 *   - Parameter register ($A000-$BFFF): writing here invokes the selected command.
 *
 * Commands:
 *   $0-$7  CHR bank for one of the eight 1KB windows ($0000-$1FFF).
 *   $8     $6000-$7FFF bank: bits 0-5 bank, bit 6 = RAM(1)/ROM(0), bit 7 = RAM enable.
 *   $9-$B  8KB PRG banks for $8000, $A000, $C000 (bits 0-5). $E000 is fixed to the last bank.
 *   $C     Nametable mirroring: 0=Vertical, 1=Horizontal, 2=1-screen lower, 3=1-screen upper.
 *   $D     IRQ control: bit 7 = counter-enable (decrement), bit 0 = IRQ-enable (assert line).
 *          Any write to $D also acknowledges a pending IRQ.
 *   $E     IRQ counter low byte.
 *   $F     IRQ counter high byte.
 *
 * The differentiator versus MMC3 is the IRQ: a 16-bit counter decremented once per
 * CPU (M2) cycle (see [tickCpuCycle]). When it decrements from $0000 to $FFFF an IRQ
 * is generated (if IRQ-enable is set). Games use this for cycle-precise raster splits.
 *
 * The 5B audio variant ($C000-$FFFF) is out of scope for this mapper and ignored.
 *
 * Games: Gimmick! (Mr. Gimmick), Batman: Return of the Joker, Gremlins 2, etc.
 */
class Mapper69(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrRam: ByteArray? = if (chrRom.isEmpty()) ByteArray(0x2000) else null

    // 8KB PRG bank count; $E000-$FFFF is fixed to the last one.
    private val prgBankCount = programRom.size / 0x2000

    // The command currently latched via $8000-$9FFF.
    private var command = 0

    // CHR: eight 1KB banks ($0000-$1FFF).
    private val chrBanks = IntArray(8)

    // PRG: three switchable 8KB banks at $8000 / $A000 / $C000 (command $9/$A/$B).
    private val prgBanks = IntArray(3)

    // $6000-$7FFF (command $8).
    private var prg6000Bank = 0       // ROM bank when prgRamSelected == false
    private var prgRamSelected = false  // bit 6: RAM(true)/ROM(false)
    private var prgRamEnabled = false   // bit 7: RAM mapped (true) / open bus (false)
    private val prgRam = ByteArray(0x2000)

    // Mirroring override from command $C (null until the game sets it -> fall back to header).
    private var mirroringMode: Int? = null

    // IRQ (command $D/$E/$F).
    private var irqCounter = 0           // 16-bit
    private var irqCounterEnable = false // bit 7 of $D: counter decrements while set
    private var irqEnable = false        // bit 0 of $D: allow the line to assert on underflow
    private var irqPending = false

    override fun tickCpuCycle() {
        if (!irqCounterEnable) return
        // IRQ fires on the $0000 -> $FFFF underflow.
        if (irqCounter == 0 && irqEnable) irqPending = true
        irqCounter = (irqCounter - 1) and 0xFFFF
    }

    override fun isIrqPending(): Boolean = irqPending

    override fun cpuRead(address: Int): Byte {
        return when {
            address in 0x6000..0x7FFF -> {
                if (prgRamSelected) {
                    if (prgRamEnabled) prgRam[address - 0x6000] else 0  // disabled RAM = open bus
                } else {
                    programRom[(prg6000Bank * 0x2000 + (address - 0x6000)) % programRom.size]
                }
            }
            address in 0x8000..0x9FFF -> programRom[(prgBanks[0] * 0x2000 + (address - 0x8000)) % programRom.size]
            address in 0xA000..0xBFFF -> programRom[(prgBanks[1] * 0x2000 + (address - 0xA000)) % programRom.size]
            address in 0xC000..0xDFFF -> programRom[(prgBanks[2] * 0x2000 + (address - 0xC000)) % programRom.size]
            address in 0xE000..0xFFFF -> programRom[((prgBankCount - 1) * 0x2000 + (address - 0xE000)) % programRom.size]
            else -> 0
        }
    }

    override fun cpuWrite(address: Int, value: Byte) {
        val v = value.toUnsignedInt()
        when {
            address in 0x6000..0x7FFF -> {
                if (prgRamSelected && prgRamEnabled) prgRam[address - 0x6000] = value
            }
            address in 0x8000..0x9FFF -> command = v and 0x0F
            address in 0xA000..0xBFFF -> writeParameter(v)
            // $C000-$FFFF: 5B expansion audio (out of scope) — ignored.
        }
    }

    private fun writeParameter(v: Int) {
        when (command) {
            in 0..7 -> chrBanks[command] = v
            0x8 -> {
                prg6000Bank = v and 0x3F
                prgRamSelected = (v and 0x40) != 0
                prgRamEnabled = (v and 0x80) != 0
            }
            0x9 -> prgBanks[0] = v and 0x3F
            0xA -> prgBanks[1] = v and 0x3F
            0xB -> prgBanks[2] = v and 0x3F
            0xC -> mirroringMode = v and 0x03
            0xD -> {
                // $D bits are live, not sticky: bit 7 enables/halts the counter,
                // bit 0 enables/masks the IRQ line, and any write to $D acknowledges
                // a pending IRQ. (A prior "sticky latch" workaround for Mr. Gimmick
                // (Europe) — issue #82 — was removed once the real cause was found:
                // Gimmick's boot is driven by its NMI handler, which fires ZERO IRQs
                // during boot, verified against Mesen2. The hang was the PPU clearing
                // only PPUSTATUS bit 7 — not the CPU-visible nmiOccurred latch — at
                // the pre-render scanline, so a mid-frame "enable NMI" write fired a
                // spurious immediate NMI. See PpuAddressedMemory.clearVBlankAtPreRender.)
                irqCounterEnable = (v and 0x80) != 0
                irqEnable = (v and 0x01) != 0
                irqPending = false
            }
            0xE -> irqCounter = (irqCounter and 0xFF00) or v
            0xF -> irqCounter = (irqCounter and 0x00FF) or (v shl 8)
        }
    }

    override fun ppuRead(address: Int): Byte {
        val a = address and 0x1FFF
        if (chrRom.isEmpty()) return chrRam!![a]
        val bank = chrBanks[a shr 10]   // 1KB windows: bits 10-12 pick the window 0-7
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
        out.writeInt(command)
        for (b in chrBanks) out.writeInt(b)
        for (b in prgBanks) out.writeInt(b)
        out.writeInt(prg6000Bank)
        out.writeBoolean(prgRamSelected)
        out.writeBoolean(prgRamEnabled)
        out.write(prgRam)
        out.writeInt(mirroringMode ?: -1)
        out.writeInt(irqCounter)
        out.writeBoolean(irqCounterEnable)
        out.writeBoolean(irqEnable)
        out.writeBoolean(irqPending)
        out.writeBoolean(chrRam != null)
        if (chrRam != null) out.write(chrRam)
    }

    override fun loadState(input: DataInput) {
        command = input.readInt()
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        for (i in prgBanks.indices) prgBanks[i] = input.readInt()
        prg6000Bank = input.readInt()
        prgRamSelected = input.readBoolean()
        prgRamEnabled = input.readBoolean()
        input.readFully(prgRam)
        val mirror = input.readInt()
        mirroringMode = if (mirror < 0) null else mirror
        irqCounter = input.readInt()
        irqCounterEnable = input.readBoolean()
        irqEnable = input.readBoolean()
        irqPending = input.readBoolean()
        val hasChrRam = input.readBoolean()
        if (hasChrRam && chrRam != null) input.readFully(chrRam)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 69,
            type = "Sunsoft FME-7",
            banks = mapOf(
                "prg6000" to prg6000Bank,
                "prg8000" to prgBanks[0],
                "prgA000" to prgBanks[1],
                "prgC000" to prgBanks[2],
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
                "command" to command,
                "prgRamSelected" to if (prgRamSelected) 1 else 0,
                "prgRamEnabled" to if (prgRamEnabled) 1 else 0,
                "mirroring" to (mirroringMode ?: -1)
            ),
            irqState = mapOf(
                "irqCounter" to irqCounter,
                "irqCounterEnable" to if (irqCounterEnable) 1 else 0,
                "irqEnable" to if (irqEnable) 1 else 0,
                "irqPending" to if (irqPending) 1 else 0
            ),
            chrRam = chrRam?.copyOf(),
            prgRam = prgRam.copyOf()
        )
    }
}
