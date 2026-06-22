package com.github.alondero.nestlin.gamepak

import java.io.DataInput
import java.io.DataOutput

/**
 * CHR bus storage backing for [Mapper].
 *
 * Owns the [chrRom] / [chrRam] byte arrays and the read-only / CHR-RAM
 * branching that every mapper used to duplicate. The mapper still owns
 * banking — bank-to-offset computation, mirroring, etc — and calls
 * [read] / [write] with a flat offset.
 *
 * Design: ADR-0002. After construction, exactly one of CHR-ROM (non-empty)
 * or CHR-RAM (size = [chrRamSize]) is active:
 *   - When [chrRom] is non-empty, reads return from CHR-ROM and writes are
 *     silently dropped (CHRRom is read-only).
 *   - When [chrRom] is empty, a CHR-RAM buffer of [chrRamSize] bytes is
 *     allocated and used for both reads and writes.
 *
 * The default [chrRamSize] is `0x2000` (standard 8KB CHR); Mapper 19 N163
 * passes `0x3000` for its 12KB internal CHR-RAM.
 *
 * Callers are expected to pre-mask the PPU address (`address and 0x1FFF`)
 * before calling [read] / [write]; out-of-range offsets throw
 * [IndexOutOfBoundsException] from the underlying [ByteArray] access.
 *
 * Future extension hooks (default no-op):
 *   - [peek] mirrors the project's existing `peek` precedent from
 *     `Memory.kt`. It defaults to [read] but a mapper that grows
 *     read-side-effect banking can override it without changing [read].
 *   - [serialize] / [deserialize] reserve the save-state seam so a future
 *     [ChrMemory] adapter (e.g. battery-backed CHR-RAM) can plug in
 *     without touching [DefaultChrMemory]. The mapper save-state refactor
 *     that uses these hooks is tracked separately from this issue.
 */
interface ChrMemory {
    fun read(offset: Int): Byte
    fun write(offset: Int, value: Byte)

    /** Side-effect-free read. Defaults to [read]; mappers with read-side
     *  effects (none today) override this. Mirrors `Memory.peek`. */
    fun peek(offset: Int): Byte = read(offset)

    /** Save-state hook — default no-op. Mappers without CHR-RAM leave
     *  this alone; [DefaultChrMemory] writes its RAM when present. */
    fun serialize(out: DataOutput) {}

    /** Save-state hook — default no-op. Mirror of [serialize]. */
    fun deserialize(input: DataInput) {}

    companion object {
        /**
         * Construct a CHR bus backed by [chrRom] when non-empty, or a
         * [chrRamSize] CHR-RAM buffer when [chrRom] is empty.
         */
        fun default(chrRom: ByteArray, chrRamSize: Int = 0x2000): ChrMemory =
            DefaultChrMemory(chrRom, chrRamSize)
    }
}

/**
 * Standard [ChrMemory] implementation: ROM + (optional) RAM, branching
 * determined once at construction.
 *
 * The invariant after construction is exactly one of:
 *   - `ram.isNotEmpty()` — reads and writes go to RAM, ROM is empty
 *   - `ram.isEmpty()`   — reads go to ROM, writes are silently dropped
 *
 * `rom` is held by reference, not copied — the GamePak owns the
 * underlying byte arrays and ChrMemory only borrows them.
 */
class DefaultChrMemory(
    chrRom: ByteArray,
    chrRamSize: Int = 0x2000
) : ChrMemory {
    private val ram: ByteArray =
        if (chrRom.isEmpty()) ByteArray(chrRamSize) else ByteArray(0)
    private val rom: ByteArray = chrRom

    override fun read(offset: Int): Byte =
        if (ram.isNotEmpty()) ram[offset] else rom[offset]

    override fun write(offset: Int, value: Byte) {
        if (ram.isNotEmpty()) ram[offset] = value
    }

    override fun serialize(out: DataOutput) {
        if (ram.isNotEmpty()) out.write(ram)
    }

    override fun deserialize(input: DataInput) {
        if (ram.isNotEmpty()) input.readFully(ram)
    }
}
