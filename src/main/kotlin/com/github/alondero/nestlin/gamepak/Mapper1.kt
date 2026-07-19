package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 1 (MMC1) - Nintendo's first bank-switching mapper.
 *
 * Uses a 5-bit shift register for bank configuration.
 * Supports PRG ROM bank switching (16KB or 32KB modes) and CHR bank switching.
 */
class Mapper1(private val gamePak: GamePak) : Mapper {

    private var shiftReg = 0x10   // marker bit at bit 4
    // Control register initialized from header mirroring at power-on:
    // bits 0-1 = mirroring, bit 2-3 = PRG mode (3 = fixed at 0xC000), bit 4 = CHR mode (0 = 8KB)
    private var controlReg = (when (gamePak.header.mirroring) {
        Header.Mirroring.HORIZONTAL -> 0x0D   // 0b01101 = horizontal + PRG mode 3 + CHR mode 0
        Header.Mirroring.VERTICAL -> 0x0E     // 0b01110 = vertical + PRG mode 3 + CHR mode 0
    }).toByte()
    private var chrBank0 = 0
    private var chrBank1 = 0
    private var prgBank = 0

    // MMC1 serial-port consecutive-write guard (issue #235). tickCpuCycle()
    // advances cpuCycle once per CPU (M2) cycle; each serial-port write stamps
    // its cycle in lastSerialWriteCycle. A data write landing within one CPU
    // cycle of the previous serial write is the 6502 read-modify-write
    // dummy/real write pair, which real MMC1 shifts only once — see cpuWrite.
    // These are transient (not save-state persisted): save states are taken at
    // instruction boundaries, never mid-RMW, so resetting them on load is safe.
    private var cpuCycle = 0L
    private var lastSerialWriteCycle = NO_SERIAL_WRITE

    // CHR bus: backed by ROM when present, otherwise 8KB of CHR-RAM.
    private val chrMemory: ChrMemory = ChrMemory.default(gamePak.chrRom)
    // Held for save-state/snapshot compatibility (the snapshot field exposes
    // the backing buffer; the per-mapper save code reads it directly).
    private val chrRom = gamePak.chrRom

    private val programRom = gamePak.programRom
    private val prgBankCount = programRom.size / 0x4000

