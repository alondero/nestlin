package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.testutil.AudioTestFixture
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioPcmEquivalenceTest {
    @Test
    fun `PCM and saved state match the pre-324 pipeline including DMC expansion mute clipping and backlog`() {
        // Captured before changing production code, with the fixed AudioTestFixture register script.
        val pcm = arrayOf(
            arrayOf("27bfca12b3c1bb6358c3f23191a8a576c66c1742bbc8b7cb35c16b7a8feeef6a",
                "a6240a6f467ef91e2fb1091fdf00691f27ee887b12f80472ba939b19b8c04a33",
                "f5b9e24af780d8d143734fd321a630737678b242c30438c808469b89e237ba57"),
            arrayOf("d69e6a5e4b5e2f337914b7fb7d9cf9ae887e51a055d458694507ea67641f68c4",
                "5f2a88420739e551319312ec1b6dc71b1f77b6501d894269d3dc0190dd4f3ccd",
                "c6444ff80370558e995362b2292ddb1e6b503690f1eb51031bce6c8800f1a33e")
        )
        val state = arrayOf("c52b95d0e1e2e448e837e1a542e350f84f69818f1e244aefa4f442a421981658",
            "d63103f920f3fa44f5ff8dce29c4d59858fbd377027feab27164335ac0620f14")
        for (region in Region.entries) for ((index, expansion) in floatArrayOf(0.0f, 1.0f, 20.0f).withIndex()) {
            val (pcmBytes, stateBytes) = AudioTestFixture.capture(region, expansion)
            fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val actual = hash(pcmBytes) to hash(stateBytes)
            assertEquals(pcm[region.ordinal][index] to state[region.ordinal], actual, "$region expansion=$expansion")
        }
    }
}
