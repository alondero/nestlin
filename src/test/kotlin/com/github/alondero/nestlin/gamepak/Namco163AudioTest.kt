package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Unit tests for [Namco163Audio]. The audio class is a single
 * [com.github.alondero.nestlin.apu.ExpansionAudioChannel] that internally
 * round-robins through 8 wavetable channels. These tests cover the data
 * port, address port, sound-enable, the per-channel register reads, the
 * 15-cycle update cadence, and the bipolar sample output.
 *
 * The 128-byte internal RAM is a key concept: channel registers live at
 * $40-$7F (8 bytes × 8 channels), and **byte $7F bits 4-6 set the active
 * channel count**. With $7F=0 (the default) only channel 8 (index 7) is
 * active; setting $7F to 0x10 enables 2 channels (7 and 6), $7F=0x70
 * enables all 8. The [seedChannel] helper sets this for multi-channel tests.
 */
class Namco163AudioTest {

    private fun b(i: Int): Byte = i.toByte()

    // ---- Data port / address port ----

    @Test
    fun `address port sets the cursor and a data port write goes there`() {
        val a = Namco163Audio()
        a.writeAddressPort(0x40)             // cursor at channel 1 base
        a.writeDataPort(b(0x12))             // RAM[0x40] = 0x12
        a.writeAddressPort(0x40)             // re-set cursor (autoInc was 0, so no advance)
        assertThat("RAM[0x40]", a.readDataPort().toUnsignedInt(), equalTo(0x12))
    }

    @Test
    fun `address port bit 7 enables auto-increment on data port access`() {
        val a = Namco163Audio()
        a.writeAddressPort(0x40 or 0x80)     // pos=0x40, autoInc=1
        a.writeDataPort(b(0xAA))             // RAM[0x40] = 0xAA; cursor → 0x41
        a.writeDataPort(b(0xBB))             // RAM[0x41] = 0xBB; cursor → 0x42
        a.writeDataPort(b(0xCC))             // RAM[0x42] = 0xCC; cursor → 0x43
        // Re-position (disabling autoInc) and read each byte back.
        a.writeAddressPort(0x40)
        assertThat("byte 0x40", a.readDataPort().toUnsignedInt(), equalTo(0xAA))
        a.writeAddressPort(0x41)
        assertThat("byte 0x41", a.readDataPort().toUnsignedInt(), equalTo(0xBB))
        a.writeAddressPort(0x42)
        assertThat("byte 0x42", a.readDataPort().toUnsignedInt(), equalTo(0xCC))
    }

    @Test
    fun `address port cursor wraps at 128 bytes on auto-increment`() {
        // After the 128th byte (cursor at 0x7F), the next auto-increment
        // wraps to 0x00. We verify by writing 0xEE at 0x7F with autoInc
        // and then writing 0xFF — if the wrap is correct, 0xFF lands at
        // 0x00 and 0xEE remains at 0x7F.
        val a = Namco163Audio()
        a.writeAddressPort(0x7F or 0x80)     // pos=0x7F, autoInc=1
        a.writeDataPort(b(0xEE))             // RAM[0x7F] = 0xEE; cursor → 0x00
        a.writeDataPort(b(0xFF))             // RAM[0x00] = 0xFF; cursor → 0x01
        // Re-position to 0x00 (where the wrap landed) and read back.
        a.writeAddressPort(0x00)
        assertThat("wrapped write went to 0x00", a.readDataPort().toUnsignedInt(), equalTo(0xFF))
        // Verify the original 0xEE is still at 0x7F (not overwritten by the wrap).
        a.writeAddressPort(0x7F)
        assertThat("byte 0x7E untouched", a.readDataPort().toUnsignedInt(), equalTo(0xEE))
    }

    // ---- Sound enable ----

