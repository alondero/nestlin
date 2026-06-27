package com.github.alondero.nestlin

import com.github.alondero.nestlin.cpu.StallSource
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

    /**
     * APU back-reference for dispatching $4000-$401F register writes/reads
     * (issue #22). The two halves of Memory↔APU have a circular wiring:
     * [Apu] takes a [DmaPort] for DMC sample reads, while [Memory] needs
     * [Apu] to actually drive the channel/frame-counter state on a register
     * access. The cycle is broken by [createWithApu] — the factory builds
     * Memory first, hands it to Apu, then attaches Apu back here. Once the
     * factory returns, [apu] is non-null for the lifetime of this Memory
     * instance, so dispatch sites can read `apu.handleRegisterWrite(...)`
     * directly with no null-check.
     *
     * Tests that only touch RAM / PPU / controller / DMA regions (i.e. never
     * read or write $4000-$401F) can still `Memory()` standalone — the lateinit
     * check fires only when the field is actually read.
     */
    lateinit var apu: Apu
        private set

    /**
     * Set by the CPU during its construction so Memory's `$4014` (OAM DMA)
     * handler can request a 513-cycle CPU stall. Replaces the previous
     * `var cpu: Cpu?` back-reference (issue #190) — the interface narrows
     * the coupling to a single capability ("you may stall this CPU") so
     * Memory no longer imports or knows about `Cpu`'s internal
     * `workCyclesLeft` field.
     *
     * Why a back-reference at all: the 6502 data-bus is shared, so the CPU
     * is suspended for the duration of an OAM DMA. Without this halt every
     * DMA would let the CPU "skip ahead" by 513 cycles — a per-frame drift
     * that desyncs games from Mesen2 in as few as ~5 frames of
     * OAM-DMA-heavy sprite work. Micro Machines (mapper 71) was the canary
     * (diagnosed 2026-06-02).
     */
    var stallSource: StallSource? = null

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
        apu.clearExpansionChannels()
        m.expansionAudioChannels().forEach { apu.registerExpansionChannel(it) }

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
                //
                // Issue #190: the back-reference was narrowed from `cpu: Cpu?`
                // to `stallSource: StallSource?`. Same behaviour, narrower
                // coupling — Memory no longer reaches into `workCyclesLeft`.
                stallSource?.stallFor(513)
            }
            in 0x4000..0x401F -> {
                apuAddressedMemory[address - 0x4000] = value
                apu.handleRegisterWrite(address - 0x4000, value)
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
            in 0x4000..0x401F -> apu.handleRegisterRead(address - 0x4000)
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

    /**
     * Side-effect-free read of any CPU bus address (issue #168, Memory Editor).
     *
     * Returns the value [get] would return for ordinary backed storage, but
     * triggers NONE of the read side effects that make [get] unsafe to call from a
     * debug viewer:
     *  - PPU `$2002`/`$2007`: no vblank clear, no write-toggle reset, no VRAM increment;
     *  - APU `$4015`: no IRQ acknowledge;
     *  - controller `$4016`/`$4017`: no shift-register advance;
     *  - and crucially [dataBus] is NOT updated, so an open-bus mapper read on the
     *    real CPU path still sees the last genuine CPU access, not a peek.
     *
     * One consequence of not touching [dataBus]: for the few mappers that emulate
     * open-bus reads (returning their own latched bus value), peek reports that
     * stale latch rather than re-deriving the bus the way [get] does. Open-bus is
     * undefined anyway, so a viewer showing the last-driven value is acceptable —
     * but peek is exact only for genuinely backed addresses.
     *
     * Thread safety: callers (the Memory Editor's 10 Hz UI timer) read this
     * unsynchronised while the emulation thread mutates the same storage. Single
     * `ByteArray` element reads are atomic per the JVM spec; a cosmetic torn read
     * across two bytes is acceptable for a human-facing display (see ADR-0001).
     */
    fun peek(address: Int): Byte = when (address) {
        in 0x0000..0x1FFF -> internalRam[address % 0x800]
        in 0x2000..0x3FFF -> ppuAddressedMemory.peek(address % 8)
        0x4016 -> controller1.peek()
        0x4017 -> controller2.peek()
        in 0x4000..0x401F -> apu.peekRegisterRead(address - 0x4000)
        in 0x4020..0xFFFF -> mapper?.cpuPeek(address) ?: 0
        else -> 0
    }

    /**
     * Write a byte to any CPU bus address from the Memory Editor (issue #170).
     *
     * Unlike [peek] (deliberately side-effect-free), poke is a *real* write: it
     * delegates to [set] so the running game sees the new value on its very next
     * read, and so genuine side effects fire — including mapper register writes
     * that trigger bank switches, which power users rely on for experimentation.
     *
     * TWO addresses are blacklisted and silently dropped, because a hand-poked
     * value there is never what the user means and the effect is catastrophic:
     *  - `$4014` (OAM DMA): a write would halt the CPU for 513 cycles and overwrite
     *    all 256 bytes of OAM from the page named by [value] — turning a stray
     *    keystroke into a corrupted sprite table plus a timing desync.
     *  - `$4016` (controller strobe): a write would reset both controllers' shift
     *    registers, desyncing the game's input polling mid-frame.
     *
     * Note `$4017` is NOT blacklisted — a write there is the APU frame-counter
     * register, a legitimate (if niche) thing to poke. Only the two registers
     * whose *write* side effect would wreck the session are dropped; see ADR-0001
     * and the CONTEXT.md "poke" glossary entry.
     *
     * Thread safety mirrors [peek]: callers are the Memory Editor's JavaFX timer /
     * input handlers, writing unsynchronised while the emulation thread runs. A
     * single byte store is atomic per the JVM spec, which is sufficient for a
     * human-driven debug poke (ADR-0001).
     */
    fun poke(address: Int, value: Byte) {
        if (address == 0x4014 || address == 0x4016) return
        this[address] = value
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

    companion object {
        /**
         * Build [Memory] and [Apu] together, breaking their circular wiring
         * (issue #22).
         *
         * Why a factory: the two classes have a genuine mutual wiring dependency —
         * Apu takes a [DmaPort] (which Memory implements) so the DMC channel can
         * pull sample bytes, while Memory needs Apu to actually drive the channel
         * / frame-counter state when a CPU write lands at $4000-$401F. There is no
         * valid construction order where neither references the other, so Kotlin
         * can't model it with a plain constructor chain. The factory builds Memory
         * first, hands it to Apu, then attaches Apu back to Memory. From this point
         * on `memory.apu` is non-null for the lifetime of the [Memory] instance,
         * so dispatch sites read `apu.handleRegisterWrite(...)` directly with no
         * null-check penalty.
         *
         * Tests that touch only RAM / PPU / controller / DMA regions (i.e. never
         * read or write $4000-$401F) can keep using `Memory()` standalone — the
         * [apu] lateinit check fires only when the field is actually accessed.
         */
        fun createWithApu(): Pair<Memory, Apu> {
            val memory = Memory()
            val apu = Apu(memory)
            memory.apu = apu
            return memory to apu
        }
    }
}
