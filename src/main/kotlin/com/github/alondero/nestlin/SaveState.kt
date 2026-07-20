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
 *   version     int      currently 6; bump on breaking format change.
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
 *
 * Endianness: big-endian (DataOutputStream default).
 *
 * The mapper block is length-prefixed so future mapper revisions can be skipped if
 * loaded into older code, and so corruption is detected by mismatched offsets.
 */
object SaveState {
    private const val MAGIC = 0x4E53544C  // "NSTL"
    const val VERSION = 6

    /** Highest version this code can read. */
    private const val MIN_SUPPORTED_VERSION = 4

    class IncompatibleSaveStateException(message: String) : RuntimeException(message)

    fun save(nestlin: Nestlin, out: OutputStream) {
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

        dos.flush()
    }

    fun load(nestlin: Nestlin, input: InputStream) {
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
