package com.github.alondero.nestlin.compare

import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares two EmulatorStateSnapshot objects and produces detailed mismatch reports.
 */
object StateComparator {

    fun compare(nestlin: EmulatorStateSnapshot, mesen2: EmulatorStateSnapshot): StateDiffResult {
        val cpuMismatches = compareCpu(nestlin.cpu, mesen2.cpu)
        val ppuMismatches = comparePpu(nestlin.ppu, mesen2.ppu)
        val memoryMismatches = compareMemory(nestlin, mesen2)

        val match = cpuMismatches.isEmpty() &&
                ppuMismatches.isEmpty() &&
                memoryMismatches.isEmpty()

        val message = buildMessage(nestlin, mesen2, cpuMismatches, ppuMismatches, memoryMismatches)

        return StateDiffResult(
            match = match,
            cpuMismatches = cpuMismatches,
            ppuMismatches = ppuMismatches,
            memoryMismatches = memoryMismatches,
            message = message
        )
    }

    private fun compareCpu(nestlin: CpuState, mesen2: CpuState): List<CpuMismatch> {
        val mismatches = mutableListOf<CpuMismatch>()

        if (nestlin.pc != mesen2.pc) {
            mismatches.add(CpuMismatch("PC", nestlin.pc, mesen2.pc))
        }
        if (nestlin.a != mesen2.a) {
            mismatches.add(CpuMismatch("A", nestlin.a, mesen2.a))
        }
        if (nestlin.x != mesen2.x) {
            mismatches.add(CpuMismatch("X", nestlin.x, mesen2.x))
        }
        if (nestlin.y != mesen2.y) {
            mismatches.add(CpuMismatch("Y", nestlin.y, mesen2.y))
        }
        if (nestlin.sp != mesen2.sp) {
            mismatches.add(CpuMismatch("SP", nestlin.sp, mesen2.sp))
        }
        if (nestlin.status != mesen2.status) {
            mismatches.add(CpuMismatch("Status", nestlin.status, mesen2.status))
        }
        if (nestlin.cycleCount != mesen2.cycleCount) {
            mismatches.add(CpuMismatch("cycleCount", nestlin.cycleCount.toInt(), mesen2.cycleCount.toInt()))
        }

        return mismatches
    }

    private fun comparePpu(nestlin: PpuState, mesen2: PpuState): List<PpuMismatch> {
        val mismatches = mutableListOf<PpuMismatch>()

        if (nestlin.cycle != mesen2.cycle) {
            mismatches.add(PpuMismatch("cycle", nestlin.cycle, mesen2.cycle))
        }
        if (nestlin.scanline != mesen2.scanline) {
            mismatches.add(PpuMismatch("scanline", nestlin.scanline, mesen2.scanline))
        }
        if (nestlin.frameCount != mesen2.frameCount) {
            mismatches.add(PpuMismatch("frameCount", nestlin.frameCount, mesen2.frameCount))
        }
        if (nestlin.control != mesen2.control) {
            mismatches.add(PpuMismatch("control", nestlin.control, mesen2.control))
        }
        if (nestlin.mask != mesen2.mask) {
            mismatches.add(PpuMismatch("mask", nestlin.mask, mesen2.mask))
        }
        if (nestlin.status != mesen2.status) {
            mismatches.add(PpuMismatch("status", nestlin.status, mesen2.status))
        }

        return mismatches
    }

    private fun compareMemory(nestlin: EmulatorStateSnapshot, mesen2: EmulatorStateSnapshot): List<MemoryMismatch> {
        val mismatches = mutableListOf<MemoryMismatch>()

        // Compare CPU RAM - report first mismatch per region
        val cpuRamMismatch = findFirstMismatch(nestlin.cpuRam, mesen2.cpuRam, "cpuRam")
        if (cpuRamMismatch != null) mismatches.add(cpuRamMismatch)

        // Compare OAM
        val oamMismatch = findFirstMismatch(nestlin.oam, mesen2.oam, "oam")
        if (oamMismatch != null) mismatches.add(oamMismatch)

        // Compare Palette RAM
        val paletteMismatch = findFirstMismatch(nestlin.paletteRam, mesen2.paletteRam, "paletteRam")
        if (paletteMismatch != null) mismatches.add(paletteMismatch)

        // Compare PPU registers
        val ppuRegsMismatch = comparePpuRegisters(nestlin.ppuRegisters, mesen2.ppuRegisters)
        if (ppuRegsMismatch != null) mismatches.add(ppuRegsMismatch)

        return mismatches
    }

    private fun findFirstMismatch(nestlin: IntArray, mesen2: IntArray, region: String): MemoryMismatch? {
        val minLen = minOf(nestlin.size, mesen2.size)
        for (i in 0 until minLen) {
            if (nestlin[i] != mesen2[i]) {
                return MemoryMismatch(region, i, nestlin[i], mesen2[i])
            }
        }
        if (nestlin.size != mesen2.size) {
            return MemoryMismatch(region, minLen, -1, -1) // Size mismatch
        }
        return null
    }

