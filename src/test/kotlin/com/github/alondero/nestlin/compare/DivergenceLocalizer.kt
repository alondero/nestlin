package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Localizes WHERE Nestlin diverges from the Mesen2 oracle for a given ROM + frame,
 * and classifies the LIKELY CAUSE using a decision tree distilled from nine past
 * debugging sessions (Gimmick NMI latch, Akira OAM DMA, RAMBO-1 double-fire,
 * Mind Blower Pak open-bus, Don Doko Don OAM phase, Micro Machines forced blank,
 * Big Nose NMI latency, Mapper 66 CHR decode, OAM DMA halt cycles).
 *
 * Output: a side-by-side table over the *render-output* state (the fields that are
 * stable across the Mesen2-scanline-240 / Nestlin-scanline-261 capture offset) plus
 * per-frame NMI/IRQ dispatch counts, with zero or more "LIKELY CAUSE" lines ordered
 * most -> least specific. PC and PPUSTATUS are shown but informational only — the
 * capture offset makes them inherently non-comparable.
 */
object DivergenceLocalizer {

    data class DivergenceReport(
        val nestlin: EmulatorStateSnapshot,
        val mesen2: EmulatorStateSnapshot?,   // null when Mesen2 is unavailable
        val table: String,                    // the full divergence-report.txt contents
        val classifications: List<String>,    // the LIKELY CAUSE lines (empty when Mesen2 absent)
        val reportDir: Path
    ) {
        val mesen2Available: Boolean get() = mesen2 != null
    }

    /**
     * Captures both emulators at [frame] for [romPath], writes
     * nestlin-state.json / mesen2-state.json / divergence-report.txt into
     * [reportDir], and returns the parsed report.
     *
     * When Mesen2 is not available the Nestlin-side table is still produced,
     * with a note that classification needs Mesen2.
     */
    fun localize(romPath: Path, frame: Int, reportDir: Path): DivergenceReport {
        val nestlin = NestlinStateCapturer.captureState(romPath, frame)
        val mesen2 = if (Mesen2StateCapturer.isMesen2Available()) {
            Mesen2StateCapturer.captureState(romPath, frame)
        } else null
        return report(nestlin, mesen2, reportDir)
    }

    /**
     * Builds + writes the report from snapshots captured elsewhere (so callers that
     * already paid for the captures — e.g. MapperRegressionTestBase — don't re-run
     * both emulators).
     */
    fun report(nestlin: EmulatorStateSnapshot, mesen2: EmulatorStateSnapshot?, reportDir: Path): DivergenceReport {
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("nestlin-state.json"), nestlin.toJson())
        if (mesen2 != null) {
            Files.writeString(reportDir.resolve("mesen2-state.json"), mesen2.toJson())
        }

        val classifications = if (mesen2 != null) classify(nestlin, mesen2) else emptyList()
        val table = buildTable(nestlin, mesen2, classifications)
        Files.writeString(reportDir.resolve("divergence-report.txt"), table)

