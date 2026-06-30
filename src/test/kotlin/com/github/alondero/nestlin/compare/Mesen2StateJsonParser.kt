package com.github.alondero.nestlin.compare

/**
 * Regex-based JSON parser for the structured state dumps written by the
 * Lua script in [Mesen2StateCapturer] (and the long-running server script
 * in [Mesen2ProcessInstance]).
 *
 * The Phase 0 spike settled on flat-keyed JSON in the `{idx:val,...}` shape
 * for arrays (no nesting) because Lua's `io.write` of nested structures is
 * brittle. The trade-off is regex parsing in Kotlin — see
 * `docs/TESTING_STRATEGY.md:197-203` for why a real Lua JSON library is a
 * Phase 3 follow-up, not this one.
 *
 * This class is intentionally not an `object` singleton — it's a stateless
 * namespace of static functions. Tests that need to validate the raw JSON
 * format can call these directly.
 *
 * Why extract this from [Mesen2StateCapturer]? So [Mesen2ProcessInstance]
 * (the new long-running Mesen2 wrapper for issue #61) can parse the JSON
 * produced by its Lua server script without having to instantiate the old
 * per-test state capturer.
 */
internal object Mesen2StateJsonParser {

    /**
     * Build a full [EmulatorStateSnapshot] from the JSON dump emitted by
     * the Lua server script. Field semantics match the legacy capturer:
     *
     * - PPUCTRL/MASK/STATUS are already rebuilt by Lua from decomposed
     *   `getState()` booleans (see `Mesen2StateCapturer.kt:96-124`).
     * - The `nmiCountLastFrame`/`irqCountLastFrame` fields count dispatches
     *   during the just-completed frame N. (Lua resets these counters
     *   AFTER capture so the next capture's count is for its own frame.)
     */
    fun parseMesen2State(json: String, romName: String, frameNumber: Int): EmulatorStateSnapshot {
        return EmulatorStateSnapshot(
            emulator = "Mesen2",
            romName = romName,
            frameNumber = frameNumber,
            cpu = parseCpuState(json),
            ppu = parsePpuState(json),
            cpuRam = parseIntArray(json, "cpuRam", 0x800),
            ppuRegisters = parsePpuRegisters(json),
            oam = parseIntArray(json, "oam", 256),
            paletteRam = parseIntArray(json, "paletteRam", 32),
            timestamp = System.currentTimeMillis(),
            chr = parseIntArray(json, "chr", 8192),
            nmiCountLastFrame = parseIntOrDefault(json, "nmiCountLastFrame", -1),
            irqCountLastFrame = parseIntOrDefault(json, "irqCountLastFrame", -1)
        )
    }

    fun parseIntOrDefault(json: String, key: String, default: Int): Int {
        val match = Regex("\"$key\":(-?\\d+)").find(json) ?: return default
        return match.groupValues[1].toIntOrNull() ?: default
    }

    fun parseInt(json: String, key: String): Int {
        val match = Regex("\"$key\":(-?\\d+)").find(json)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun parseLong(json: String, key: String): Long {
        val match = Regex("\"$key\":(-?\\d+)").find(json)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    /**
     * Parses a `"{idx:val,idx:val,...}"` array into an IntArray of size [size].
     * Missing indices default to 0. Used for `cpuRam`, `oam`, `chr`, `paletteRam`.
     */
    fun parseIntArray(json: String, key: String, size: Int): IntArray {
        val arr = IntArray(size)
        val pattern = "\"$key\":\\{([0-9:,]*)\\}"
        val match = Regex(pattern).find(json) ?: return arr
        val pairs = match.groupValues[1].split(",")
        for (pair in pairs) {
            if (!pair.contains(":")) continue
            val parts = pair.split(":")
            val idx = parts[0].toIntOrNull() ?: continue
            val value = parts[1].toIntOrNull() ?: continue
            if (idx in 0 until size) arr[idx] = value
        }
        return arr
    }

    private fun parseCpuState(json: String): CpuState = CpuState(
        pc = parseInt(json, "pc"),
        a = parseInt(json, "a"),
        x = parseInt(json, "x"),
        y = parseInt(json, "y"),
        sp = parseInt(json, "sp"),
        status = parseInt(json, "status"),
        cycleCount = parseLong(json, "cycleCount")
    )

    private fun parsePpuState(json: String): PpuState = PpuState(
        cycle = parseInt(json, "ppuCycle"),
        scanline = parseInt(json, "scanline"),
        frameCount = parseInt(json, "ppuFrameCount"),
        control = parseInt(json, "control"),
        mask = parseInt(json, "mask"),
        status = parseInt(json, "ppuStatus"),
        vRamAddress = 0
    )

    private fun parsePpuRegisters(json: String): PpuRegisters = PpuRegisters(
        controller = parseInt(json, "control"),
        mask = parseInt(json, "mask"),
        status = parseInt(json, "ppuStatus"),
        oamAddress = 0,
        oamData = 0,
        scroll = 0,
        address = 0,
        data = 0
    )
}