    @Test
    fun `sound enable bit 6 of 0x40 disables the audio, 0x00 enables`() {
        val a = Namco163Audio()
        seedChannel(a, channel = 7, activeChannels = 1,
            freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 0, volume = 15, sampleByte = 0xFF.toByte())
        // Disable sound (bit 6 of 0x40 = 0x40).
        a.writeSoundEnable(0x40)
        repeat(15) { a.tick(1) }
        assertThat("disabled audio outputs 0", a.currentSample(), equalTo(0.0f))

        // Re-enable (0x00). After 15 more cycles, the channel update lands
        // and the output goes non-zero.
        a.writeSoundEnable(0x00)
        repeat(15) { a.tick(1) }
        assertThat("re-enabled audio produces non-zero output",
            abs(a.currentSample()) > 0.0f, equalTo(true))
    }

    // ---- Wave length formula ----

    @Test
    fun `wave length 256 (lengthRaw 0x00) wraps phase at 24 bits naturally`() {
        // With lengthRaw=0x00 the formula gives length=256; combined with
        // frequency=0 the phase accumulator never advances, so the sample
        // at the wave address is read forever.
        val a = Namco163Audio()
        seedChannel(a, channel = 7, activeChannels = 1,
            freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 0, volume = 15, sampleByte = 0x77)
        repeat(15) { a.tick(1) }
        // Sample 0x7 → (7-8)*15 = -15. n+1 = 2 (one active channel). /120.
        assertThat("single-channel output with sample=7, vol=15",
            a.currentSample(), equalTo(-15.0f / 2.0f / 120.0f))
    }

    @Test
    fun `wave length 4 (lengthRaw 0xFC) restricts the sample read to 4 entries`() {
        // lengthRaw=0xFC → length=4. Phase accumulator modulo 4*65536 means
        // sample position wraps every 4 entries. With wave address 0, reads
        // visit positions 0, 1, 2, 3, 0, 1, 2, 3, ... in turn.
        //
        // To step the sample position by 1 per update, set the frequency to
        // 0x10000 (one position per update = phase advances by 65536 per tick).
        // The frequency register is 18 bits: low 8 + mid 8 + high 2. So
        // freqHigh & 0x03 = 1 carries the 0x10000 bit, sharing the byte with
        // the length register (bits 2-7).
        //
        // We use activeChannels=0 (n+1=1) so ch 7 is the only channel
        // visited by the round-robin. With count=1, the chip alternates
        // ch 7 and ch 6, breaking the pos-0,1,2,3 sequence we want to test.
        val a = Namco163Audio()
        // Seed channel 8 FIRST (it overwrites RAM[0] with the sample byte
        // we pass), then write the two test bytes (0xBA at 0, 0xDC at 1).
        seedChannel(a, channel = 7, activeChannels = 0,
            freqLow = 0, freqMid = 0, freqHigh = 1, lengthRaw = 0xFC,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 0, volume = 15,
            // Use 0xBA so the seedChannel's sample-byte write is harmless
            // (we'll overwrite RAM[0] with the same value below).
            sampleByte = (0xB0 or 0x0A).toByte())
        // Re-write the sample bytes (seedChannel wrote 0xBA to RAM[0]).
        a.writeAddressPort(0x00 or 0x80)  // autoInc ON
        a.writeDataPort((0xB0 or 0x0A).toByte())  // RAM[0] = 0xBA (low=0xA, high=0xB)
        a.writeDataPort((0xD0 or 0x0C).toByte())  // RAM[1] = 0xDC (low=0xC, high=0xD)
        // 1 update (15 cycles) → reads position 0 = low nibble of byte 0
        // = 0xA → (10-8)*15 = +30. n+1=1 (count=0).
        repeat(15) { a.tick(1) }
        assertThat("first read at pos 0 = 0xA → +30",
            a.currentSample(), equalTo(30.0f / 1.0f / 120.0f))

        // Tick 15 more → next update reads pos 1 = high nibble of byte 0
        // = 0xB → (11-8)*15 = +45
        repeat(15) { a.tick(1) }
        assertThat("second read at pos 1 = 0xB → +45",
            a.currentSample(), equalTo(45.0f / 1.0f / 120.0f))

        // Third update: pos 2 = 0xC → (12-8)*15 = +60
        repeat(15) { a.tick(1) }
        assertThat("third read at pos 2 = 0xC → +60",
            a.currentSample(), equalTo(60.0f / 1.0f / 120.0f))

        // Fourth: pos 3 = 0xD → (13-8)*15 = +75
        repeat(15) { a.tick(1) }
        assertThat("fourth read at pos 3 = 0xD → +75",
            a.currentSample(), equalTo(75.0f / 1.0f / 120.0f))

        // Fifth: phase wraps to 0 → pos 0 = 0xA → +30 (back to first)
        repeat(15) { a.tick(1) }
        assertThat("after wrap: pos 0 again → +30",
            a.currentSample(), equalTo(30.0f / 1.0f / 120.0f))
    }

