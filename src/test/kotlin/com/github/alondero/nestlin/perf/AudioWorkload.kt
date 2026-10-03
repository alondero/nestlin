package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.apu.DmaPort
import com.github.alondero.nestlin.apu.ExpansionAudioChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Hermetic register-driven audio, including real DMC fetches from a deterministic DMA port. */
object AudioWorkload {
    fun create(region: Region, expansion: Float = 0.0f): Apu {
        val apu = Apu(object : DmaPort {
            override fun get(address: Int): Byte = ((address * 73) xor (address ushr 3)).toByte()
        })
        apu.region = region
        fun write(address: Int, value: Int) = apu.handleRegisterWrite(address, value.toByte())
        write(0x15, 0x0F)
        write(0x00, 0xBF); write(0x02, 0xFD); write(0x03, 0x08)
        write(0x04, 0x7A); write(0x06, 0xA1); write(0x07, 0x09)
        write(0x08, 0xFF); write(0x0A, 0x71); write(0x0B, 0x08)
        write(0x0C, 0x3C); write(0x0E, 0x04); write(0x0F, 0x08)
        write(0x10, 0x4F); write(0x11, 0x40); write(0x12, 0x32); write(0x13, 0x08)
        write(0x15, 0x1F)
        if (expansion != 0.0f) apu.registerExpansionChannel(object : ExpansionAudioChannel {
            private var cycles = 0
            override fun tick(cycles: Int) { this.cycles += cycles }
            override fun currentSample(): Float = if ((cycles / 701) % 2 == 0) expansion else 0.0f
        })
        return apu
    }

    fun fingerprint(region: Region, expansion: Float): Pair<String, String> {
        val apu = create(region, expansion)
        val digest = MessageDigest.getInstance("SHA-256")
        val output = ShortArray(apu.audioBufferCapacity())
        var saved = ByteArray(0)
        repeat(45) { frame ->
            apu.outputMuted = frame in 8..12
            if (frame % 3 == 0) apu.handleRegisterWrite(0x11, (frame * 19 and 127).toByte())
            if (frame == 15) saved = state(apu)
            if (frame == 20) apu.loadState(DataInputStream(ByteArrayInputStream(saved)))
            repeat((region.cpuFrequencyHz / 60).toInt()) { apu.tick() }
            // A deliberate backlog beyond capacity exercises drop-oldest and ring wrap.
            if (frame !in 25..38) {
                val count = apu.getAudioSamples(output)
                for (i in 0 until count) {
                    digest.update((output[i].toInt() ushr 8).toByte())
                    digest.update(output[i].toByte())
                }
            }
        }
        val hex = HexFormat.of()
        return hex.formatHex(digest.digest()) to hex.formatHex(MessageDigest.getInstance("SHA-256").digest(state(apu)))
    }

    private fun state(apu: Apu): ByteArray = ByteArrayOutputStream().also {
        apu.saveState(DataOutputStream(it))
    }.toByteArray()
}
