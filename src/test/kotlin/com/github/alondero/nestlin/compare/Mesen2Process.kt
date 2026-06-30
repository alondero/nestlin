package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared driver for invoking Mesen2 in GUI mode with a generated Lua script.
 *
 * GUI launch: `Mesen.exe --doNotSaveSettings <script.lua> <rom.nes>`
 *
 * Requires a display attached AND I/O access enabled once interactively
 * (Script → Settings → Script Window → Restrictions → Allow access to I/O
 * and OS functions). See `.claude/skills/mesen/SKILL.md` for the full setup.
 *
 * ## Scope
 *
 * This object owns the **one-off GUI invocation path** for tests that need
 * their own ad-hoc Lua (AkiraMesen2OracleTest's FM2-input variant, plus
 * any future diagnostic dumps). The cross-emulator regression suite
 * (`Mesen2StateCapturer`, `Mesen2ReferenceRunner`) no longer goes through
 * here — it uses `Mesen2Session` (issue #61) which keeps a Mesen2
 * process long-running per ROM.
 *
 * Path resolution delegates to [Mesen2Session.mesen2Path] so the four
 * independent call sites (`Mesen2Process.mesen2Path`,
 * `Mesen2StateCapturer.getMesen2Path`, `Mesen2ReferenceRunner.getMesen2Path`,
 * `Mesen2ProcessInstance`) all agree on which binary to launch.
 */
object Mesen2Process {

    private val ARGS = listOf("--doNotSaveSettings")

    /** Resolved Mesen2 executable path. Delegates to [Mesen2Session.mesen2Path]. */
    fun mesen2Path(): Path = Mesen2Session.mesen2Path()

    /** True if Mesen2 is available at the resolved path. */
    fun isAvailable(): Boolean = mesen2Path().toFile().exists()

    /**
     * Runs Mesen2 against [romPath] with [lua] saved as `<scriptName>.lua` in
     * a fresh temp dir, then returns the `LuaScriptData/<scriptName>/`
     * folder where the script's outputs land (resolved relative to the
     * Mesen2 install directory).
     *
     * Both script and rom paths are passed as absolute strings, because
     * `ProcessBuilder.directory(mesenDir)` re-roots cwd into the Mesen
     * install folder — relative paths would resolve under `tools/Mesen2/`
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
