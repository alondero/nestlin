package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Snapshot Kirby OAM at a sequence of frames so we can pinpoint when the
 * title-screen sprite overlay bug first appears, and what the OAM looks
 * like across the transition.
 *
 * Output: build/kirby-oam-snapshots.txt
 */
class KirbyOamSnapshotTest {

    @Test
    fun snapshotOamAtKeyFrames() {
        val romPath = Paths.get("kirby.nes")
        if (!Files.exists(romPath)) {
            System.err.println("Kirby ROM not at ${romPath.toAbsolutePath()}, skipping")
            return
        }

        val framesToSnap = (500..1500 step 50).toSet() + setOf(615, 619, 625, 800, 900, 1200)
        val snapshots = mutableMapOf<Int, String>()
        val screenshotFrames = setOf(500, 619, 625, 700, 900, 1200)
        val outDir = java.nio.file.Paths.get("build/kirby-oam-snapshots")
        java.nio.file.Files.createDirectories(outDir)

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount in framesToSnap) {
                    snapshots[frameCount] = dumpOam(nestlin) + "\n" + dumpPatternTableSlice(nestlin)
                }
                if (frameCount in screenshotFrames) {
                    saveFrameAsPng(frame, outDir.resolve("nestlin-frame${frameCount}.png"))
                }
                if (frameCount > framesToSnap.max()) {
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        val outPath = Paths.get("build/kirby-oam-snapshots.txt")
        Files.createDirectories(outPath.parent)
        val sb = StringBuilder()
        for (f in snapshots.keys.sorted()) {
            sb.append("=== frame $f ===\n")
            sb.append(snapshots[f])
            sb.append("\n")
        }
        Files.write(outPath, sb.toString().toByteArray())
        System.err.println("Wrote ${snapshots.size} snapshots to ${outPath.toAbsolutePath()}")
    }

    private fun saveFrameAsPng(frame: Frame, path: java.nio.file.Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        ImageIO.write(image, "PNG", path.toFile())
    }

    private fun dumpPatternTableSlice(nestlin: Nestlin): String {
        val ppuMem = nestlin.memory.ppuAddressedMemory.ppuInternalMemory
        val sb = StringBuilder()
        sb.append("# pattern-table dump \$1800-\$1BFF (1KB)\n")
        for (base in 0x1800 until 0x1C00 step 16) {
            sb.append("%04X:".format(base))
            for (i in 0 until 16) {
                sb.append(" %02X".format(ppuMem[base + i].toUnsignedInt()))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun dumpOam(nestlin: Nestlin): String {
        val oam = nestlin.memory.ppuAddressedMemory.objectAttributeMemory
        val sb = StringBuilder()
        for (i in 0 until 16) {
            val s = oam.getSprite(i)
            sb.append("  S%02d  Y=%3d X=%3d tile=$%02X attr=$%02X\n"
                .format(i, s.y, s.x, s.tileIndex.toUnsignedInt(), s.attributes.toUnsignedInt()))
        }
        return sb.toString()
    }
}
