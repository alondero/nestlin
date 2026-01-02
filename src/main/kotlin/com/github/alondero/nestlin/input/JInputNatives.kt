package com.github.alondero.nestlin.input

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object JInputNatives {
    @Volatile
    private var prepared = false

    fun prepare() {
        if (prepared) return
        synchronized(this) {
            if (prepared) return
            val existingPath = System.getProperty("net.java.games.input.librarypath")
            if (!existingPath.isNullOrBlank()) {
                prepared = true
                return
            }

            val libraries = requiredLibraries()
            if (libraries.isEmpty()) {
                prepared = true
                return
            }

            val targetDir = File(System.getProperty("java.io.tmpdir"), "nestlin-jinput")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                println("[GAMEPAD] Failed to create JInput native dir: ${targetDir.absolutePath}")
                prepared = true
                return
            }

            val loader = JInputNatives::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val missing = mutableListOf<String>()

            for (library in libraries) {
                val mappedName = System.mapLibraryName(library)
                val targetFile = File(targetDir, mappedName)
                if (targetFile.exists()) continue

                val resourceName = resolveResourceName(loader, mappedName)
                if (resourceName == null) {
                    missing.add(mappedName)
                    continue
                }

                loader.getResourceAsStream(resourceName)?.use { input ->
                    Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } ?: missing.add(mappedName)
            }

            val hasAny = libraries.any { File(targetDir, System.mapLibraryName(it)).exists() }
            if (hasAny) {
                System.setProperty("net.java.games.input.librarypath", targetDir.absolutePath)
            } else if (missing.isNotEmpty()) {
                println("[GAMEPAD] JInput natives missing on classpath: ${missing.joinToString(", ")}")
            }

            prepared = true
        }
    }

    private fun requiredLibraries(): List<String> {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        return when {
            osName.startsWith("windows") -> {
                if (arch == "x86") {
                    listOf("jinput-dx8", "jinput-raw")
                } else {
                    listOf("jinput-dx8_64", "jinput-raw_64")
                }
            }
            osName.startsWith("linux") -> {
                if (arch == "x86") listOf("jinput-linux") else listOf("jinput-linux64")
            }
            osName.startsWith("mac") || osName.startsWith("darwin") -> listOf("jinput-osx")
            else -> emptyList()
        }
    }

    private fun resolveResourceName(loader: ClassLoader, mappedName: String): String? {
        if (loader.getResource(mappedName) != null) return mappedName
        if (mappedName.endsWith(".dylib")) {
            val legacy = mappedName.removeSuffix(".dylib") + ".jnilib"
            if (loader.getResource(legacy) != null) return legacy
        }
        return null
    }
}
