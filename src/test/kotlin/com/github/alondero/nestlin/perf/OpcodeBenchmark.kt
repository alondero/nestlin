package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.cpu.opcode.Opcode
import com.github.alondero.nestlin.cpu.opcode.OpcodesRefactor
import com.github.alondero.nestlin.cpu.opcode.Kil
import com.github.alondero.nestlin.testutil.TestRoms
import com.sun.management.ThreadMXBean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/** Manual dispatch benchmark and baseline/candidate fingerprints. See docs/PERFORMANCE.md. */
object OpcodeBenchmark {
    private const val LOOKUP_REPEATS = 64
    private var checksum = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val samples = args.getOrNull(0)?.toInt() ?: 600
        val warmup = args.getOrNull(1)?.toInt() ?: 300
        require(samples > 0 && warmup >= 0)
        val lines = requireNotNull(javaClass.getResourceAsStream("/nestest.log"))
            .bufferedReader().use { it.readLines() }
        val trace = lines.map { it.substring(6, 8).toInt(16) }.toIntArray()
        println("nestest instructions=${trace.size} distinct=${trace.toSet().size} " +
            "unofficial=${lines.count { '*' in it }} high-byte=${trace.count { it >= 128 }}")
        val counters = Counters()
        val map = OpcodesRefactor.map
        val table = Array<Opcode?>(256) { map[it] }
        for (code in 0..255) check(map[code] === table[code] && map[code] === OpcodesRefactor[code])
        val lookupCount = trace.size.toLong() * LOOKUP_REPEATS
        val mapTimes = LongArray(samples)
        val arrayTimes = LongArray(samples)
        val mapCpuTimes = LongArray(samples)
        val arrayCpuTimes = LongArray(samples)
        val mapBytes = LongArray(samples)
        val arrayBytes = LongArray(samples)
        // Alternate order to avoid always giving one lookup the warmer host/JIT.
        repeat(warmup + samples) { i ->
            fun measureMap() = counters.measure { lookupMap(trace, map) }
            fun measureArray() = counters.measure { lookupArray(trace, table) }
            val first = if (i % 2 == 0) measureMap() else measureArray()
            val second = if (i % 2 == 0) measureArray() else measureMap()
            check(first.checksum == second.checksum)
            if (i >= warmup) {
                val m = if (i % 2 == 0) first else second
                val a = if (i % 2 == 0) second else first
                mapTimes[i - warmup] = m.nanos
                mapBytes[i - warmup] = m.bytes
                arrayTimes[i - warmup] = a.nanos
                arrayBytes[i - warmup] = a.bytes
                mapCpuTimes[i - warmup] = m.cpuNanos
                arrayCpuTimes[i - warmup] = a.cpuNanos
            }
        }
        report("lookup-map", mapTimes, mapCpuTimes, mapBytes, lookupCount, counters)
        report("lookup-array", arrayTimes, arrayCpuTimes, arrayBytes, lookupCount, counters)

