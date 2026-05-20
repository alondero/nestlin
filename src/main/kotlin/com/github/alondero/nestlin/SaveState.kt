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
 *   version     int      currently 1; bump on breaking format change
 *   romCrc      long     CRC32 of the loaded ROM at save time
 *   romMapper   int      mapper id (validated on load)
 *   cpu         block    written by Cpu.saveState
 *   ram         2048 b   internal RAM
 *   ppu         block    written by Ppu.saveState
 *   apu         block    written by Apu.saveState
 *   ctrl1/ctrl2 block    Controller.saveState x 2
 *   mapper      length-prefixed blob (4-byte int length + bytes from Mapper.saveState)
 *
 * Endianness: big-endian (DataOutputStream default).
 *
 * The mapper block is length-prefixed so future mapper revisions can be skipped if
 * loaded into older code, and so corruption is detected by mismatched offsets.
 */
object SaveState {
    private const val MAGIC = 0x4E53544C  // "NSTL"
    const val VERSION = 1

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
        nestlin.memory.saveRamState(dos)
        nestlin.ppu.saveState(dos)
        nestlin.apu.saveState(dos)
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
        if (version != VERSION) {
            throw IncompatibleSaveStateException("Unsupported save state version $version (expected $VERSION)")
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
        nestlin.memory.loadRamState(dis)
        nestlin.ppu.loadState(dis)
        nestlin.apu.loadState(dis)
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
}
