package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.gamepak.Mapper
import com.github.alondero.nestlin.ppu.PpuAddressedMemory
import com.github.alondero.nestlin.apu.ApuAddressedMemory
import com.github.alondero.nestlin.apu.DmaPort
import java.io.DataInput
import java.io.DataOutput

class Memory : DmaPort {
    private val internalRam = ByteArray(0x800)
    val ppuAddressedMemory = PpuAddressedMemory()
    val apuAddressedMemory = ApuAddressedMemory()

    val controller1 = Controller()
    val controller2 = Controller()

    // Will be set by Nestlin after APU creation
    var apu: Apu? = null

    // Will be set by Nestlin after CPU creation. Memory needs this back-reference
    // so it can halt the CPU for the 513 cycles an OAM DMA takes (NESdev: the CPU
    // is suspended for the duration of the transfer). Without it, every DMA would
    // let the CPU "skip ahead" by 513 cycles — a per-frame drift that desyncs games
    // from Mesen2 in as few as ~5 frames of OAM-DMA-heavy sprite work.
    var cpu: com.github.alondero.nestlin.cpu.Cpu? = null

    // Mapper for cartridge bank switching (set during readCartridge)
    var mapper: Mapper? = null

    /**
     * Last byte driven on the 6502 data bus. Tracked globally across
     * every CPU read OR write (regardless of which component handled
     * the access), so open-bus reads — addresses the mapper has no byte
     * of its own to return, e.g. $4020-$5FFF on MMC3, $6000-$7FFF on
     * chips without PRG-RAM — can return the value a real 6502 would
     * see. Without this, games that rely on the 6502's data-bus open-bus
     * behaviour diverge from Mesen2 at boot (Klax's IRQ-driven bonus
     * timer, Mind Blower Pak's reset vector trampoline, etc.).
     *
     * Updated at the END of every get/set so the value a caller sees is
     * the value that was actually returned / written. Set on the mapper
     * (via [Mapper.dataBus]) just before `cpuRead` so open-bus paths
     * in the mapper can read it.
     */
    var dataBus: Byte = 0

    fun readCartridge(data: GamePak) {
        val m = data.createMapper()
        mapper = m

        // Drop any prior cart's mapper-side audio channels (Issue #50) before
        // wiring this cart's. Without the clear, swapping a Mapper-24 ROM out
        // for a Mapper-0 ROM would leave the previous VRC6 voices ticking
        // against the silent APU.
        apu?.clearExpansionChannels()
        m.expansionAudioChannels().forEach { apu?.registerExpansionChannel(it) }

        // Wire CHR banking delegates to the mapper. PPU CHR reads also
        // update the system data-bus, since the PPU drives the shared
        // 6502 bus on its cycles. Without this, a CPU open-bus read at
        // $4020-$7FFF (e.g. Klax's $6000 polling) would see whatever the
        // last CPU access was, not the PPU's last CHR byte — diverging
        // from real hardware. Klax specifically relies on this for its
        // boot sequence.
        ppuAddressedMemory.ppuInternalMemory.chrReadDelegate = { addr ->
            val result = m.ppuRead(addr)
            dataBus = result
            result
        }
        ppuAddressedMemory.ppuInternalMemory.chrWriteDelegate = { addr, v -> m.ppuWrite(addr, v) }

        // Wire A12 edge detection for MMC3 scanline IRQ
        ppuAddressedMemory.ppuInternalMemory.a12EdgeListener = { rising -> m.notifyA12Edge(rising) }
        ppuAddressedMemory.ppuInternalMemory.resetA12State()

        // Load CHR ROM into PPU pattern tables for initial tiles
        ppuAddressedMemory.ppuInternalMemory.loadChrRom(data.chrRom)

        // Apply initial mirroring from mapper
        applyMirroringFromMapper(m)
    }

    /** Re-applies the current mapper's mirroring to the PPU. Called after save state restore. */
    fun syncMirroringFromMapper() {
        mapper?.let { applyMirroringFromMapper(it) }
    }

    fun saveRamState(out: DataOutput) {
        out.write(internalRam)
    }

    fun loadRamState(input: DataInput) {
        input.readFully(internalRam)
    }

    private fun applyMirroringFromMapper(m: Mapper) {
        ppuAddressedMemory.ppuInternalMemory.mirroring = when (m.currentMirroring()) {
            Mapper.MirroringMode.HORIZONTAL -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.HORIZONTAL
            Mapper.MirroringMode.VERTICAL -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.VERTICAL
            Mapper.MirroringMode.ONE_SCREEN_LOWER -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.ONE_SCREEN_LOWER
            Mapper.MirroringMode.ONE_SCREEN_UPPER -> com.github.alondero.nestlin.ppu.PpuInternalMemory.Mirroring.ONE_SCREEN_UPPER
        }
    }