    // ---- Update cadence (15 cycles per channel) ----

    @Test
    fun `tick 15 cycles updates exactly one channel`() {
        val a = Namco163Audio()
        // Set up 1 active channel (channel 8 / index 7). Frequency 0,
        // length 256, sample=+7, volume=15.
        seedChannel(a, channel = 7, activeChannels = 1,
            freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 0, volume = 15, sampleByte = 0x77)
        a.tick(14)  // 14 cycles — not yet 15, no update
        // After 14 cycles, output is still 0 (no update happened).
        assertThat("before 15 cycles, output is 0", a.currentSample(), equalTo(0.0f))
        a.tick(1)  // 15th cycle — first update happens
        // The single update reads sample=7, biased to -1, × 15 = -15.
        // n+1 = 2 (one active channel); avg = -7.5; /120 ≈ -0.0625.
        assertThat("after 15 cycles, output reflects the update",
            a.currentSample() < 0.0f, equalTo(true))
    }

    @Test
    fun `tick 120 cycles round-robins through all 8 channels (n=8)`() {
        val a = Namco163Audio()
        // All 8 channels active, frequency 0, length 256, sample=8 (silent),
        // volume 1. After 120 cycles each channel has been updated once.
        // Sample 8 is biased to 0, so the per-channel output is 0; sum = 0;
        // avg = 0.
        for (ch in 0 until 8) {
            seedChannel(a, channel = ch, activeChannels = 8,
                freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
                phaseLow = 0, phaseMid = 0, phaseHigh = 0,
                waveAddress = ch * 2, volume = 1,
                // Both nibbles = 8 (silent biased sample). Positions ch*2
                // (low) and ch*2+1 (high) both read sample 8.
                sampleByte = ((8 shl 4) or 8).toByte())
        }
        repeat(120) { a.tick(1) }
        assertThat("all 8 channels at sample=8 → output 0",
            a.currentSample(), equalTo(0.0f))
    }

    // ---- Round-robin order ----

