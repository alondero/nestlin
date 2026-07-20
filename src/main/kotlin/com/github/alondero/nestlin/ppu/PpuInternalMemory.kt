package com.github.alondero.nestlin.ppu

import java.io.DataInput
import java.io.DataOutput

class PpuInternalMemory {

    private val patternTable0 = ByteArray(0x1000)
    private val patternTable1 = ByteArray(0x1000)
    private val nameTable0 = ByteArray(0x400)
    private val nameTable1 = ByteArray(0x400)
    private val paletteRam = PaletteRam()

    var mirroring = Mirroring.HORIZONTAL

    // Delegate functions for dynamic CHR banking (set by mapper)
    var chrReadDelegate: ((Int) -> Byte)? = null
    var chrWriteDelegate: ((Int, Byte) -> Unit)? = null

    // Optional nametable override ($2000-$2FFF). When a mapper (e.g. Mapper 19
    // CHR-as-nametable mode) wants to redirect a nametable read/write to its own
    // backing — typically because the chip's internal CHR-RAM is doubling as NT
    // RAM — it installs these and returns `true` / non-null to claim the access.
    // null / false means "fall through to the standard CIRAM-backed read".
    //
    // The split between read & write mirrors chrReadDelegate / chrWriteDelegate
    // so the mapper's `ppuRead` (which only covers $0000-$1FFF CHR) can co-exist
    // with the mapper's `readNametableOverride` (which covers $2000-$2FFF) —
    // both delegate paths converge in this class.
    //
    // The READ lambda returns `Byte?` (nullable) so the mapper can signal
    // "I don't own this address" by returning null — the caller's `?: run {...}`
    // then falls through to the standard CIRAM mirroring. A non-null Byte is
    // the mapper's authoritative read result.
    var nametableReadDelegate: ((Int) -> Byte?)? = null
    var nametableWriteDelegate: ((Int, Byte) -> Boolean)? = null

    // A12 edge detection for MMC3 scanline IRQ
    private var lastA12High: Boolean = false
    // Tracks how many M2 (CPU) cycles A12 has been continuously low
    // Real MMC3 requires A12 to be low for 3+ M2 cycles before accepting a rising edge
    private var m2CyclesSinceA12Low: Int = 0
    var a12EdgeListener: ((Boolean) -> Unit)? = null

    private fun emitA12(addr: Int) {
        val high = (addr and 0x1000) != 0
        if (high != lastA12High) {
            if (!high) {
                // A12 transitioned to low - start counting M2 cycles
                m2CyclesSinceA12Low = 0
            } else {
                // A12 transitioned to high (rising edge) - only fire if A12 was low for 3+ M2 cycles
                // Per NESdev wiki: "triggered on a rising edge after the line has remained low for
                // three falling edges of M2"
                if (m2CyclesSinceA12Low >= 3) {
                    a12EdgeListener?.invoke(true)
                }
                m2CyclesSinceA12Low = 0
            }
            lastA12High = high
        }
        if (!high) {
            m2CyclesSinceA12Low++
        }
    }

    fun resetA12State() {
        lastA12High = false
        m2CyclesSinceA12Low = 0
    }

    enum class Mirroring {
        HORIZONTAL,
        VERTICAL,
        ONE_SCREEN_LOWER,
        ONE_SCREEN_UPPER
    }

    private fun mapNametableAddress(addr: Int): Pair<ByteArray, Int> {
        val normalizedAddr = (addr - 0x2000) % 0x1000
        val tableIndex = when (mirroring) {
            Mirroring.HORIZONTAL -> {
                // Horizontal mirroring: $2000/$2400 -> NT0, $2800/$2C00 -> NT1
                // Check bit 11 of normalized address (the 0x800 bit)
                if ((normalizedAddr and 0x800) != 0) 1 else 0
            }
            Mirroring.VERTICAL -> {
                // Vertical mirroring: $2000/$2400 -> NT0, $2800/$2C00 -> NT1
                // This means CIRAM A10 = PPU A10
                (normalizedAddr / 0x400) % 2
            }
            Mirroring.ONE_SCREEN_LOWER -> 0
            Mirroring.ONE_SCREEN_UPPER -> 1
        }
        val table = if (tableIndex == 0) nameTable0 else nameTable1
        return Pair(table, addr % 0x400)
    }

    operator fun get(addr: Int): Byte = when (addr) {
        in 0x0000..0x0FFF -> {
            emitA12(addr)
            chrReadDelegate?.invoke(addr) ?: patternTable0[addr]
        }
        in 0x1000..0x1FFF -> {
            emitA12(addr)
            chrReadDelegate?.invoke(addr) ?: patternTable1[addr - 0x1000]
        }
        in 0x2000..0x2FFF -> {
            emitA12(addr)
            // Nametable override: mappers that own part of the nametable area
            // (e.g. Mapper 19's CHR-as-NT mode) return non-null to claim the
            // read. See `Mapper.readNametableOverride`.
            nametableReadDelegate?.invoke(addr) ?: run {
                val (table, offset) = mapNametableAddress(addr)
                table[offset]
            }
        }
        in 0x3000..0x3EFF -> this[addr - 0x1000] // Mirror of 0x2000 - 0x2EFF
        else /*in 0x3F00..0x3FFF*/ -> paletteRam[addr % 0x020]
    }

