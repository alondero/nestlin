package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Diagnostic boot for *Mind Blower Pak* (Mapper 113 / HES NTD-8) — a
 * first-pass look at whether the game's PRG/CHR/mirroring/NMI machinery
 * is actually getting through the new mapper. Prints tagged
 * `[DEBUG-mb-boot]` output every frame for the first few frames, then
 * once per 60 frames. Captures final PC, instruction count, PPU mask,
 * PPU ctrl, PRG/CHR banks, and NMI count.
 *
 * ROM path defaults to the canonical NO-INTRO location; override with
 * `NESTLIN_MIND_BLOWER_PAK_ROM` if running on a machine without the
 * full NO-INTRO library.
 */
class Mapper113BootDiagnosticTest {

    private fun romPath(): Path {
        val override = System.getenv("NESTLIN_MIND_BLOWER_PAK_ROM")
        if (!override.isNullOrBlank()) return Paths.get(override)
        return Paths.get("S:/Media/Nintendo NES/Games/Mind Blower Pak (Australia) (Unl).nes")
    }

    @Test
    fun `mind blower pak boot diagnostic`() {
        val rom = romPath()
        assumeTrue(Files.exists(rom), "ROM not found at $rom")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as Mapper113
        val ppu = nestlin.memory.ppuAddressedMemory
        val oam = ppu.objectAttributeMemory

        val startInstr = nestlin.cpu.getInstructionCount()
        // 6502 vectors are little-endian: low byte at $FFFC, high byte at $FFFD.
        val resetVector = (nestlin.memory[0xFFFD].toUnsignedInt() shl 8) or
            nestlin.memory[0xFFFC].toUnsignedInt()
        println("[DEBUG-mb-boot] ROM: $rom")
        println("[DEBUG-mb-boot] region: ${nestlin.currentRegion()}")
        println("[DEBUG-mb-boot] reset vector: $${resetVector.toString(16).padStart(4, '0')}")
        println(
            "[DEBUG-mb-boot] PRG0[0..3] = " +
                "$${(nestlin.memory[0x8000].toUnsignedInt()).toString(16).padStart(2, '0')} " +
                "$${(nestlin.memory[0x8001].toUnsignedInt()).toString(2).padStart(8, '0')}"
        )

        var frameCount = 0
        val maxFrames = 240
        val earlyFrames = 5
        val sampleEvery = 60
        val oamNonZero = mutableSetOf<Int>()
        val visitedPrgBanks = mutableSetOf<Int>()
        val visitedChrBanks = mutableSetOf<Int>()

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                val instr = nestlin.cpu.getInstructionCount()
                val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                val ppuMask = ppu.mask.register.toUnsignedInt()
                val ppuCtrl = ppu.controller.register.toUnsignedInt()
                val snap = mapper.snapshot()
                val prg = snap.banks["prgBank"] ?: -1
                val chr = snap.banks["chrBank"] ?: -1
                visitedPrgBanks += prg
                visitedChrBanks += chr
                // Sample OAM (256 bytes). First hit per byte index is enough.
                for (i in 0 until 256) {
                    if (oam[i].toUnsignedInt() != 0) oamNonZero += i
                }
                if (frameCount < earlyFrames || frameCount % sampleEvery == 0) {
                    println(
                        "[DEBUG-mb-boot] frame=$frameCount instr=$instr pc=$${pc.toString(16).padStart(4, '0')} " +
                            "ppumask=$${ppuMask.toString(16).padStart(2, '0')} ppuctrl=$${ppuCtrl.toString(16).padStart(2, '0')} " +
                            "prg=$prg chr=$chr oamNonZero=${oamNonZero.size}"
                    )
                }
                frameCount++
                if (frameCount >= maxFrames) nestlin.stop()
            }
        })
        nestlin.start()

        val totalInstr = nestlin.cpu.getInstructionCount() - startInstr
        val finalPc = nestlin.cpu.registers.programCounter.toUnsignedInt()
        val finalPpuMask = ppu.mask.register.toUnsignedInt()
        val finalPpuCtrl = ppu.controller.register.toUnsignedInt()
        val finalPrg = mapper.snapshot().banks["prgBank"] ?: -1
        val finalChr = mapper.snapshot().banks["chrBank"] ?: -1
        println(
            "[DEBUG-mb-boot] FINAL frame=$frameCount totalInstr=$totalInstr (avg ${totalInstr / maxFrames}/frame) " +
                "pc=$${finalPc.toString(16).padStart(4, '0')} " +
                "ppumask=$${finalPpuMask.toString(16).padStart(2, '0')} ppuctrl=$${finalPpuCtrl.toString(16).padStart(2, '0')} " +
                "prg=$finalPrg chr=$finalChr oamNonZero=${oamNonZero.size}"
        )
        println(
            "[DEBUG-mb-boot] PPUMASK bit3 (show bg) = ${(finalPpuMask and 0x08) != 0}, " +
                "bit4 (show sprites) = ${(finalPpuMask and 0x10) != 0}, " +
                "bits 0-2 (palette) = ${finalPpuMask and 0x07}"
        )
        println(
            "[DEBUG-mb-boot] PPUCTRL bit7 (NMI enable) = ${(finalPpuCtrl and 0x80) != 0}, " +
                "bit5 (8x16 sprites) = ${(finalPpuCtrl and 0x20) != 0}, " +
                "bit4 (bg pattern table) = ${(finalPpuCtrl and 0x10) != 0}"
        )
        println("[DEBUG-mb-boot] PRG banks seen: $visitedPrgBanks")
        println("[DEBUG-mb-boot] CHR banks seen: $visitedChrBanks")
    }
}
