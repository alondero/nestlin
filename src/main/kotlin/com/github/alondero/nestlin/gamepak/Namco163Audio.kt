package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.apu.ExpansionAudioChannel
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Namco 163 expansion-audio engine (Issue #59). 8 wavetable channels share
 * a single mixed output, so this class implements [ExpansionAudioChannel]
 * directly (unlike VRC6, which returns 3 separate channels from
 * `expansionAudioChannels()`). The mapper registers this single instance;
 * the APU clocks it at CPU rate via [tick], and [currentSample] returns
 * the averaged sum of all enabled channels.
 *
 * Spec: https://www.nesdev.org/wiki/Namco_163_audio
 * Reference: Mesen2 `Core/NES/Mappers/Audio/Namco163Audio.h`.
 *
 * ## The 128-byte internal RAM is dual-purpose
 *
 *  - Bytes `$00-$3F` — wavetable sample data: 64 bytes, each storing two
 *    4-bit samples (low nibble first, then high). So 128 4-bit samples
 *    available; a real game picks a window of `length` (4..256) samples
 *    for each channel via the channel's [WaveLength] register.
 *  - Bytes `$40-$7F` — 8 channel register sets, 8 bytes each:
 *
 * ```
 *   +0  FrequencyLow  (low 8 of 18-bit frequency)
 *   +1  PhaseLow      (low 8 of 24-bit phase accumulator)
 *   +2  FrequencyMid  (bits 8-15 of frequency)
 *   +3  PhaseMid      (bits 8-15 of phase)
 *   +4  FrequencyHigh (bits 16-17 of frequency) | WaveLength (bits 2-7 of length reg)
 *   +5  PhaseHigh     (bits 16-23 of phase)
 *   +6  WaveAddress   (offset into the 64-byte sample bank)
 *   +7  Volume        (low 4 bits = volume; bits 4-6 = active channel count)
 * ```
 *
 *  Channel 1 is at `$40`, channel 8 at `$78`. Bits 4-6 of byte `$7F`
 *  (channel 8's volume) set the **active channel count** — value 0 means
 *  "only channel 8", value 7 means "all 8 channels". NESdev: this byte
 *  is sometimes called the "channel count" register and is the only
 *  non-per-channel volume byte.
 *
 * ## Update cadence
 *
 *  The chip round-robins through enabled channels, updating one every
 *  **15 CPU cycles**. The first update happens at cycle 15, then 30, 45,
 *  ... At each update the channel's 24-bit phase accumulator is advanced
 *  by its 18-bit frequency, modulo the waveform period; the high 8 bits
 *  of the result (plus the wave address offset) index into the sample
 *  RAM, the 4-bit sample is read out, biased to ±7, multiplied by the
 *  channel's 4-bit volume, and stored in the channel's output latch.
 *
 *  Channels are visited in reverse order: 7, 6, 5, ..., 0, then wrap. With
 *  `n` active channels (1..8), channels 7-(n-1) are skipped entirely
 *  (never updated, always output 0). Mesen2's `ClockAudio` matches this
 *  (`_currentChannel--`, wrap to 7 when below `7 - n`).
 */
class Namco163Audio : ExpansionAudioChannel {

    /** 128 bytes of internal RAM. Channel registers live in $40-$7F; samples in $00-$3F. */
    private val internalRam = ByteArray(0x80)

    /** Per-channel current sample output (biased, multiplied by volume). Range `[-120, +105]`. */
    private val channelOutput = IntArray(8)

    /** Address-port cursor into [internalRam] (set by $F800 writes). */
    private var ramPosition = 0

    /** Whether the data port ($4800-$4FFF) auto-advances [ramPosition] after each access. */
    private var autoIncrement = false

    /** Counts to 15 between channel updates. */
    private var updateCounter = 0

    /** Index of the next channel to update (starts at 7, counts DOWN). */
    private var currentChannel = 7

    /** Master sound-enable (bit 6 of $E000: 1 = disabled, 0 = enabled). */
    private var disableSound = false

    // ---- Data-port + address-port + sound-enable accessors ------------------
    //
    // The mapper calls these from its $4800/$E000/$F800 decode. Keeping the
    // bus protocol on the audio class (rather than the mapper) lets the
    // mapper be a thin dispatcher and concentrates the chip's 3 register
    // shapes in one place.

    /** $4800-$4FFF: write [value] to internal RAM at [ramPosition], optionally auto-incrementing. */
    fun writeDataPort(value: Byte) {
        internalRam[ramPosition] = value
        if (autoIncrement) ramPosition = (ramPosition + 1) and 0x7F
    }

    /** $4800-$4FFF: read internal RAM at [ramPosition], optionally auto-incrementing. */
    fun readDataPort(): Byte {
        val v = internalRam[ramPosition]
        if (autoIncrement) ramPosition = (ramPosition + 1) and 0x7F
        return v
    }

    /** $F800: set the address-port cursor ([value] bits 0-6) and auto-increment (bit 7). */
    fun writeAddressPort(value: Int) {
        ramPosition = value and 0x7F
        autoIncrement = (value and 0x80) != 0
    }

    /** $E000 bit 6: when set, freezes the audio (the APU keeps calling tick but we no-op it). */
    fun writeSoundEnable(value: Int) {
        disableSound = (value and 0x40) != 0
    }

    /** Test/diagnostic accessor for the raw internal RAM (also needed for save-state). */
    fun getInternalRam(): ByteArray = internalRam
    fun setInternalRam(ram: ByteArray) {
        require(ram.size == 0x80) { "N163 internal RAM must be 128 bytes, got ${ram.size}" }
        System.arraycopy(ram, 0, internalRam, 0, 0x80)
    }

    /** Test/debug accessor for the live channel outputs (last sampled × volume, per channel). */
    fun debugChannelOutput(): IntArray = channelOutput.copyOf()

    // ---- Per-channel register readers ---------------------------------------
    //
    // These read from the internal RAM at the channel's 8-byte slot. Reading
    // fresh each tick keeps the audio class stateless-from-the-cpu-side: the
    // mapper only needs to write to the data port, the audio class figures
    // out which channel register to look at.

    private fun frequency(channel: Int): Int {
        val base = 0x40 + channel * 8
        // 18-bit value: low 8 + mid 8 + (high 2). Mask high 2 explicitly —
        // the high byte of the wave-length register (offset +4) shares the
        // same address; the top 2 bits are frequency, the next 6 are length.
        return (internalRam[base].toUnsignedInt()) or
            (internalRam[base + 2].toUnsignedInt() shl 8) or
            ((internalRam[base + 4].toUnsignedInt() and 0x03) shl 16)
    }

    private fun phase(channel: Int): Int {
        val base = 0x40 + channel * 8
        return (internalRam[base + 1].toUnsignedInt()) or
            (internalRam[base + 3].toUnsignedInt() shl 8) or
            (internalRam[base + 5].toUnsignedInt() shl 16)
    }

    private fun setPhase(channel: Int, phase: Int) {
        val base = 0x40 + channel * 8
        internalRam[base + 1] = (phase and 0xFF).toByte()
        internalRam[base + 3] = ((phase ushr 8) and 0xFF).toByte()
        internalRam[base + 5] = ((phase ushr 16) and 0xFF).toByte()
    }

    private fun waveAddress(channel: Int): Int =
        internalRam[0x40 + channel * 8 + 6].toUnsignedInt()

    /**
     * Wave length in 4-bit samples, derived from the 8-bit "WaveLength" field
     * via the hardware formula `length = 256 - (reg and 0xFC)`. The formula
     * rounds to multiples of 4 (because only the top 6 bits matter), giving
     * valid lengths 4, 8, 12, ..., 256. NESdev quirk: writing `$00` to the
     * length field → length 256 samples, the maximum.
     */
    private fun waveLength(channel: Int): Int {
        val raw = internalRam[0x40 + channel * 8 + 4].toUnsignedInt()
        return 256 - (raw and 0xFC)
    }

    /** 4-bit linear volume (0..15). The channel-count bits (4-6) are NOT included. */
    private fun volume(channel: Int): Int =
        internalRam[0x40 + channel * 8 + 7].toUnsignedInt() and 0x0F

    /** Number of active channels: bits 4-6 of byte $7F (channel 8's volume byte). */
    private fun activeChannelCount(): Int =
        (internalRam[0x7F].toUnsignedInt() ushr 4) and 0x07

    // ---- The per-update one-channel advance ---------------------------------

    private fun updateChannel(channel: Int) {
        val freq = frequency(channel)
        val currentPhase = phase(channel)
        val length = waveLength(channel)   // 4..256 (multiple of 4)
        val offset = waveAddress(channel)  // 0..255
        val vol = volume(channel)          // 0..15

        // Sample position uses the CURRENT (pre-advance) phase — the chip
        // reads the sample at the current position, then advances the
        // accumulator for the next update.
        val samplePos = ((currentPhase ushr 16) + offset) and 0xFF
        // 4-bit samples packed as nibbles: even byte = low nibble, odd = high.
        val rawNibble = if ((samplePos and 0x01) != 0) {
            (internalRam[samplePos ushr 1].toUnsignedInt() ushr 4) and 0x0F
        } else {
            internalRam[samplePos ushr 1].toUnsignedInt() and 0x0F
        }
        // Biased to signed: 0..15 → -8..+7, then × volume. The signed bias
        // is what makes the N163 a *bipolar* expansion source (unlike the
        // VRC6's unsigned 0-15 / 0-31 outputs).
        val signedSample = rawNibble - 8
        channelOutput[channel] = signedSample * vol

        // Advance the 24-bit phase accumulator modulo the waveform period
        // (length × 65536). For length=256 this is a natural 24-bit wrap;
        // for shorter lengths the modulo clips to one cycle. Mesen2 uses
        // the same `phase = (phase + freq) % (length << 16)`.
        val advanced = (currentPhase + freq) % (length shl 16)
        setPhase(channel, advanced)
    }

    // ---- ExpansionAudioChannel contract -------------------------------------

    override fun tick(cycles: Int) {
        if (disableSound) return
        var remaining = cycles
        while (remaining > 0) {
            val step = minOf(15 - updateCounter, remaining)
            updateCounter += step
            remaining -= step
            if (updateCounter >= 15) {
                updateCounter = 0
                updateChannel(currentChannel)
                // Round-robin in reverse: 7, 6, 5, ..., then wrap.
                // The "low" bound is `7 - activeChannelCount`; below that,
                // channels are disabled and we wrap to 7.
                val low = 7 - activeChannelCount()
                currentChannel = if (currentChannel <= low) 7 else currentChannel - 1
            }
        }
    }

    override fun currentSample(): Float {
        // Sum all active channels' last output, divide by (n+1), then
        // normalize. n+1 (not n) is what Mesen2's `UpdateOutputLevel`
        // uses — it's a hardware detail that prevents a single-channel
        // mode from being 8× louder than 8-channel mode.
        //
        // Normalization: the per-channel range is `[-8*15, +7*15]` =
        // `[-120, +105]`. Summing 8 channels → roughly `[-960, +840]`.
        // Dividing by `(n+1)` (max 8) → roughly `[-120, +105]`. We map
        // that to `[-1, +1]` by dividing by 120 (the peak negative) so
        // a single full-scale channel can swing the mix by ~1.0. The
        // APU mixer's EXPANSION_GAIN=0.15 then puts a fullscale N163
        // channel at the same level as a VRC6 voice.
        val n = activeChannelCount() + 1
        var sum = 0
        for (i in (7 - activeChannelCount())..7) {
            sum += channelOutput[i]
        }
        val avg = sum.toFloat() / n
        return avg / 120.0f
    }

    // ---- Save state ---------------------------------------------------------

    fun saveState(out: DataOutput) {
        out.write(internalRam)
        for (o in channelOutput) out.writeInt(o)
        out.writeInt(ramPosition)
        out.writeBoolean(autoIncrement)
        out.writeInt(updateCounter)
        out.writeInt(currentChannel)
        out.writeBoolean(disableSound)
    }

    fun loadState(input: DataInput) {
        input.readFully(internalRam)
        for (i in channelOutput.indices) channelOutput[i] = input.readInt()
        ramPosition = input.readInt()
        autoIncrement = input.readBoolean()
        updateCounter = input.readInt()
        currentChannel = input.readInt()
        disableSound = input.readBoolean()
    }
}
