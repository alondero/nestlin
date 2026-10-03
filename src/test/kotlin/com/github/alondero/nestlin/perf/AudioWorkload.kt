package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.testutil.AudioTestFixture
import java.security.MessageDigest
import java.util.HexFormat

/** Benchmark reporting; correctness owns the shared stimulus in testutil/. */
object AudioWorkload {
    fun fingerprint(region: Region, expansion: Float): Pair<String, String> {
        val (pcm, state) = AudioTestFixture.capture(region, expansion)
        val hex = HexFormat.of()
        fun hash(bytes: ByteArray): String = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return hash(pcm) to hash(state)
    }
}
