package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper3
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Regression test for issue #43 (Star Soldier mapper 3 corruption).
 *
 * Asserts that Star Soldier's CHR bank actually changes during the title
 * sequence. The real ROM writes its bank-select to $C000-$DFFF; before
 * the CNROM range fix in [[Mapper3]], every write was silently dropped and
 * chrBank stayed at 0 forever.
 *
 * ROM expected at build/starsoldier.nes (staged from NO-INTRO collection);
 * skipped if absent.
 */
class StarSoldierMapper3RegressionTest {

    private val romPath = Paths.get("build/starsoldier.nes")
    private val captureFrames = setOf(60, 120, 180, 300)

    @Test
    fun traceMapperWrites() {
        assumeTrue(Files.exists(romPath), "ROM not staged at $romPath")

        val trace = mutableListOf<Mapper3.Write>()
        val bankAtFrame = linkedMapOf<Int, Int>()

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }
        // powerReset() is what wires the mapper (cpu.reset -> memory.readCartridge).
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as? Mapper3
            ?: error("Expected Mapper3 for Star Soldier; got ${nestlin.memory.mapper?.javaClass?.simpleName}")
        mapper.writeTrace = trace

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount in captureFrames) {
                    bankAtFrame[frameCount] = mapper.snapshot().banks["chr"] ?: -1
                }
                if (frameCount >= captureFrames.max()) nestlin.stop()
            }
        })
        nestlin.start()

        Assertions.assertTrue(trace.isNotEmpty()
        , "Star Soldier never wrote to mapper-3 register — ROM may not have booted")
        Assertions.assertTrue(bankAtFrame.values.any { it != 0 }
        , "Star Soldier CHR bank stayed at 0 — mapper-3 write-range regression. " +
                "Bank-per-frame: $bankAtFrame. First write: ${trace.first()}")
    }
}
