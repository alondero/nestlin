package com.github.alondero.nestlin.compare

import org.junit.Test
import java.nio.file.Paths

class CompareCpuCyclesTest {

    @Test
    fun `compare Dragon Power CPU cycles`() {
        val romPath = Paths.get("X:/src/nestlin/.claude/worktrees/ripe-eager-beam/testroms/dragonpower.nes")
        CompareCpuCycles.compareCycles(romPath, "DragonPower", 30)
    }

    @Test
    fun `compare SMB CPU cycles`() {
        val romPath = Paths.get("X:/src/nestlin/.claude/worktrees/ripe-eager-beam/testroms/smbdh.nes")
        CompareCpuCycles.compareCycles(romPath, "SMB", 30)
    }
}