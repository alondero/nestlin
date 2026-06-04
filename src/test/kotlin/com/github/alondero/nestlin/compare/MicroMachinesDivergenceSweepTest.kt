package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper71
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Sweeps the Mesen2 state-diff across boot to find the exact frame where
 * Nestlin's render output first diverges from Mesen2's. The result of
 * `extended boot trace` shows the PRG bank changed at frames 267 and 270
 * — this sweep pinpoints whether the divergence coincides.
 *
 * Skipped if Mesen2 is not installed.
 */
class MicroMachinesDivergenceSweepTest {

    @Test
    fun `find first divergence frame between Nestlin and Mesen2`() {
        assumeTrue(Mesen2StateCapturer.isMesen2Available(), "Mesen2 not available")
        val romPath = locateRom() ?: run {
            assumeTrue(false, "Micro Machines ROM not found"); return
        }

        // Sweep the suspect range. Frame 120 already matches (we verified that
        // earlier). The extended trace shows PRG bank changes at frame 267 and
        // 270. So we sweep 121..360 in 30-frame steps, plus the 240-280 hot zone.
        val framesToTest = listOf(121, 150, 180, 210, 240, 250, 260, 267, 270, 280, 300, 330, 360)

        for (f in framesToTest) {
            val nestlin = NestlinStateCapturer.captureState(romPath, f)
            val mesen2 = Mesen2StateCapturer.captureState(romPath, f)

            val palette0Nest = nestlin.paletteRam[0]
            val palette0Mes = mesen2.paletteRam[0]
            val paletteDiff = firstDiffIndex(nestlin.paletteRam, mesen2.paletteRam)
            val chrDiff = firstDiffIndex(nestlin.chr, mesen2.chr)
            val oamDiff = firstDiffIndex(nestlin.oam, mesen2.oam)

            val prgBank = nestlin.mapper?.banks?.get("prgBank") ?: -1
            val status = if (paletteDiff == null && chrDiff == null && oamDiff == null) "MATCH" else "DIVERGE"

            // CPU state at capture time — this is the smoking gun for "CPU took a
            // different code path". Mesen2 captures at scanline 240 (pre-NMI), Nestlin
            // at scanline 261 (post-NMI), so PC and instruction count are NOT expected
            // to match — but the *delta* vs the previous sample should be in the same
            // ballpark.
            val pcNest = nestlin.cpu.pc
            val pcMes = mesen2.cpu.pc
            val aNest = nestlin.cpu.a
            val aMes = mesen2.cpu.a

            println(
                ("[sweep] frame=$f status=$status prgBank=$prgBank " +
                 "PC=N0x%04X/M0x%04X A=N0x%02X/M0x%02X " +
                 "palette[0]=0x%02X/0x%02X paletteFirstDiff=%s chrFirstDiff=%s oamFirstDiff=%s").format(
                    pcNest, pcMes, aNest, aMes,
                    palette0Nest, palette0Mes,
                    paletteDiff, chrDiff, oamDiff
                )
            )
        }
    }

    private fun firstDiffIndex(a: IntArray, b: IntArray): String? {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            if (a[i] != b[i]) return "@$i: 0x%02X vs 0x%02X".format(a[i], b[i])
        }
        return if (a.size != b.size) "len ${a.size}/${b.size}" else null
    }

    private fun locateRom(): java.nio.file.Path? {
        System.getenv("NESTLIN_MICRO_MACHINES_ROM")?.let {
            val p = java.nio.file.Paths.get(it); if (java.nio.file.Files.exists(p)) return p
        }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = java.nio.file.Paths.get(lib)
            if (!java.nio.file.Files.isDirectory(dir)) continue
            java.nio.file.Files.list(dir).use { stream ->
                return stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("micro machines") && n.contains("usa")
                }
            }
        }
        return null
    }
}
