package com.github.alondero.nestlin.ui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

enum class ScaleMode {
    X1, X2, X3, X4, FIT;

    fun factor(): Int? = when (this) {
        X1 -> 1
        X2 -> 2
        X3 -> 3
        X4 -> 4
        FIT -> null
    }

    fun label(): String = when (this) {
        X1 -> "1x"
        X2 -> "2x"
        X3 -> "3x"
        X4 -> "4x"
        FIT -> "Fit"
    }
}

data class DisplayConfig(
    val scale: ScaleMode = ScaleMode.X3,
    val fullscreen: Boolean = false
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        private val defaultConfigDir = File(System.getProperty("user.home"), ".config/nestlin")

        fun configFile(dir: File = defaultConfigDir): File = File(dir, "display.json")

        fun load(dir: File = defaultConfigDir): DisplayConfig {
            val file = configFile(dir)
            return try {
                if (file.exists()) {
                    gson.fromJson(file.readText(), DisplayConfig::class.java) ?: DisplayConfig()
                } else {
                    DisplayConfig()
                }
            } catch (e: Exception) {
                println("[DISPLAY] Error loading config: ${e.message}, using defaults")
                DisplayConfig()
            }
        }

        fun save(config: DisplayConfig, dir: File = defaultConfigDir) {
            try {
                dir.mkdirs()
                configFile(dir).writeText(gson.toJson(config))
            } catch (e: Exception) {
                println("[DISPLAY] Error saving config: ${e.message}")
            }
        }
    }
}