    @Test
    fun `channels update in reverse order 7 then 6 then 5 then 0 then wrap`() {
        val a = Namco163Audio()
        // 2 active channels: 7 and 6. Set ch 7 → sample +30, ch 6 → +45.
        // After 30 cycles (2 updates), the per-channel outputs are:
        //   ch 7 = (10-8) * 1 = +2
        //   ch 6 = (11-8) * 1 = +3
        // Sum = 5; n+1 = 3; avg = 5/3; / 120 ≈ 0.0138...
        //
        // Note: the helper writes $7F (active count) for non-ch-7 calls,
        // which would clobber ch 7's volume if seeded first. Seed ch 6
        // first so ch 7's combined volume+count write is the final state.
        // The two channels read different sample positions (0 and 2) so
        // their sample bytes don't collide.
        seedChannel(a, channel = 6, activeChannels = 2,
            freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 2, volume = 1, sampleByte = 0xBB.toByte())  // low=0xB at RAM[1]
        seedChannel(a, channel = 7, activeChannels = 2,
            freqLow = 0, freqMid = 0, freqHigh = 0, lengthRaw = 0x00,
            phaseLow = 0, phaseMid = 0, phaseHigh = 0,
            waveAddress = 0, volume = 1, sampleByte = 0xAA.toByte())  // low=0xA at RAM[0]
        repeat(30) { a.tick(1) }
        val afterTwoUpdates = a.currentSample()
        // (2 + 3) / 3 / 120 = 5/360 ≈ 0.01389
        val expected = 5.0f / 3.0f / 120.0f
        assertThat("after 2 updates: avg = (2+3)/3 / 120 (got $afterTwoUpdates, expected $expected)",
            abs(afterTwoUpdates - expected) < 0.0001f, equalTo(true))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save then load restores internal RAM and cursor state`() {
        val a = Namco163Audio()
        a.writeAddressPort(0x45 or 0x80)
        a.writeDataPort(b(0x42))
        a.writeDataPort(b(0x43))
        a.writeAddressPort(0x7F)
        a.writeDataPort(b(0xC0))
        a.writeSoundEnable(0x40)

        val baos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(baos)
        a.saveState(out)
        out.flush()

        val a2 = Namco163Audio()
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
        a2.loadState(inp)

        // After restore, the cursor position is what we last set (0x7F).
        // Reading internal RAM at 0x7F should give 0xC0.
        assertThat("RAM[0x7F] restored", a2.readDataPort().toUnsignedInt(), equalTo(0xC0))
        // Reading at 0x45 and 0x46 (after re-positioning) should give 0x42, 0x43.
        a2.writeAddressPort(0x45)
        assertThat("RAM[0x45] restored", a2.readDataPort().toUnsignedInt(), equalTo(0x42))
        a2.writeAddressPort(0x46)
        assertThat("RAM[0x46] restored", a2.readDataPort().toUnsignedInt(), equalTo(0x43))
    }

    // ---- Helpers ----

    /**
     * Set up channel [channel] (0..7) with full register control, the
     * [activeChannels] active-channel-count register at $7F, and the sample
     * byte at the channel's wave address so a tick produces a non-zero output.
     *
     * **Channel 7's volume byte and the active-channel-count share $7F**
     * (low nibble = volume, bits 4-6 = count). The helper performs a single
     * combined write for channel 7 so neither field clobbers the other. For
     * other channels, the count is written in a separate $7F write.
     *
     * The `activeChannels` parameter matters: only channels from
     * `8 - activeChannels` to 7 inclusive are updated by `tick`. The default
     * after power-on is 0 (only channel 7), which is why the helper sets
     * the count explicitly.
     */
    private fun seedChannel(
        a: Namco163Audio,
        channel: Int,
        activeChannels: Int,
        freqLow: Int, freqMid: Int, freqHigh: Int, lengthRaw: Int,
        phaseLow: Int, phaseMid: Int, phaseHigh: Int,
        waveAddress: Int, volume: Int, sampleByte: Byte
    ) {
        val base = 0x40 + channel * 8
        a.writeAddressPort(base or 0x80)            // autoInc
        a.writeDataPort(b(freqLow))
        a.writeDataPort(b(phaseLow))
        a.writeDataPort(b(freqMid))
        a.writeDataPort(b(phaseMid))
        a.writeDataPort(b((freqHigh and 0x03) or (lengthRaw and 0xFC)))
        a.writeDataPort(b(phaseHigh))
        a.writeDataPort(b(waveAddress))
        if (channel == 7) {
            // Volume + active count share this byte — single combined write.
            val v = (volume and 0x0F) or
                ((activeChannels.coerceIn(0, 7) shl 4) and 0x7F)
            a.writeDataPort(b(v))
        } else {
            a.writeDataPort(b(volume and 0x0F))
            // Set $7F (active count) for non-ch-7 calls.
            a.writeAddressPort(0x7F)
            a.writeDataPort(b((activeChannels.coerceIn(0, 7) shl 4) and 0x7F))
        }
        // Re-position to the sample bank and write the sample byte. The chip
        // reads 4-bit samples at positions [waveAddress] (low nibble) and
        // [waveAddress+1] (high nibble), both packed into the byte at
        // RAM[waveAddress/2]. For the "both nibbles = N" case, write the byte
        // ((N shl 4) | N) to RAM[waveAddress/2].
        a.writeAddressPort(waveAddress ushr 1)
        a.writeDataPort(sampleByte)
    }
}
