package com.github.alondero.nestlin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration settings for the emulator.
 *
 * This class holds runtime configuration options that can be modified during emulation.
 */
data class EmulatorConfig(
    /**
     * Enable/disable speed throttling to match original NES timing.
     * When enabled, emulator runs at ~60 FPS (NTSC).
     * When disabled, emulator runs as fast as possible.
     * Default: true (throttling enabled)
     */
    var speedThrottlingEnabled: Boolean = true,

    /**
     * Target frame rate in frames per second.
     * NTSC (US/Japan): 60.0988 FPS
     * PAL (Europe): 50.007 FPS
     * Default: 60.0 (NTSC approximation)
     */
    var targetFps: Double = 60.0,

    /**
     * Pause emulation. When true, the emulation loop short-circuits each
     * iteration without ticking CPU/PPU/APU. Toggled from the UI's Emulation menu.
     */
    var paused: Boolean = false
) {
    /**
     * Target time per frame in nanoseconds.
     * Calculated from targetFps for precise timing.
     */
    val targetFrameTimeNanos: Long
        get() = (1_000_000_000.0 / targetFps).toLong()

    companion object {
        private val configDir = File(System.getProperty("user.home"), ".config/nestlin")
        private val recentRomsFile = File(configDir, "recent_roms.json")
        private const val MAX_RECENT_ROMS = 10

        private val gson = com.google.gson.GsonBuilder().create()

        /**
         * Add a ROM path to the recent ROMs list.
         * If the path already exists, it's moved to the front.
         * Maintains a maximum of 10 entries.
         */
        fun addRecentRom(path: Path) {
            val recentRoms = getRecentRoms().toMutableList()
            recentRoms.remove(path)
            recentRoms.add(0, path)
            val trimmed = recentRoms.take(MAX_RECENT_ROMS)
            saveRecentRoms(trimmed)
        }

        /**
         * Get the list of recent ROMs, filtering out paths that no longer exist.
         */
        fun getRecentRoms(): List<Path> {
            return try {
                if (recentRomsFile.exists()) {
                    val json = recentRomsFile.readText()
                    val paths: List<String> = gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type)
                    paths.mapNotNull { pathStr ->
                        val path = Paths.get(pathStr)
                        if (Files.exists(path)) path else null
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                println("[CONFIG] Error loading recent ROMs: ${e.message}")
                emptyList()
            }
        }

        private fun saveRecentRoms(roms: List<Path>) {
            try {
                configDir.mkdirs()
                val pathsAsStrings = roms.map { it.toAbsolutePath().toString() }
                recentRomsFile.writeText(gson.toJson(pathsAsStrings))
            } catch (e: Exception) {
                println("[CONFIG] Error saving recent ROMs: ${e.message}")
            }
        }
    }
}
