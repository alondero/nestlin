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
 */
data class LoadedRom(val gamePak: GamePak, val sourcePath: Path)
