package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Mapper 68 (Sunsoft-4) — also referred to as "Sunsoft 2" in some iNES
 * header conventions (NESdev wiki + Mesen2 `MapperFactory` agree this iNES
 * number maps to the Sunsoft-4 IC).
 *
 * Register layout (`addr & 0xF000` decoded):
 *   $8000-$8FFF  2KB CHR bank 0 → PPU $0000-$07FF
 *   $9000-$9FFF  2KB CHR bank 1 → PPU $0800-$0FFF
 *   $A000-$AFFF  2KB CHR bank 2 → PPU $1000-$17FF
 *   $B000-$BFFF  2KB CHR bank 3 → PPU $1800-$1FFF
 *   $C000-$CFFF  Nametable CHR reg 0 (when $E000 bit 4 is set)
 *   $D000-$DFFF  Nametable CHR reg 1 (when $E000 bit 4 is set)
 *   $E000-$EFFF  Mirroring (bits 0-1) + CHR-for-nametable mode (bit 4)
 *   $F000-$FFFF  PRG bank 0 (bits 0-2), external PRG select (bit 3),
 *                PRG-RAM enable (bit 4)
 *
 * Mirroring modes ($E000 bits 0-1):
 *   0 = Vertical, 1 = Horizontal, 2 = ScreenA only, 3 = ScreenB only
 *
 * Initial power-on state (Mesen2 Sunsoft4::InitMapper):
 *   PRG page 0 = bank 0
 *   PRG page 1 = bank (prgBankCount - 1)
 *   All CHR regs = 0; PRG-RAM disabled; CHR-for-nametable mode off
 *
 * PRG-RAM ($6000-$7FFF): 8KB, battery-backed when iNES header has the
 * battery flag set, readable only after $F000 bit 4 is asserted. Writes
 * are always accepted (writes are how the licensing-IC timer is armed).
 *
 * Licensing IC: a write to $6000-$7FFF arms a ~107,520 CPU-cycle
 * countdown during which $8000-$BFFF returns open bus. Only
 * *Nantettatte!! Baseball* actually uses this protection; the other
 * 15+ Mapper 68 titles just leave the RAM alone and read PRG normally.
 *
 * Known games: The Legend of Valkyrie, Nangoku Shounen Papuwa-kun,
 * Nantettatte!! Baseball.
 */
class Mapper68(private val gamePak: GamePak) : Mapper {

    private val programRom = gamePak.programRom
    private val chrRom = gamePak.chrRom
    private val chrMemory: ChrMemory = ChrMemory.default(chrRom)

    private val prgBankCount = programRom.size / 0x4000

    // CHR — four 2KB pages at $0000-$1FFF.
    private val chrBanks = IntArray(4)

    // Nametable CHR regs (only active when useChrForNametables). Stored
    // with bit 7 OR'd in, mirroring Mesen2's `_ntRegs[0] = value | 0x80`.
    private val ntRegs = IntArray(2)

    // PRG — page 0 switchable, page 1 fixed to the last 16KB bank on
    // power-up. `prgBank` is the effective page-0 bank (already remapped
    // through the external-PRG rule when applicable).
    private var prgBank = 0

    // $E000 control bits.
    private var mirroringMode = 0      // bits 0-1
    private var useChrForNametables = false   // bit 4

    // $F000 control bits.
    private var usingExternalRom = false
    private var prgRamEnabled = false

    // PRG-RAM ($6000-$7FFF). Battery-backed when the iNES header battery
    // flag is set; exposed via batteryBackedRam() so SaveRam persists it
    // to a .sav file in the FCEUX/Mesen format.
    private val prgRam = ByteArray(0x2000)
    override var batteryDirty: Boolean = false
    override fun batteryBackedRam(): ByteArray? = prgRam

    // Namco-style licensing IC: writes to $6000-$7FFF start a countdown.
    // While non-zero, reads in $8000-$BFFF return open bus. Counts down
    // once per CPU (M2) cycle via tickCpuCycle().
    private var licensingTimer = 0
    private val LICENSING_DURATION = 1024 * 105   // ~107,520 cycles

