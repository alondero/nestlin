package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared driver for invoking Mesen2 in GUI mode with a generated Lua script.
 *
 * GUI launch: `Mesen.exe --doNotSaveSettings <script.lua> <rom.nes>`
 *
 * Requires a display attached AND I/O access enabled once interactively
 * (Script → Settings → Script Window → Restrictions → Allow access to I/O
 * and OS functions). See .claude/skills/mesen/SKILL.md for the full setup.
 */
object Mesen2Process {

    private const val ENV_VAR = "MESEN2_PATH"
    private const val SYS_PROP = "mesen2.path"
    private val DEFAULT_MESEN2_PATH = Paths.get("X:/src/nestlin/tools/Mesen2/Mesen.exe")
    private val ARGS = listOf("--doNotSaveSettings")

    fun mesen2Path(): Path {
        val path = System.getenv(ENV_VAR) ?: System.getProperty(SYS_PROP)
        return path?.let { Paths.get(it) } ?: DEFAULT_MESEN2_PATH
    }

    fun isAvailable(): Boolean = mesen2Path().toFile().exists()

    /**
     * Runs Mesen2 against [romPath] with [lua] saved as `<scriptName>.lua` in
     * a fresh temp dir, then returns the `LuaScriptData/<scriptName>/`
     * folder where the script's outputs land (resolved relative to the
     * Mesen2 install directory).
     *
     * Both script and rom paths are passed as absolute strings, because
     * `ProcessBuilder.directory(mesenDir)` re-roots cwd into the Mesen
     * install folder — relative paths would resolve under tools/Mesen2/
     * and silently fail to load.
     */
    fun runScript(lua: String, romPath: Path, scriptName: String): Path {
        val mesen = mesen2Path()
        val mesenDir = mesen.parent.toFile()
        val runDir = Files.createTempDirectory("mesen_${scriptName}_")
        val scriptPath = runDir.resolve("$scriptName.lua")
        Files.writeString(scriptPath, lua)
        try {
            val process = ProcessBuilder().apply {
                command(mesen.toString(), *ARGS.toTypedArray(),
                        scriptPath.toAbsolutePath().toString(),
                        romPath.toAbsolutePath().toString())
                directory(mesenDir)
                redirectError(ProcessBuilder.Redirect.INHERIT)
                redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Mesen2ExecutionException(
                    "Mesen2 exited with code $exitCode. " +
                    "Make sure I/O access is enabled in Script → Settings → " +
                    "Script Window → Restrictions → Allow access to I/O and OS functions."
                )
            }
            return mesen.parent.resolve("LuaScriptData").resolve(scriptName)
        } finally {
            runDir.toFile().deleteRecursively()
        }
    }
}