    operator fun set(address: Int, value: Byte) {
        when (address) {
            in 0x0000..0x1FFF -> internalRam[address%0x800] = value
            in 0x2000..0x3FFF -> ppuAddressedMemory[address%8] = value
            0x4016 -> {
                controller1.write(value)
                controller2.write(value)
            }
            0x4014 -> {
                val base = value.toUnsignedInt() shl 8
                // NESdev: writing $4014 sets OAMADDR ($2003) to 0 at the start of
                // the DMA — every DMA always writes $base+0 to OAM[0], $base+1 to
                // OAM[1], etc. Without this reset, a non-zero oamAddress (e.g. from
                // the game doing manual $2004 writes to mask sprite 0 just before
                // triggering the DMA) shifts every DMA byte by that offset, so
                // $0200[0] lands in OAM[oamAddress] instead of OAM[0] and sprite 0
                // keeps the manually-written mask bytes instead of the real data.
                // This was the silent cause of the Akira (mapper 33) title→gameplay
                // freeze (issue #141): the game hides sprite 0 with 4 manual $2004
                // writes (Y=$FF, tile=0, attr=0, X=0) then runs the DMA, expecting
                // the real sprite-0 tile=$81 from the data table to land in OAM[0].
                // On Nestlin the DMA shifted by 4, leaving OAM[0] = $FF $00 $00 $00,
                // the game polled PPUSTATUS bit 6 forever, and sprite-0 hit never
                // fired because the real sprite was at OAM[1] instead of OAM[0].
                ppuAddressedMemory.oamAddress = 0
                for (i in 0 until 256) {
                    val data = this[base + i]
                    ppuAddressedMemory.writeOamData(data)
                }
                // OAM DMA halts the CPU for 513 cycles (NESdev: each of the 256
                // byte transfers is 2 PPU cycles, +1 for the align-on-write setup
                // cycle). Without this halt, every DMA "skips" 513 CPU cycles —
                // enough to desync the game from a cycle-accurate reference like
                // Mesen2 within a handful of frames of OAM-heavy sprite updates.
                // Surface diagnosed against Micro Machines (mapper 71) on
                // 2026-06-02: the 2-frame CPU-PC drift at frame 270 was the
                // downstream symptom.
                cpu?.workCyclesLeft = 513
            }
            in 0x4000..0x401F -> {
                apuAddressedMemory[address - 0x4000] = value
                apu?.handleRegisterWrite(address - 0x4000, value)
            }
            in 0x4020..0xFFFF -> {
                mapper?.cpuWrite(address, value)
                mapper?.let { applyMirroringFromMapper(it) }  // sync mirroring after each write
            }
        }
        // The 6502 drives the data bus with the value it's writing. Track
        // it globally so mappers that opt into open-bus reads (e.g. HES
        // NTD-8 / Mapper 113) can return the correct value. The
        // default Mapper implementation of `dataBus` is 0, so mappers
        // that don't override `cpuRead` get the old 0-on-open-bus behaviour.
        dataBus = value
    }

    override operator fun get(address: Int): Byte {
        val result: Byte = when (address) {
            in 0x0000..0x1FFF -> internalRam[address % 0x800]
            in 0x2000..0x3FFF -> ppuAddressedMemory[address % 8]
            0x4016 -> controller1.read()
            0x4017 -> controller2.read()
            in 0x4000..0x401F -> apu?.handleRegisterRead(address - 0x4000) ?: apuAddressedMemory[address - 0x4000]
            in 0x4020..0xFFFF -> {
                // Push the current data-bus value into the mapper BEFORE
                // calling `cpuRead`, so mappers that opt into open-bus
                // reads can return the correct value. The default Mapper
                // property is no-op, so mappers that don't override it
                // see dataBus=0 and fall back to the old 0-on-open-bus
                // behaviour. This is the minimum-blast-radius fix: only
                // mappers that EXPLICITLY want open-bus reads get them.
                mapper?.dataBus = dataBus
                mapper?.cpuRead(address) ?: 0
            }
            else -> 0
        }
        // Track the result on the data bus for the next access.
        dataBus = result
        return result
    }

    operator fun get(address1: Int, address2: Int): Short {
        val addr1 = this[address1].toUnsignedInt()
        val addr2 = this[address2].toUnsignedInt() shl 8

        return (addr2 + addr1).toSignedShort()
    }

    fun clear() {
        internalRam.fill(0xFF.toSignedByte())
        apuAddressedMemory.reset()
        ppuAddressedMemory.reset()
    }

    fun resetVector() = this[0xFFFC, 0xFFFD]
}