    // CPU data-bus value, set by Memory just before each cpuRead. Used
    // for open-bus reads at $6000-$7FFF when PRG-RAM is disabled and
    // for $8000-$BFFF while the licensing IC has $8000-$BFFF unmapped.
    override var dataBus: Byte = 0

    init {
        // Mesen2 Sunsoft4::InitMapper — page 0 = bank 0, page 1 = last bank.
        // Mesen2's comment: "Bank 0's initial state is undefined, but some
        // roms expect it to be the first page." Page 1 is fixed by the
        // 16KB windowing (cpuRead reads programRom[prgBankCount - 1]*0x4000+).
        prgBank = 0
    }

    override fun cpuRead(address: Int): Byte {
        if (address in 0x6000..0x7FFF) {
            // PRG-RAM region. Reads return RAM only when explicitly
            // enabled by $F000 bit 4; otherwise open bus.
            return if (prgRamEnabled) prgRam[address - 0x6000] else dataBus
        }
        if (address in 0x8000..0xBFFF) {
            // Page 0 of PRG. Unmapped while the licensing IC is counting;
            // remaps to the current page-0 bank (regular or external) when
            // the timer expires. Mesen2 keeps the page unmapped in the
            // external-PRG case even after expiry — that combination
            // (external PRG + licensing-IC arming) only occurs in
            // Nantettatte!! Baseball, which doesn't use external PRG mode.
            if (licensingTimer > 0) return dataBus
            val offset = address - 0x8000
            return programRom[(prgBank * 0x4000 + offset) % programRom.size]
        }
        if (address in 0xC000..0xFFFF) {
            // Page 1 is fixed to the last 16KB bank on power-up and stays
            // there — $F000 only selects page 0.
            val offset = address - 0xC000
            val bank = prgBankCount - 1
            return programRom[(bank * 0x4000 + offset) % programRom.size]
        }
        return dataBus
    }

    override fun cpuWrite(address: Int, value: Byte) {
        if (address in 0x6000..0x7FFF) {
            // Always store the write so games that DO have battery RAM
            // (Legend of Valkyrie) can use it. The licensing-IC timer
            // arms regardless — Mesen2's WriteRam arms before the base
            // class stores the byte, so writes here both arm the timer
            // AND land in RAM.
            prgRam[address - 0x6000] = value
            batteryDirty = true
            armLicensingTimer()
            return
        }
        if (address < 0x8000) return

        val v = value.toUnsignedInt()
        when (address and 0xF000) {
            0x8000 -> chrBanks[0] = v
            0x9000 -> chrBanks[1] = v
            0xA000 -> chrBanks[2] = v
            0xB000 -> chrBanks[3] = v
            0xC000 -> {
                // Bit 7 forced set per Mesen2 (_ntRegs[0] = value | 0x80).
                // This is the offset Mesen uses when mapping the 1KB
                // CHR-for-nametable page; the high bit is part of the
                // bank-index arithmetic.
                ntRegs[0] = v or 0x80
            }
            0xD000 -> ntRegs[1] = v or 0x80
            0xE000 -> {
                mirroringMode = v and 0x03
                useChrForNametables = (v and 0x10) != 0
            }
            0xF000 -> {
                // External-PRG select: bit 3 = 0 means "use the upper
                // half of an oversized PRG (bank 8..15)" if PRG > 8 banks.
                // The mapper then selects (8 | (value & 7)) modulo the
                // available banks above index 8.
                val externalPrg = (v and 0x08) == 0
                if (externalPrg && prgBankCount > 8) {
                    usingExternalRom = true
                    val external = 0x08 or ((v and 0x07) % (prgBankCount - 8))
                    prgBank = external
                } else {
                    usingExternalRom = false
                    prgBank = v and 0x07
                }
                // Bit 4 enables PRG-RAM reads at $6000-$7FFF. Writes are
                // always accepted (so the licensing timer still arms).
                prgRamEnabled = (v and 0x10) != 0
            }
        }
    }