    private fun comparePpuRegisters(nestlin: PpuRegisters, mesen2: PpuRegisters): MemoryMismatch? {
        if (nestlin.controller != mesen2.controller) {
            return MemoryMismatch("ppuRegisters.controller", 0x2000, nestlin.controller, mesen2.controller)
        }
        if (nestlin.mask != mesen2.mask) {
            return MemoryMismatch("ppuRegisters.mask", 0x2001, nestlin.mask, mesen2.mask)
        }
        if (nestlin.status != mesen2.status) {
            return MemoryMismatch("ppuRegisters.status", 0x2002, nestlin.status, mesen2.status)
        }
        if (nestlin.oamAddress != mesen2.oamAddress) {
            return MemoryMismatch("ppuRegisters.oamAddress", 0x2003, nestlin.oamAddress, mesen2.oamAddress)
        }
        if (nestlin.oamData != mesen2.oamData) {
            return MemoryMismatch("ppuRegisters.oamData", 0x2004, nestlin.oamData, mesen2.oamData)
        }
        if (nestlin.scroll != mesen2.scroll) {
            return MemoryMismatch("ppuRegisters.scroll", 0x2005, nestlin.scroll, mesen2.scroll)
        }
        if (nestlin.address != mesen2.address) {
            return MemoryMismatch("ppuRegisters.address", 0x2006, nestlin.address, mesen2.address)
        }
        if (nestlin.data != mesen2.data) {
            return MemoryMismatch("ppuRegisters.data", 0x2007, nestlin.data, mesen2.data)
        }
        return null
    }

    private fun buildMessage(
        nestlin: EmulatorStateSnapshot,
        mesen2: EmulatorStateSnapshot,
        cpuMismatches: List<CpuMismatch>,
        ppuMismatches: List<PpuMismatch>,
        memoryMismatches: List<MemoryMismatch>
    ): String {
        if (cpuMismatches.isEmpty() && ppuMismatches.isEmpty() && memoryMismatches.isEmpty()) {
            return "State match: Nestlin and Mesen2 are in sync at frame ${nestlin.frameNumber}"
        }

        val sb = StringBuilder()
        sb.appendLine("State mismatch for ${nestlin.romName} at frame ${nestlin.frameNumber}")
        sb.appendLine()

        if (cpuMismatches.isNotEmpty()) {
            sb.appendLine("=== CPU STATE ===")
            for (m in cpuMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        if (ppuMismatches.isNotEmpty()) {
            sb.appendLine("=== PPU STATE ===")
            for (m in ppuMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        if (memoryMismatches.isNotEmpty()) {
            sb.appendLine("=== MEMORY STATE ===")
            for (m in memoryMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        val totalMismatches = cpuMismatches.size + ppuMismatches.size + memoryMismatches.size
        sb.appendLine("SUMMARY: $totalMismatches mismatch(es)")
        if (cpuMismatches.isNotEmpty()) {
            sb.appendLine("  ${cpuMismatches.size} CPU, ${ppuMismatches.size} PPU, ${memoryMismatches.size} memory")
        }

        return sb.toString().trimEnd()
    }

    fun writeReport(diff: StateDiffResult, nestlin: EmulatorStateSnapshot, mesen2: EmulatorStateSnapshot, outputPath: Path) {
        val report = buildReport(diff, nestlin, mesen2)
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, report)
    }

    private fun buildReport(diff: StateDiffResult, nestlin: EmulatorStateSnapshot, mesen2: EmulatorStateSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("State Snapshot Comparison Report")
        sb.appendLine("==============================")
        sb.appendLine("ROM: ${nestlin.romName}")
        sb.appendLine("Frame: ${nestlin.frameNumber}")
        sb.appendLine("Timestamp: ${nestlin.timestamp}")
        sb.appendLine()
        sb.appendLine("EMULATORS: ${nestlin.emulator} vs ${mesen2.emulator}")
        sb.appendLine()

        if (diff.match) {
            sb.appendLine("RESULT: MATCH")
            return sb.toString()
        }

        sb.appendLine("RESULT: MISMATCH")
        sb.appendLine()

        if (diff.cpuMismatches.isNotEmpty()) {
            sb.appendLine("=== CPU STATE ===")
            for (m in diff.cpuMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        if (diff.ppuMismatches.isNotEmpty()) {
            sb.appendLine("=== PPU STATE ===")
            for (m in diff.ppuMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        if (diff.memoryMismatches.isNotEmpty()) {
            sb.appendLine("=== MEMORY STATE ===")
            for (m in diff.memoryMismatches) {
                sb.appendLine(m.format())
            }
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("See full snapshots:")
        sb.appendLine("  build/reports/state-diffs/${nestlin.romName}-frame-${nestlin.frameNumber}/nestlin-state.json")
        sb.appendLine("  build/reports/state-diffs/${nestlin.romName}-frame-${nestlin.frameNumber}/mesen2-state.json")

        return sb.toString()
    }
}