        return DivergenceReport(nestlin, mesen2, table, classifications, reportDir)
    }

    // ------------------------------------------------------------------ table

    private fun buildTable(
        nestlin: EmulatorStateSnapshot,
        mesen2: EmulatorStateSnapshot?,
        classifications: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Divergence Report — ${nestlin.romName} @ frame ${nestlin.frameNumber}")
        sb.appendLine("=".repeat(70))
        sb.appendLine("Capture offset: Mesen2 endFrame fires at scanline 240 (pre-NMI),")
        sb.appendLine("Nestlin's frame callback at scanline 261 (post-NMI). CPU registers and")
        sb.appendLine("cycle counts are therefore NON-comparable; PC and PPUSTATUS below are")
        sb.appendLine("informational only. Render outputs (OAM, palette, CHR, PPUCTRL, PPUMASK)")
        sb.appendLine("reflect the completed frame and ARE comparable.")
        sb.appendLine()

        fun fmtCount(v: Int) = if (v < 0) "?" else v.toString()
        fun row(label: String, n: String, m: String, note: String = "") {
            val diff = if (mesen2 == null) "" else if (n == m) "" else "  <-- DIFFERS"
            sb.appendLine("%-22s %-18s %-18s%s%s".format(label, n, m, diff, note))
        }

        sb.appendLine("%-22s %-18s %-18s".format("Field", "Nestlin", if (mesen2 != null) "Mesen2" else "Mesen2 (N/A)"))
        sb.appendLine("-".repeat(70))
        row("PC (informational)", "0x%04X".format(nestlin.cpu.pc), mesen2?.let { "0x%04X".format(it.cpu.pc) } ?: "-")
        row("PPUCTRL", "0x%02X".format(nestlin.ppuRegisters.controller), mesen2?.let { "0x%02X".format(it.ppuRegisters.controller) } ?: "-")
        row("PPUMASK", "0x%02X".format(nestlin.ppuRegisters.mask), mesen2?.let { "0x%02X".format(it.ppuRegisters.mask) } ?: "-")
        // PPUSTATUS vblank bit is phase-dependent across the capture offset — informational.
        sb.appendLine("%-22s %-18s %-18s".format(
            "PPUSTATUS (info)",
            "0x%02X".format(nestlin.ppuRegisters.status),
            mesen2?.let { "0x%02X".format(it.ppuRegisters.status) } ?: "-"))
        row("NMI/frame", fmtCount(nestlin.nmiCountLastFrame), mesen2?.let { fmtCount(it.nmiCountLastFrame) } ?: "-")
        row("IRQ/frame", fmtCount(nestlin.irqCountLastFrame), mesen2?.let { fmtCount(it.irqCountLastFrame) } ?: "-")
        sb.appendLine()

        // Mapper banks (Nestlin side only — Mesen2 captures don't decompose the mapper).
        sb.appendLine("Mapper banks (Nestlin):")
        val mapper = nestlin.mapper
        if (mapper != null) {
            sb.appendLine("  type=${mapper.type} (mapper ${mapper.mapperId})")
            if (mapper.banks.isEmpty()) {
                sb.appendLine("  (no banks reported)")
            } else {
                mapper.banks.forEach { (k, v) -> sb.appendLine("  %-20s %d (0x%02X)".format(k, v, v)) }
            }
        } else {
            sb.appendLine("  (mapper snapshot not available)")
        }
        sb.appendLine()

        if (mesen2 == null) {
            sb.appendLine("Mesen2 NOT AVAILABLE — no oracle to diff against, so memory diffs and")
            sb.appendLine("classification are skipped. Set MESEN2_PATH (or mesen2.path) to enable.")
            return sb.toString()
        }

        // Memory diffs.
        sb.appendLine("Memory diffs (Nestlin vs Mesen2):")
        appendArrayDiff(sb, "OAM", nestlin.oam, mesen2.oam, sampleCount = 8)
        appendArrayDiff(sb, "paletteRam", nestlin.paletteRam, mesen2.paletteRam, sampleCount = 8)
        appendArrayDiff(sb, "CHR", nestlin.chr, mesen2.chr, sampleCount = 0)
        appendArrayDiff(sb, "cpuRam", nestlin.cpuRam, mesen2.cpuRam, sampleCount = 1)
        sb.appendLine()

        if (classifications.isEmpty()) {
            sb.appendLine("LIKELY CAUSE: (none classified)")
        } else {
            classifications.forEach { sb.appendLine("LIKELY CAUSE: $it") }
        }
        return sb.toString()
    }

    private fun appendArrayDiff(sb: StringBuilder, name: String, a: IntArray, b: IntArray, sampleCount: Int) {
        if (a.size != b.size) {
            sb.appendLine("  %-12s size mismatch: ${a.size} vs ${b.size}".format(name))
            return
        }
        val diffs = a.indices.filter { a[it] != b[it] }
        if (diffs.isEmpty()) {
            sb.appendLine("  %-12s match (${a.size} bytes)".format(name))
        } else {
            val sample = if (sampleCount > 0) {
                " — first ${minOf(sampleCount, diffs.size)}: " + diffs.take(sampleCount).joinToString(", ") {
                    "[0x%03X] N=0x%02X M=0x%02X".format(it, a[it], b[it])
                }
            } else ""
            sb.appendLine("  %-12s ${diffs.size}/${a.size} byte(s) differ$sample".format(name))
        }
    }

    // --------------------------------------------------------------- classify

    /**
     * The decision tree. Multiple rules can fire; output is ordered most -> least
     * specific. Distilled from past sessions — each rule names the historical bug
     * that taught it.
     */
    internal fun classify(n: EmulatorStateSnapshot, m: EmulatorStateSnapshot): List<String> {
        val causes = mutableListOf<String>()

        val oamDiffers = !sameArray(n.oam, m.oam)
        val chrDiffers = !sameArray(n.chr, m.chr)
        val paletteDiffers = !sameArray(n.paletteRam, m.paletteRam)
        val cpuRamDiffers = !sameArray(n.cpuRam, m.cpuRam)
        val maskDiffers = n.ppuRegisters.mask != m.ppuRegisters.mask
        val ctrlDiffers = n.ppuRegisters.controller != m.ppuRegisters.controller
        val nmiKnown = n.nmiCountLastFrame >= 0 && m.nmiCountLastFrame >= 0
        val irqKnown = n.irqCountLastFrame >= 0 && m.irqCountLastFrame >= 0
        val nmiDiffers = nmiKnown && n.nmiCountLastFrame != m.nmiCountLastFrame
        val irqDiffers = irqKnown && n.irqCountLastFrame != m.irqCountLastFrame

        // 1. Boot stall.
        if (n.ppuRegisters.mask == 0 && m.ppuRegisters.mask != 0) {
            causes += "Boot stall: Nestlin never enabled rendering — game is stuck pre-init. " +
                "Compare PC: if Nestlin PC sits in a small loop, find what flag/interrupt it is " +
                "waiting on. Past causes: NMI latch not cleared at pre-render (Gimmick), " +
                "open-bus reads decoding as BRK (Mind Blower Pak)."
        }

        // 2. NMI/frame differs.
        if (nmiDiffers) {
            causes += "NMI dispatch divergence (Nestlin=${n.nmiCountLastFrame}/frame, " +
                "Mesen2=${m.nmiCountLastFrame}/frame) — check vblank latch, \$2002 read " +
                "suppression, NMI 1-instruction latency."
        }

        // 3. IRQ/frame differs.
        if (irqDiffers) {
            causes += "Mapper IRQ timing divergence (Nestlin=${n.irqCountLastFrame}/frame, " +
                "Mesen2=${m.irqCountLastFrame}/frame) — check fire condition placement " +
                "(decrement-to-zero vs reload-to-zero, RAMBO-1 trap), A12 edge counting vs " +
                "CPU-cycle counting."
        }

        // 4/5. OAM rules.
        if (oamDiffers) {
            val shift = detectOamShift(n.oam, m.oam)
            if (shift != null) {
                causes += "OAM uniformly shifted by $shift — OAM DMA target/oamAddress bug " +
                    "(\$4014 must reset oamAddress to 0; Akira #141)."
            } else {
                causes += "CPU-side divergence upstream of sprite setup (OAM is CPU-written) — " +
                    "likely CPU/DMA/timing, NOT the PPU. Note: if the game's NMI handler " +
                    "rewrites OAM, the capture offset makes OAM incomparable (Don Doko Don) — " +
                    "verify with a static screen."
            }
        }

        // 6. OAM matches but CHR differs.
        if (!oamDiffers && chrDiffers) {
            causes += "Mapper CHR banking/fetch bug — wrong bank mapped at capture. Check " +
                "register decode and bank-window arithmetic."
        }

        // 7. Palette differs while OAM + CHR match.
        if (paletteDiffers && !oamDiffers && !chrDiffers) {
            causes += "Palette write path or VRAM address increment bug."
        }

        // 8. Only cpuRam differs.
        val anyRenderDiff = oamDiffers || chrDiffers || paletteDiffers || maskDiffers ||
            ctrlDiffers || nmiDiffers || irqDiffers
        if (cpuRamDiffers && !anyRenderDiff) {
            causes += "RAM divergence without render impact yet — bisect earlier frames to " +
                "find first divergent frame."
        }

        // 9. Everything compared matches.
        if (!anyRenderDiff && !cpuRamDiffers) {
            causes += "No divergence at frame ${n.frameNumber} — diverges later; re-run at a " +
                "later frame, or the bug is timing-only (use replay CLI fingerprints to bisect)."
        }

        return causes
    }

    private fun sameArray(a: IntArray, b: IntArray): Boolean =
        a.size == b.size && a.indices.all { a[it] == b[it] }

    /**
     * Detects whether [nestlin] is the same OAM byte stream as [mesen2] but
     * uniformly rotated by k positions (k in 1..8, either direction) — the
     * signature of an OAM DMA writing to the wrong starting oamAddress
     * (Akira #141: 4 manual writes before the DMA shifted every byte by 4).
     * Returns the shift k, or null if no uniform shift matches. All-equal
     * arrays return null (that is a match, not a shift); degenerate constant
     * arrays would match trivially, so a shift is only reported when the
     * arrays actually differ unrotated.
     */
    internal fun detectOamShift(nestlin: IntArray, mesen2: IntArray): Int? {
        if (nestlin.size != mesen2.size || nestlin.size == 0) return null
        if (sameArray(nestlin, mesen2)) return null
        val size = nestlin.size
        for (k in 1..8) {
            // Nestlin's byte i landed where mesen2 has i+k (or i-k) — check both directions.
            if (nestlin.indices.all { nestlin[(it + k) % size] == mesen2[it] }) return k
            if (nestlin.indices.all { nestlin[it] == mesen2[(it + k) % size] }) return k
        }
        return null
    }
}