    override fun ppuRead(address: Int): Byte {
        val masked = address and 0x1FFF
        if (chrRom.isEmpty()) {
            return chrMemory.read(masked)
        }
        // Four 2KB windows. `% chrRom.size` so out-of-range bank numbers
        // (a buggy write, or a dump with fewer CHR banks than the game's
        // code requests) wrap around instead of crashing.
        //
        // Limitation: CHR-for-nametable mode ($E000 bit 4) — which maps
        // PPU $2000-$2FFF to 1KB CHR slices via ntRegs[0..1] — is NOT
        // modelled here. Only Nantettatte!! Baseball uses it (it routes
        // its nametables through CHR-ROM for the licensing-IC overlay);
        // every other Mapper 68 game (Valkyrie, Papuwa-kun, After Burner,
        // ~15 more) leaves bit 4 clear, so the standard 2KB CHR banking
        // below is correct. See MAPPER_SUPPORT.md § Mapper 68.
        val window = masked ushr 11            // 0..3
        return chrRom[(chrBanks[window] * 0x0800 + (masked and 0x07FF)) % chrRom.size]
    }

    override fun ppuWrite(address: Int, value: Byte) {
        if (chrRom.isEmpty()) {
            chrMemory.write(address and 0x1FFF, value)
        }
        // CHR ROM is read-only.
    }

    override fun currentMirroring(): Mapper.MirroringMode = when (mirroringMode) {
        0 -> Mapper.MirroringMode.VERTICAL
        1 -> Mapper.MirroringMode.HORIZONTAL
        2 -> Mapper.MirroringMode.ONE_SCREEN_LOWER
        else -> Mapper.MirroringMode.ONE_SCREEN_UPPER
    }

    override fun tickCpuCycle() {
        if (licensingTimer <= 0) return
        licensingTimer--
        // We don't need to "remap" anything on expiry — `cpuRead` checks
        // the timer each access, so once it hits 0 the page is live again.
    }

    private fun armLicensingTimer() {
        if (licensingTimer == 0) {
            licensingTimer = LICENSING_DURATION
        }
    }

    override val saveStateVersion: Int = 1

    override fun saveState(out: DataOutput) {
        super.saveState(out)
        for (b in chrBanks) out.writeInt(b)
        out.writeInt(ntRegs[0])
        out.writeInt(ntRegs[1])
        out.writeInt(prgBank)
        out.writeInt(mirroringMode)
        out.writeBoolean(useChrForNametables)
        out.writeBoolean(usingExternalRom)
        out.writeBoolean(prgRamEnabled)
        out.writeInt(licensingTimer)
        chrMemory.serialize(out)
        out.write(prgRam)
    }

    override fun loadState(input: DataInput) {
        super.loadState(input)
        for (i in chrBanks.indices) chrBanks[i] = input.readInt()
        ntRegs[0] = input.readInt()
        ntRegs[1] = input.readInt()
        prgBank = input.readInt()
        mirroringMode = input.readInt()
        useChrForNametables = input.readBoolean()
        usingExternalRom = input.readBoolean()
        prgRamEnabled = input.readBoolean()
        licensingTimer = input.readInt()
        chrMemory.deserialize(input)
        input.readFully(prgRam)
    }

    override fun snapshot(): MapperStateSnapshot {
        return MapperStateSnapshot(
            mapperId = 68,
            type = "Sunsoft-4",
            banks = mapOf(
                "prgBank" to prgBank,
                "chrBank0" to chrBanks[0],
                "chrBank1" to chrBanks[1],
                "chrBank2" to chrBanks[2],
                "chrBank3" to chrBanks[3],
            ),
            registers = mapOf(
                "mirroringMode" to mirroringMode,
                "useChrForNametables" to if (useChrForNametables) 1 else 0,
                "usingExternalRom" to if (usingExternalRom) 1 else 0,
                "prgRamEnabled" to if (prgRamEnabled) 1 else 0,
                "licensingTimer" to licensingTimer,
                "ntReg0" to ntRegs[0],
                "ntReg1" to ntRegs[1],
            ),
            irqState = null,
            chrRam = chrMemory.snapshotBytes(),
            prgRam = prgRam.copyOf(),
        )
    }
}