    operator fun set(addr: Int, value: Byte) {
        when (addr) {
            // Pattern tables (0x0000-0x1FFF): delegate to mapper for CHR banking
            in 0x0000..0x0FFF -> {
                emitA12(addr)
                chrWriteDelegate?.invoke(addr, value) ?: Unit
            }
            in 0x1000..0x1FFF -> {
                emitA12(addr)
                chrWriteDelegate?.invoke(addr, value) ?: Unit
            }
            in 0x2000..0x2FFF -> {
                emitA12(addr)
                // Mirror of the read path — see `Mapper.writeNametableOverride`.
                // Returning `true` from the delegate consumes the write (no
                // fall-through to the standard CIRAM mirroring); `false` (or a
                // null delegate) lets the standard table/offset write happen.
                if (nametableWriteDelegate?.invoke(addr, value) != true) {
                    val (table, offset) = mapNametableAddress(addr)
                    table[offset] = value
                }
            }
            in 0x3000..0x3EFF -> this[addr - 0x1000] = value // Mirror of 0x2000 - 0x2EFF
            else /*in 0x3F00..0x3FFF*/ -> paletteRam[addr % 0x020] = value
        }
    }

    /**
     * Load CHR ROM data into pattern tables.
     * CHR ROM contains tile graphics data.
     * $0000-$0FFF: Pattern table 0
     * $1000-$1FFF: Pattern table 1
     *
     * For cartridges with small CHR ROMs (8KB or less), the CHR ROM is mirrored
     * across both pattern tables (both tables see the same data).
     * For cartridges with 16KB+ CHR ROM, pattern table 0 and 1 are separate.
     */
    fun loadChrRom(chrRom: ByteArray) {
        if (chrRom.isEmpty()) return

        // Load pattern table 0 ($0000-$0FFF)
        val table0Size = minOf(0x1000, chrRom.size)
        chrRom.copyInto(
            destination = patternTable0,
            destinationOffset = 0,
            startIndex = 0,
            endIndex = table0Size
        )

        // For pattern table 1 ($1000-$1FFF):
        // - Mapper 0 (NROM): CHR ROM is fixed at boot, never swappable
        // - 4KB CHR: mirror to both tables (duplicate data)
        // - 8KB CHR: ENTIRE 8KB ROM mirrors to both PT0 and PT1 (NROM behavior)
        //           Load the same first 4KB to pattern table 1
        //           (CTRL bit 4 doesn't swap banks; both tables always see same data)
        // - 16KB+: split across two pattern tables

        if (chrRom.size < 0x2000) {
            // 4KB, 8KB or less: mirror pattern table 0 data to pattern table 1
            // The entire CHR ROM repeats across both pattern tables
            patternTable0.copyInto(patternTable1)
        } else {
            // 16KB+: load second half of CHR ROM to pattern table 1
            val table1Size = minOf(0x1000, chrRom.size - 0x1000)
            chrRom.copyInto(
                destination = patternTable1,
                destinationOffset = 0,
                startIndex = 0x1000,
                endIndex = 0x1000 + table1Size
            )
        }
    }

    fun saveState(out: DataOutput) {
        // Pattern tables: only matter when no chrReadDelegate (Mapper 0 NROM with built-in CHR ROM
        // copied here at load). For mapper-owned CHR ROM/RAM the mapper persists its own copy.
        // Save them anyway for symmetry & to ride out future mappers that fall back to these.
        out.write(patternTable0)
        out.write(patternTable1)
        out.write(nameTable0)
        out.write(nameTable1)
        paletteRam.saveState(out)
        out.writeInt(mirroring.ordinal)
        out.writeBoolean(lastA12High)
        out.writeInt(m2CyclesSinceA12Low)
    }

    fun loadState(input: DataInput) {
        input.readFully(patternTable0)
        input.readFully(patternTable1)
        input.readFully(nameTable0)
        input.readFully(nameTable1)
        paletteRam.loadState(input)
        mirroring = Mirroring.values()[input.readInt()]
        lastA12High = input.readBoolean()
        m2CyclesSinceA12Low = input.readInt()
    }
}

class PaletteRam {
    // RP2C02G power-on contents documented by NESdev and exposed by Mesen2.
    private val memory = byteArrayOf(
        0x09, 0x01, 0x00, 0x01, 0x00, 0x02, 0x02, 0x0D,
        0x08, 0x10, 0x08, 0x24, 0x00, 0x00, 0x04, 0x2C,
        0x09, 0x01, 0x34, 0x03, 0x00, 0x04, 0x00, 0x14,
        0x08, 0x3A, 0x00, 0x02, 0x00, 0x20, 0x2C, 0x08
    )

    operator fun get(addr: Int): Byte {
        val index = addr and 0x1F
        // Mirror $3F10/$3F14/$3F18/$3F1C to $3F00/$3F04/$3F08/$3F0C
        val mirroredIndex = if (index and 0x13 == 0x10) index and 0x0F else index
        return memory[mirroredIndex]
    }

    operator fun set(addr: Int, value: Byte) {
        val index = addr and 0x1F
        // Mirror $3F10/$3F14/$3F18/$3F1C to $3F00/$3F04/$3F08/$3F0C
        val mirroredIndex = if (index and 0x13 == 0x10) index and 0x0F else index
        memory[mirroredIndex] = value
    }

    fun saveState(out: DataOutput) { out.write(memory) }
    fun loadState(input: DataInput) { input.readFully(memory) }
}
