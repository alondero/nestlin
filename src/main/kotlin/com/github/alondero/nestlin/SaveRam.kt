package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.Mapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Battery-backed save RAM (.sav) reader/writer.
 *
 * File format: raw bytes, no header. Matches FCEUX / Nestopia / Mesen / Mesen2 so
 * saves are bit-for-bit portable. Typical size is 8KB (cartridge PRG-RAM at $6000-$7FFF).
 *
 * Thread-safety: [save] is safe to call from a thread other than the emulation thread.
 * It snapshots the RAM under a tiny lock then writes the snapshot, so the emulation
 * thread never waits on disk I/O. Atomic file replace ensures the existing .sav is
 * intact if the JVM dies mid-write.
 */
object SaveRam {

    fun load(path: Path, mapper: Mapper) {
        val ram = mapper.batteryBackedRam() ?: return
        if (!Files.exists(path)) return
        val bytes = Files.readAllBytes(path)
        if (bytes.size != ram.size) {
            System.err.println("[SRAM] Skipping load: $path size ${bytes.size} != expected ${ram.size}")
            return
        }
        synchronized(ram) {
            System.arraycopy(bytes, 0, ram, 0, ram.size)
        }
        mapper.batteryDirty = false
    }

    fun save(path: Path, mapper: Mapper) {
        val ram = mapper.batteryBackedRam() ?: return
        val snapshot: ByteArray
        synchronized(ram) { snapshot = ram.copyOf() }
        Files.createDirectories(path.parent ?: Path.of("."))
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.write(tmp, snapshot)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Some filesystems (e.g. cross-volume) don't support atomic move; fall back
            // to a plain replace. We lose crash-atomicity but avoid leaving the .tmp behind.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        mapper.batteryDirty = false
    }
}