        val emu = nestest()
        val initial = save(emu)
        val recorded = emu.cpu.enableInstructionTrace(trace.size)
        // The current dispatcher intentionally maps $C3 to KIL, so the ROM cannot
        // execute the complete reference log. Measure its actual executable prefix.
        while (!emu.cpu.idle || emu.cpu.executionInFlight) {
            check(emu.cpu.cycleCount < 100_000) {
                "nestest did not finish: instructions=${emu.cpu.getInstructionCount()} " +
                    "PC=${emu.cpu.getCurrentPc()} idle=${emu.cpu.idle} last=${recorded.takeLast(5)}"
            }
            emu.cpu.tick()
        }
        check(recorded.last().second == 0xC3 && OpcodesRefactor[0xC3] is Kil) { "unexpected nestest halt" }
        val executed = recorded.size
        println("cpu-nestest instructions=$executed distinct=${recorded.map { it.second }.toSet().size} stop=KIL-C3")
        val cycles = emu.cpu.cycleCount
        emu.cpu.disableInstructionTrace()
        val times = LongArray(samples)
        val cpuTimes = LongArray(samples)
        val bytes = LongArray(samples)
        repeat(warmup + samples) { i ->
            emu.loadState(ByteArrayInputStream(initial))
            val measurement = counters.measure {
                repeat(cycles) { emu.cpu.tick() }
                emu.cpu.registers.accumulator.toLong()
            }
            if (i >= warmup) {
                times[i - warmup] = measurement.nanos
                bytes[i - warmup] = measurement.bytes
                cpuTimes[i - warmup] = measurement.cpuNanos
            }
        }
        report("cpu-nestest cycles=$cycles", times, cpuTimes, bytes, executed.toLong(), counters)
        // Run correctness evidence separately: tracing, hashing and copies are never timed.
        fingerprint("nestest", nestest(), cycles = cycles)
        fingerprint("NTSC-mixed-render", mixed(Region.NTSC), frames = 120)
        fingerprint("PAL-mixed-render", mixed(Region.PAL), frames = 120)
        println("checksum=$checksum")
    }

    private fun lookupMap(trace: IntArray, map: Map<Int, Opcode>): Long {
        var sum = 0L
        repeat(LOOKUP_REPEATS) {
            for (code in trace) sum += map[code]?.cycles ?: 0
        }
        return sum
    }

    private fun lookupArray(trace: IntArray, table: Array<Opcode?>): Long {
        var sum = 0L
        repeat(LOOKUP_REPEATS) {
            for (code in trace) sum += table.getOrNull(code)?.cycles ?: 0
        }
        return sum
    }

    private fun nestest(): Nestlin = Nestlin().apply {
        config.rewindEnabled = false
        loadBytes(TestRoms.nestestBytes())
        powerReset()
        repeat(7) { stepCpuCycle() }
    }

    /** A synthetic game-style kernel: indexed loads/stores, ALU, stack, branches and unofficial RMW. */
    private fun mixed(region: Region): Nestlin = RenderingWorkload.create(region).apply {
        val program = intArrayOf(
            0xA2, 0x0F,             // LDX #15
            0xA0, 0x03,             // LDY #3
            0xB5, 0x40,             // LDA $40,X
            0x18,                   // CLC
            0x69, 0x03,             // ADC #3
            0x95, 0x40,             // STA $40,X
            0xA7, 0x40,             // LAX $40
            0x07, 0x41,             // SLO $41
            0x67, 0x42,             // RRA $42
            0xC7, 0x43,             // DCP $43
            0xE7, 0x44,             // ISC $44
            0x87, 0x45,             // SAX $45
            0x48, 0x68,             // PHA / PLA
            0x49, 0xA5,             // EOR #$A5
            0x2A,                   // ROL A
            0xB9, 0xFF, 0x03,       // LDA $03FF,Y (page crossing)
            0x99, 0xFF, 0x04,       // STA $04FF,Y (dummy read)
            0xE6, 0x46,             // INC $46
            0x20, 0x80, 0x02,       // JSR $0280
            0xCA,                   // DEX
            0xD0, 0xDA,             // BNE $0204
            0x4C, 0x00, 0x02,       // JMP $0200
        )
        program.forEachIndexed { i, byte -> cpu.memory[0x0200 + i] = byte.toByte() }
        intArrayOf(0xC8, 0x88, 0x60).forEachIndexed { i, byte -> cpu.memory[0x0280 + i] = byte.toByte() }
        repeat(256) { cpu.memory[0x0300 + it] = (it * 17).toByte() }
        cpu.registers.programCounter = 0x0200
    }

    private fun fingerprint(name: String, emu: Nestlin, cycles: Int = 0, frames: Int = 0) {
        val bus = MessageDigest.getInstance("SHA-256")
        val audio = MessageDigest.getInstance("SHA-256")
        val pixels = MessageDigest.getInstance("SHA-256")
        var accesses = 0L
        emu.cpu.memory.cpuBusObserver = {
            val cycle = emu.cpu.cycleCount
            for (shift in 24 downTo 0 step 8) bus.update((cycle ushr shift).toByte())
            bus.update(it.operation.ordinal.toByte())
            bus.update((it.address ushr 8).toByte())
            bus.update(it.address.toByte())
            bus.update(it.value)
            accesses++
        }
        fun outputs() {
            for (sample in emu.getAudioSamples()) {
                audio.update((sample.toInt() ushr 8).toByte())
                audio.update(sample.toByte())
            }
            for (row in emu.ppu.publishedFrame.scanlines) for (pixel in row) {
                pixels.update((pixel ushr 16).toByte())
                pixels.update((pixel ushr 8).toByte())
                pixels.update(pixel.toByte())
            }
        }
        repeat(cycles) { emu.stepCpuCycle() }
        if (cycles > 0) outputs()
        repeat(frames) {
            RenderingWorkload.runFrame(emu)
            outputs()
        }
        emu.cpu.memory.cpuBusObserver = null
        val hex = HexFormat.of()
        println("$name cycles=${emu.cpu.cycleCount} instructions=${emu.cpu.getInstructionCount()} bus-accesses=$accesses")
        println("state=${hex.formatHex(MessageDigest.getInstance("SHA-256").digest(save(emu)))} " +
            "bus=${hex.formatHex(bus.digest())} frame=${hex.formatHex(pixels.digest())} " +
            "audio=${hex.formatHex(audio.digest())}")
    }

    private fun save(emu: Nestlin): ByteArray = ByteArrayOutputStream().also { emu.saveState(it) }.toByteArray()

    private class Counters {
        private val bean = ManagementFactory.getThreadMXBean()
        private val allocationBean = bean as? ThreadMXBean
        val cpuTime = bean.isCurrentThreadCpuTimeSupported
        val allocation = allocationBean?.isThreadAllocatedMemorySupported == true
        private val thread = Thread.currentThread().threadId()

        init {
            if (cpuTime) bean.isThreadCpuTimeEnabled = true
            if (allocation) requireNotNull(allocationBean).isThreadAllocatedMemoryEnabled = true
        }

        private fun cpuNanos(): Long = if (cpuTime) bean.currentThreadCpuTime else 0
        private fun bytes(): Long = if (allocation) requireNotNull(allocationBean).getThreadAllocatedBytes(thread) else 0

        fun measure(block: () -> Long): Measurement {
            val beforeBytes = bytes()
            val beforeCpu = cpuNanos()
            val start = System.nanoTime()
            val value = block()
            val nanos = System.nanoTime() - start
            val cpu = cpuNanos() - beforeCpu
            val allocated = bytes() - beforeBytes
            checksum += value // consume results outside the measured section
            return Measurement(nanos, cpu, allocated, value)
        }
    }

    private data class Measurement(val nanos: Long, val cpuNanos: Long, val bytes: Long, val checksum: Long)

    private fun report(name: String, times: LongArray, cpuTimes: LongArray, bytes: LongArray, operations: Long, counters: Counters) {
        times.sort()
        val allocation = if (counters.allocation) String.format(Locale.ROOT, "%.3f", bytes.sum().toDouble() / times.size / operations) else "unavailable"
        // Aggregate CPU time: Windows thread CPU counters can quantize individual short samples to zero.
        val cpu = if (counters.cpuTime) String.format(Locale.ROOT, "%.3f", cpuTimes.sum().toDouble() / times.size / operations) else "unavailable"
        println(String.format(Locale.ROOT,
            "%s wall-median=%.3fns/instruction wall-p95=%.3fns/instruction cpu-mean=%sns/instruction bytes/instruction=%s",
            name, times[times.size / 2].toDouble() / operations,
            times[(times.size * .95).toInt()].toDouble() / operations, cpu, allocation))
    }
}
