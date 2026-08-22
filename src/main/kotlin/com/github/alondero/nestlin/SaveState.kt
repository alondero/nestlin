package com.github.alondero.nestlin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Save state file format ("NSTL"):
 *
 *   magic       4 bytes  "NSTL" (0x4E 0x53 0x54 0x4C)
 *   version     int      currently 7; bump on breaking format change.
 *                        Version 2 added a per-mapper version byte inside the
 *                        mapper block (see below) so individual mappers can
 *                        evolve their own field order without invalidating
 *                        sibling subsystems. Issue #100.
 *                        Version 3 added the CPU's nmiArmed latch (1-instruction
 *                        NMI latency, issue #88).
 *                        Version 4 moved nmiArmed out of the CPU block into a
 *                        dedicated `interruptController` sub-block. Issue #190.
 *                        Version 5 added a `ports` sub-block (one length-prefixed
 *                        UTF-8 string per port) recording which InputDevice is
 *                        plugged into each controller port. v4 files load with
 *                        both ports defaulting to STANDARD_GAMEPAD. Issue: 2-player
 *                        support.
 *                        Version 6 added optional 4-screen VRAM (two extra 1 KB
 *                        nametables) inside the PPU block. These bytes are only
 *                        present when the PPU mirroring is FOUR_SCREEN, so v4/v5
 *                        saves — and any v6 save of a non-4-screen game — are
 *                        byte-for-byte identical and load unchanged. GH #105.
 *                        Version 7 appends an optional length-prefixed
 *                        RetroAchievements runtime-progress trailer (issue #271).
 *                        Format: 4-byte big-endian length + bytes from
 *                        [ProgressCapture.capture]. Length 0 is reserved for
 *                        "no active runtime" (the runtime was idle when the
 *                        state was captured; on load the runtime resets to its
 *                        post-prepareGame baseline so no progress leaks across
 *                        timelines). A length above [MAX_RA_PROGRESS_BYTES] is
 *                        treated as corruption — the bytes are NOT allocated or
 *                        handed to the native runtime; instead the runtime is
 *                        reset. v4–v6 files do not carry this trailer and load
 *                        unchanged, with the runtime reset against the restored
 *                        memory (per the issue's "abandoned future timeline"
 *                        rule).
 *   romCrc      long     CRC32 of the loaded ROM at save time
 *   romMapper   int      mapper id (validated on load)
 *   cpu         block    written by Cpu.saveState
 *   interruptController block  written by InterruptController.saveState
 *                              (currently a single byte: nmiArmed)
 *   ram         2048 b   internal RAM
 *   ppu         block    written by Ppu.saveState
 *   apu         block    written by Apu.saveState
 *   ports       block    v5+ only. Two length-prefixed UTF-8 strings, one per
 *                              controller port, holding the
 *                              [com.github.alondero.nestlin.input.InputDevice.DeviceType.storageKey].
 *                              Format per entry: 1 byte length + UTF-8 bytes.
 *                              v4 files load with both ports defaulting to STANDARD_GAMEPAD.
 *   ctrl1/ctrl2 block    Controller.saveState x 2
 *   mapper      length-prefixed blob (4-byte int length + bytes from Mapper.saveState).
 *               Inside the blob, the first byte is the mapper's per-mapper
 *               saveState format version (see Mapper.saveStateVersion). A
 *               mismatch raises IncompatibleSaveStateException on load.
 *   raProgress  block    v7+ only. 4-byte big-endian length + bytes. A length of
 *                              0 means "no active runtime". An oversize length
 *                              (> [MAX_RA_PROGRESS_BYTES]) is rejected at parse
 *                              time and the trailer is NOT deserialized; the
 *                              runtime is reset to its baseline. The bytes are
 *                              the opaque output of the active
 *                              RetroAchievements runtime's serialize-progress;
 *                              the [ProgressRestore] callback is contractually
 *                              safe on corrupt/native-rejected input per the
 *                              [com.github.alondero.nestlin.session.RetroAchievementsService]
 *                              interface.
 *
 * Endianness: big-endian (DataOutputStream default).
 *
 * The mapper block is length-prefixed so future mapper revisions can be skipped if
 * loaded into older code, and so corruption is detected by mismatched offsets.
 */
object SaveState {
    private const val MAGIC = 0x4E53544C  // "NSTL"
    const val VERSION = 7

    /** Highest version this code can read. */
    private const val MIN_SUPPORTED_VERSION = 4

    /**
     * Hard upper bound on the RA progress trailer. A real rcheevos runtime
     * progress dump is well under 64 KB; 1 MiB gives plenty of headroom for
     * future growth while still rejecting the obvious "attacker set length to
     * 2 GiB" attempt at the parse layer, before any allocation or native call.
     */
    const val MAX_RA_PROGRESS_BYTES: Int = 1 * 1024 * 1024

    /**
     * Functional seam for capturing the active RA runtime's serialized
     * progress at the moment a save state is written. A `null` return is
     * equivalent to "length 0" (no active runtime).
     */
    fun interface ProgressCapture {
        fun capture(): ByteArray?
    }

    /**
     * Functional seam for restoring the active RA runtime's serialized
     * progress when a save state is read. A `null` (or empty) argument MUST
     * reset the runtime to its post-prepareGame baseline — never retain
     * progress from the abandoned timeline.
     */
    fun interface ProgressRestore {
        fun restore(progress: ByteArray?)
    }

    class IncompatibleSaveStateException(message: String) : RuntimeException(message)

    /**
     * Write the current emulator state to [out].
     *
     * @param captureProgress optional callback that returns the active RA
     *   runtime's serialized progress, or null if there is no active runtime.
     *   The bytes returned are appended as the v7 trailer. A null callback
     *   (the default) writes a zero-length trailer — equivalent to "no
     *   active runtime" — so the file format is identical regardless of
     *   whether a service is wired.
     */
    fun save(
        nestlin: Nestlin,
        out: OutputStream,
        captureProgress: ProgressCapture = ProgressCapture { null },
    ) {
        val game = nestlin.cpu.currentGame
            ?: throw IllegalStateException("No game loaded; cannot save state")

        val dos = DataOutputStream(out)
        dos.writeInt(MAGIC)
        dos.writeInt(VERSION)
        dos.writeLong(game.crc.value)
        dos.writeInt(game.header.mapper)

        nestlin.cpu.saveState(dos)
        nestlin.cpu.interruptController.saveState(dos)
        nestlin.memory.saveRamState(dos)
        nestlin.ppu.saveState(dos)
        nestlin.apu.saveState(dos)

        // v5 ports block: one length-prefixed UTF-8 string per port recording the
        // InputDevice.DeviceType.storageKey. Length-prefixed (rather than fixed-size
        // or null-terminated) so future device types like "four-score" can fit
        // without re-versioning the file format.
        writeDeviceType(dos, nestlin.memory.portType(0))
        writeDeviceType(dos, nestlin.memory.portType(1))

        nestlin.memory.controller1.saveState(dos)
        nestlin.memory.controller2.saveState(dos)

        val mapperBuf = ByteArrayOutputStream()
        nestlin.memory.mapper?.saveState(DataOutputStream(mapperBuf))
        dos.writeInt(mapperBuf.size())
        mapperBuf.writeTo(dos)

        // v7 RA progress trailer. Length-prefixed, big-endian, exactly like
        // other blocks. Zero-length means "no active runtime" — the load
        // path will reset the runtime rather than retain timeline-leaked
        // progress. We refuse to embed anything above the safety cap; the
        // load-side bounds check is the authoritative gate but a defensive
        // here means the file is never corrupted by an upstream caller
        // returning an unbounded buffer.
        val progress = captureProgress.capture()
        val progressToWrite = when {
            progress == null -> ByteArray(0)
            progress.size > MAX_RA_PROGRESS_BYTES -> ByteArray(0)
            else -> progress
        }
        dos.writeInt(progressToWrite.size)
        if (progressToWrite.isNotEmpty()) dos.write(progressToWrite)

        dos.flush()
    }

    /**
     * Restore emulator state from [input].
     *
     * @param restoreProgress callback invoked with the v7 trailer bytes (or
     *   null when the file has no trailer / a zero-length trailer / an
     *   over-limit trailer / a corrupt negative length). The callback MUST
     *   treat null and empty as "reset the runtime to baseline" — that's the
     *   only way to honour the "no progress leaks across timelines"
     *   invariant. The default callback is a no-op (CLI replay and tests
     *   that don't care about RA).
     */
    fun load(
        nestlin: Nestlin,
        input: InputStream,
        restoreProgress: ProgressRestore = ProgressRestore { /* no-op */ },
    ) {
        val game = nestlin.cpu.currentGame
            ?: throw IllegalStateException("No game loaded; cannot load state")

        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IncompatibleSaveStateException("Not a Nestlin save state (bad magic: ${"%08X".format(magic)})")
        }
        val version = dis.readInt()
        if (version < MIN_SUPPORTED_VERSION || version > VERSION) {
            throw IncompatibleSaveStateException("Unsupported save state version $version (expected $MIN_SUPPORTED_VERSION..$VERSION)")
        }
        val romCrc = dis.readLong()
        if (romCrc != game.crc.value) {
            throw IncompatibleSaveStateException(
                "Save state ROM mismatch: state was made on CRC ${"%08X".format(romCrc)}, " +
                "but ${"%08X".format(game.crc.value)} is loaded"
            )
        }
        val mapperId = dis.readInt()
        if (mapperId != game.header.mapper) {
            throw IncompatibleSaveStateException(
                "Save state mapper mismatch: state mapper=$mapperId, current mapper=${game.header.mapper}"
            )
        }

        nestlin.cpu.loadState(dis)
        nestlin.cpu.interruptController.loadState(dis)
        nestlin.memory.loadRamState(dis)
        nestlin.ppu.loadState(dis)
        nestlin.apu.loadState(dis)

        // v5+ reads the ports block; v4 leaves both ports at their construction-time
        // default (StandardGamepad). The controller1/controller2 fields are stable
        // across the swap, so a v4 save loaded into v5 code resumes with both ports
        // bound to their original Controllers — the same behaviour a v4 save produced
        // when loaded into v4 code.
        if (version >= 5) {
            val port0Type = readDeviceType(dis)
            val port1Type = readDeviceType(dis)
            nestlin.memory.setPortType(0, port0Type)
            nestlin.memory.setPortType(1, port1Type)
        }

        nestlin.memory.controller1.loadState(dis)
        nestlin.memory.controller2.loadState(dis)

        val mapperLen = dis.readInt()
        val mapperBytes = ByteArray(mapperLen)
        dis.readFully(mapperBytes)
        val mapper = nestlin.memory.mapper
            ?: throw IllegalStateException("No mapper present on Nestlin instance after ROM load")
        mapper.loadState(DataInputStream(ByteArrayInputStream(mapperBytes)))

        nestlin.memory.syncMirroringFromMapper()

        // v7 RA progress trailer. Older versions have no trailer, so we
        // reset the runtime against the freshly-restored emulator memory
        // rather than retaining progress from the abandoned future
        // timeline. Zero-length and over-limit trailers also reset.
        if (version >= 7) {
            val progressLen = dis.readInt()
            val progressBytes: ByteArray? = when {
                progressLen < 0 -> {
                    // Corrupt (negative) length: refuse to allocate, do not
                    // skipBytes (length is meaningless), reset the runtime.
                    null
                }
                progressLen == 0 -> {
                    // No active runtime was captured: reset the runtime to
                    // its post-prepareGame baseline. Empty bytes in the
                    // restoreProgress contract means "reset".
                    null
                }
                progressLen > MAX_RA_PROGRESS_BYTES -> {
                    // Oversize: refuse to allocate, drain the trailer so the
                    // stream offset is consistent with what we just read,
                    // then reset. We swallow any IOException from
                    // skipBytes (e.g. EOF on a truncated file) — the
                    // runtime is reset regardless, which is the safe
                    // outcome.
                    runCatching { dis.skipBytes(progressLen) }
                    null
                }
                else -> {
                    val buf = ByteArray(progressLen)
                    dis.readFully(buf)
                    buf
                }
            }
            // Invariant: a corrupt / over-limit / zero-length trailer hands
            // `null` to the restore callback. The restoreProgress contract
            // requires it to interpret null as "reset to baseline" rather
            // than "retain previous progress".
            restoreProgress.restore(progressBytes)
        } else {
            // Older save: no trailer. Reset the runtime against the
            // restored memory so a freshly-loaded v4–v6 state never
            // inherits progress from the timeline we're throwing away.
            restoreProgress.restore(null)
        }
    }

    /** Write a single port's [InputDevice.DeviceType] in the length-prefixed UTF-8 form. */
    private fun writeDeviceType(dos: DataOutputStream, type: com.github.alondero.nestlin.input.InputDevice.DeviceType) {
        val keyBytes = type.storageKey.toByteArray(Charsets.UTF_8)
        dos.writeByte(keyBytes.size)
        dos.write(keyBytes)
    }

    /** Read a single port's [InputDevice.DeviceType]. Unknown keys fall back to STANDARD_GAMEPAD. */
    private fun readDeviceType(dis: DataInputStream): com.github.alondero.nestlin.input.InputDevice.DeviceType {
        val len = dis.readUnsignedByte()
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        val key = String(bytes, Charsets.UTF_8)
        return com.github.alondero.nestlin.input.InputDevice.DeviceType.entries
            .firstOrNull { it.storageKey == key }
            ?: com.github.alondero.nestlin.input.InputDevice.DeviceType.STANDARD_GAMEPAD
    }
}
