package com.github.alondero.nestlin.apu

/** Host PCM conversion shared by playback and the device benchmark. */
internal object PcmEncoder {
    fun encode(samples: ShortArray, count: Int, output: ByteArray, bits: Int, bigEndian: Boolean): Int {
        require(count in 0..samples.size)
        require(bits == 8 || bits == 16)
        val bytesPerSample = bits / 8
        require(count <= output.size / bytesPerSample)
        if (bits == 16) {
            if (bigEndian) {
                for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    output[i * 2] = (sample shr 8).toByte()
                    output[i * 2 + 1] = (sample and 0xFF).toByte()
                }
            } else {
                for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    output[i * 2] = (sample and 0xFF).toByte()
                    output[i * 2 + 1] = (sample shr 8).toByte()
                }
            }
        } else {
            for (i in 0 until count) output[i] = ((samples[i].toInt() + 32768) shr 8).toByte()
        }
        return count * bytesPerSample
    }
}
