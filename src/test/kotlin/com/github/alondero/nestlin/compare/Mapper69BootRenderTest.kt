package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper69
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Boot/render regression test for Mapper 69 (Sunsoft FME-7) using Batman: Return of
 * the Joker (USA) — the available NTSC mapper-69 title.
 *
 * It asserts the cheapest end-to-end signal that the mapper works in a real game: the
 * ROM boots, banks correctly, enables rendering, and draws substantial non-blank
 * content — while actively driving the CPU-cycle FME-7 IRQ (the mapper's differentiator).
 * The precise per-cycle IRQ semantics are covered by the fast unit tests in Mapper69Test.
 *
 * NOTE on Gimmick!: the only locally available Gimmick! ROM is the European (PAL)
 * release, whose IRQ-driven frame loop is tuned for PAL CPU timing. Nestlin's core is
 * NTSC-only, so that ROM desyncs and hangs at boot — a pre-existing emulator limitation
 * (PAL timing), not a mapper defect. Batman (NTSC) is therefore the mapper-69 boot oracle.
 *
 * The ROM is a commercial title not in git, located via FME7_ROM_PATH or the local
 * NO-INTRO library default. When absent the test no-ops with a clear message, consistent
 * with the other commercial-ROM tests in this module.
 */
class Mapper69BootRenderTest {

    private val romPath: Path = Paths.get(
        System.getenv("FME7_ROM_PATH") ?: "S:/Media/Nintendo NES/Games/Batman - Return of the Joker (USA).nes"
    )

    @Test
    fun `Batman boots renders and drives the FME-7 IRQ`() {
        if (!Files.exists(romPath)) {
            println("[SKIP] FME-7 (Batman) ROM not found at $romPath — set FME7_ROM_PATH to enable.")
            return
        }

        var frameCount = 0
        var maxNonBlack = 0
        var irqEverArmed = false

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                val nonBlack = frame.scanlines.sumOf { row -> row.count { it != 0x000000 } }
                if (nonBlack > maxNonBlack) maxNonBlack = nonBlack

                val mapper = nestlin.cpu.memory.mapper
                assertThat("ROM should select Mapper69", mapper is Mapper69, equalTo(true))
                if ((mapper as Mapper69).snapshot().irqState!!["irqCounterEnable"] == 1) irqEverArmed = true

                if (frameCount >= 360) nestlin.stop()
            }
        })

        nestlin.load(romPath)
        nestlin.powerReset()
        nestlin.start()

        println("Batman (FME-7): $frameCount frames, max non-black pixels=$maxNonBlack, irqArmed=$irqEverArmed")

        // Boots far enough to enable rendering and draw a populated screen.
        assertThat("rendered substantial non-blank content", maxNonBlack, greaterThan(5_000))
        // The game drives the FME-7 cycle IRQ — the mapper's distinguishing feature.
        assertThat("game armed the FME-7 IRQ counter", irqEverArmed, equalTo(true))
    }
}
