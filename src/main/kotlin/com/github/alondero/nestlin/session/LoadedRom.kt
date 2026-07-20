package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.gamepak.GamePak
import java.nio.file.Path

/**
 * The identity of a loaded ROM: the parsed [GamePak] paired with the disk path it was read from.
 *
 * Collapses what was previously split across `cpu.currentGame` (just the `GamePak`) and
 * `Application.currentRomPath` (just the `Path`) into a single value object so any caller can
 * answer "where did this ROM come from?" with one read. See issue #189.
 *
 * Constructed by [com.github.alondero.nestlin.Nestlin.load] and exposed via
 * `Nestlin.loadedRom` (null before the first `load()`).
 *
 * [sourcePath] is nullable so bytes-only loads (test fixtures that read from
 * `src/test/resources/`, callers that synthesise ROMs in memory) can participate without
 * claiming a synthetic `/dev/null` path. Operations that genuinely need a disk path
 * (battery-RAM autosave at shutdown, FM2 movie recording) already guard with `?:` — so
 * nullable here just expresses what the existing call sites already assume.
 */
data class LoadedRom(val gamePak: GamePak, val sourcePath: Path?)