    // PRG RAM ($6000-$7FFF)
    private val prgRam = ByteArray(0x2000)
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            return prgRam[address - 0x6000]
        }
        if (address < 0x8000) return 0

        val prgMode = controlReg.toUnsignedInt() shr 2 and 0x03
        val lowWindow = address < 0xC000
        val offset = if (lowWindow) address - 0x8000 else address - 0xC000

        // SUROM/SXROM: 512KB boards address only 256KB with the 4-bit PRG register;
        // the active 256KB half (PRG A18) is selected by bit 4 of the CHR bank-0
        // register. The outer bank applies to the switchable AND the fixed windows,
        // and the fixed "last bank" is the last 16KB of the *current* half. Guarded
        // on PRG size so ordinary <=256KB games (whose CHR bit 4 legitimately selects
        // a CHR bank) are unaffected. See nesdev "MMC1" — SUROM/SXROM.
        val is512k = prgBankCount > 16
        val outer = if (is512k) (chrBank0 and 0x10) else 0   // 16KB-bank units: 0 or 16
        val prgLow = prgBank and 0x0F

        val bank = when (prgMode) {
            0, 1 -> {
                // 32KB mode: both 16KB windows switch together (low bit ignored).
                val base = outer or (prgBank and 0x0E)
                if (lowWindow) base else base + 1
            }
            2 -> {
                // Mode 2: $8000-$BFFF fixed to the first bank of the half, $C000 switchable.
                if (lowWindow) outer else (outer or prgLow)
            }
            else /* 3 */ -> {
                // Mode 3 (default): $8000 switchable, $C000 fixed to the last bank
                // of the current half (or the whole-ROM last bank on <=256KB boards).
                if (lowWindow) (outer or prgLow)
                else if (is512k) (outer or 0x0F) else (prgBankCount - 1)
            }
        }
        return programRom[(bank * 0x4000 + offset) % programRom.size]
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x6000..0x7FFF) {
            prgRam[address - 0x6000] = value
            batteryDirty = true
            return
        }
        if (address < 0x8000) return

        // MMC1's serial port ignores a data write that lands within one CPU
        // cycle of the previous serial-port write. The 6502 executes
        // read-modify-write instructions (INC/DEC/ASL/ROR/...) as a dummy write
        // of the unmodified byte followed by the real write on the very next
        // cycle; real MMC1 shifts only the first, so without this guard the
        // second write corrupts the 5-bit shift register (e.g. Bill & Ted).
        // The bit-7 reset is NEVER ignored — matches Mesen2 MMC1.h / nesdev and
        // fixes Shinsenden. The cycle stamp is updated unconditionally.
        val isReset = value.toUnsignedInt() and 0x80 != 0
        val consecutive = lastSerialWriteCycle != NO_SERIAL_WRITE &&
            (cpuCycle - lastSerialWriteCycle) < 2L
        lastSerialWriteCycle = cpuCycle
        if (consecutive && !isReset) return

        // MMC1 uses a 5-bit shift register protocol
        // Bit 7 of the value controls whether this is a "reset" write
        if (isReset) {
            // Bit 7 set: reset shift register and force PRG mode 3
            shiftReg = 0x10
            controlReg = (controlReg.toUnsignedInt() or 0x0C).toByte()
            return
        }

        // Check if we're completing a 5-write sequence
        val completing = (shiftReg and 0x01) != 0

        // Shift right and insert new bit at bit 4
        shiftReg = (shiftReg shr 1) or ((value.toUnsignedInt() and 0x01) shl 4)

        if (completing) {
            // Write completed - update the appropriate register
            when ((address shr 13) and 0x03) {
                0 -> controlReg = (shiftReg and 0x1F).toByte()       // $8000-$9FFF: Control register
                1 -> chrBank0 = shiftReg and 0x1F        // $A000-$BFFF: CHR bank 0
                2 -> chrBank1 = shiftReg and 0x1F        // $C000-$DFFF: CHR bank 1
                3 -> prgBank = shiftReg and 0x1F         // $E000-$FFFF: PRG bank
            }
            shiftReg = 0x10  // Reset to marker value
        }
    }

    /** Advances the serial-port cycle clock; see the consecutive-write guard in [cpuWrite]. */
    override fun tickCpuCycle() {
        cpuCycle++
    }

    override fun ppuRead(address: Int): Byte {
        if (chrRom.isEmpty()) {
            return chrMemory.read(address and 0x1FFF)
        }
        // CHR banking mode
        val chrMode = if (controlReg.isBitSet(4)) 1 else 0
        return if (chrMode == 0) {
            // 8KB CHR mode: both 4KB banks point to the same 8KB region
            val bankBase = (chrBank0 and 0x1E)
            val offset = address and 0x1FFF
            if (offset < 0x1000) {
                chrRom[(bankBase * 0x1000 + offset) % chrRom.size]
            } else {
                chrRom[(((bankBase + 1) * 0x1000) + (offset - 0x1000)) % chrRom.size]
            }
        } else {
            // 4KB CHR mode: each bank is independent
            if (address < 0x1000) {
                chrRom[(chrBank0 * 0x1000 + address) % chrRom.size]
            } else {
                chrRom[(chrBank1 * 0x1000 + (address - 0x1000)) % chrRom.size]
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
        return when (controlReg.toUnsignedInt() and 0x03) {
            0 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
            1 -> Mapper.MirroringMode.ONE_SCREEN_UPPER
            2 -> Mapper.MirroringMode.VERTICAL
            else -> Mapper.MirroringMode.HORIZONTAL
        }
    }

    override val saveStateVersion: Int = 2

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        out.writeInt(shiftReg)
        out.writeByte(controlReg.toInt())
        out.writeInt(chrBank0)
        out.writeInt(chrBank1)
        out.writeInt(prgBank)
        chrMemory.serialize(out)
        out.write(prgRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        shiftReg = input.readInt()
        controlReg = input.readByte()
        chrBank0 = input.readInt()
        chrBank1 = input.readInt()
        prgBank = input.readInt()
        chrMemory.deserialize(input)
        input.readFully(prgRam)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 1,
            type = "MMC1",
            banks = mapOf(
                "prgBank" to prgBank,
                "chrBank0" to chrBank0,
                "chrBank1" to chrBank1
            ),
            registers = mapOf(
                "controlReg" to controlReg.toUnsignedInt(),
                "shiftReg" to shiftReg
            ),
            irqState = null,
            // Snapshot chrRam for debug display.
            chrRam = chrMemory.snapshotBytes(),
            prgRam = prgRam.copyOf()
        )
    }

    private companion object {
        /** Sentinel: no serial-port write has happened yet (guards against a
         *  spurious "consecutive" verdict on the very first write). */
        const val NO_SERIAL_WRITE = Long.MIN_VALUE
    }
}
