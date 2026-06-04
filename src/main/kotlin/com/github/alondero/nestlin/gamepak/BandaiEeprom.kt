package com.github.alondero.nestlin.gamepak

/**
 * Minimal I2C + 24Cxx EEPROM model for the Bandai LZ93D50 chip.
 *
 * The chip exposes two pins (SCL + SDA) and a register-shaped interface:
 * the game writes [SCL, SDA] to bit 5 and bit 6 of register `$D`, and reads
 * the same bits back. We model the standard 2-wire protocol by tracking
 * edges of SCL and SDA:
 *   - Start:  SDA falls while SCL is high
 *   - Stop:   SDA rises while SCL is high
 *   - Data:   sampled on SCL rising edge
 *   - Ack:    slave drives SDA low for one SCL cycle
 *
 * Each byte on the bus takes 9 SCL cycles: 8 data + 1 ACK. The ACK clock is
 * what we need to track carefully — between bytes, the master raises SCL one
 * extra time to clock the slave's ACK bit. This implementation tracks a
 * 2-phase ACK state (driving SDA low on the 8th SCL falling, releasing it
 * on the 9th SCL falling) so the master sees a clean ACK pulse.
 *
 * The 24Cxx protocol over those primitives:
 *   [start]  [dev_addr=0b1010xxx + r/w]  [ack]  [word_addr=8 bits]  [ack]
 *   [data=8 bits]  [ack]   ...   [stop]
 *
 * This is intentionally a "small enough to fit in your head" implementation:
 * the Bandai FCG games that need EEPROM (Famicom Jump II, Magical Taruruuto-kun
 * 2, several Dragon Ball Z titles) only use random read/write, never multi-master
 * arbitration, never general-call resets, never the hardware-address pins. So
 * we hard-code the device address to 0x50 (`xxx = 000`) and skip those cases.
 */
class BandaiEeprom(val sizeBytes: Int = 256) {

    // A factory-fresh / erased EEPROM reads as 0xFF in every cell. Games that
    // probe the EEPROM at boot rely on this "blank" pattern to decide whether a
    // save exists; zero-initialising would look like (corrupt) save data.
    val memory = ByteArray(sizeBytes) { 0xFF.toByte() }

    // Last SCL / SDA the game wrote. Used for edge detection.
    private var scl = false
    private var sda = false

    /** SDA level the slave currently drives (true = high = NACK / data-1). */
    private var sdaOut = true

    private enum class State {
        IDLE,
        ADDRESS,        // master sending device address
        ACK_AFTER_ADDR, // waiting for ACK clock (2 phases)
        WORD_ADDR,      // master sending 8-bit word address
        ACK_AFTER_WORD,
        DATA_TX,        // master sending data byte
        ACK_AFTER_TX,
        DATA_RX,        // master receiving data byte (slave drives SDA)
        ACK_AFTER_RX
    }
    private var state = State.IDLE

    private var shiftReg = 0    // bit-shift register being assembled
    private var bitCount = 0    // bits assembled in current byte (0..7)
    private var wordAddress = 0 // current EEPROM address
    private var readMode = false

    /** ACK sub-phase: 0 = "driving SDA low"; 1 = "ready to release and transition". */
    private var ackPhase = 0

    /** Apply a CPU write to register `$D`. */
    fun write(value: Int) {
        val newScl = (value and 0x20) != 0
        val newSda = (value and 0x40) != 0

        // Start: SDA falls while SCL is high.
        if (scl && newScl && sda && !newSda) {
            state = State.ADDRESS
            bitCount = 0
            shiftReg = 0
            ackPhase = 0
        }
        // Stop: SDA rises while SCL is high.
        else if (scl && newScl && !sda && newSda) {
            state = State.IDLE
            ackPhase = 0
        }
        // Sample data on SCL rising edge.
        else if (!scl && newScl) {
            when (state) {
                State.ADDRESS -> {
                    shiftReg = ((shiftReg shl 1) and 0xFF) or (if (newSda) 1 else 0)
                    bitCount++
                    if (bitCount == 8) {
                        val addr = (shiftReg ushr 1) and 0x7F
                        readMode = (shiftReg and 0x01) != 0
                        // The 24Cxx device-select byte is `1010 A2 A1 A0 R/W`.
                        // With the Bandai board grounding the address pins it is
                        // 0xA0 (write) / 0xA1 (read), i.e. a 7-bit device address
                        // of 0x50 (0b1010000). (NB: 0x50 here, NOT 0x28 — the byte
                        // already has the R/W bit shifted out by `ushr 1`.)
                        state = if (addr == 0x50) {
                            State.ACK_AFTER_ADDR
                        } else {
                            State.IDLE
                        }
                        ackPhase = 0
                    }
                }
                State.WORD_ADDR -> {
                    shiftReg = ((shiftReg shl 1) and 0xFF) or (if (newSda) 1 else 0)
                    bitCount++
                    if (bitCount == 8) {
                        wordAddress = shiftReg and 0xFF
                        state = State.ACK_AFTER_WORD
                        ackPhase = 0
                    }
                }
                State.DATA_TX -> {
                    shiftReg = ((shiftReg shl 1) and 0xFF) or (if (newSda) 1 else 0)
                    bitCount++
                    if (bitCount == 8) {
                        memory[wordAddress % sizeBytes] = shiftReg.toByte()
                        wordAddress = (wordAddress + 1) and 0xFF
                        state = State.ACK_AFTER_TX
                        ackPhase = 0
                    }
                }
                State.DATA_RX -> {
                    // Master reads SDA on rising edge. We don't have to do
                    // anything here — the master reads the SDA bit on its
                    // own end. The sdaOut value is set on the previous
                    // SCL falling edge.
                    bitCount++
                    if (bitCount == 8) {
                        wordAddress = (wordAddress + 1) and 0xFF
                        state = State.ACK_AFTER_RX
                        ackPhase = 0
                    }
                }
                State.ACK_AFTER_ADDR, State.ACK_AFTER_WORD,
                State.ACK_AFTER_TX, State.ACK_AFTER_RX -> {
                    // Master reading ACK bit. No state change.
                }
                State.IDLE -> { /* nothing */ }
            }
        }
        // SCL falling: drive ack/nack or output next data bit.
        else if (scl && !newScl) {
            when (state) {
                State.ACK_AFTER_ADDR -> {
                    if (ackPhase == 0) {
                        sdaOut = false  // slave drives ACK low
                        ackPhase = 1
                    } else {
                        state = if (readMode) State.DATA_RX else State.WORD_ADDR
                        bitCount = 0
                        shiftReg = 0
                        ackPhase = 0
                        // In read mode the slave must present the first data bit
                        // (MSB) on SDA now, while SCL is low, so the master samples
                        // it on the very next rising edge. Releasing SDA here
                        // instead would drop bit 7 and force the MSB high.
                        if (readMode) {
                            val byte = memory[wordAddress % sizeBytes].toInt() and 0xFF
                            sdaOut = (byte and 0x80) != 0
                        } else {
                            sdaOut = true   // release SDA for the master's word-address bits
                        }
                    }
                }
                State.ACK_AFTER_WORD -> {
                    if (ackPhase == 0) {
                        sdaOut = false
                        ackPhase = 1
                    } else {
                        sdaOut = true
                        state = State.DATA_TX
                        bitCount = 0
                        shiftReg = 0
                        ackPhase = 0
                    }
                }
                State.ACK_AFTER_TX -> {
                    if (ackPhase == 0) {
                        sdaOut = false
                        ackPhase = 1
                    } else {
                        sdaOut = true
                        // Sequential writes keep going (page write protocol).
                        state = State.DATA_TX
                        bitCount = 0
                        shiftReg = 0
                        ackPhase = 0
                    }
                }
                State.DATA_RX -> {
                    // Output next data bit on SDA. Bits are MSB first.
                    // sdaOut=true means slave drives SDA high (= data bit 1).
                    val byte = memory[wordAddress % sizeBytes].toInt() and 0xFF
                    sdaOut = (byte and (0x80 ushr bitCount)) != 0
                }
                State.ACK_AFTER_RX -> {
                    if (ackPhase == 0) {
                        // Master drives NACK (high) on first half.
                        sdaOut = true
                        ackPhase = 1
                    } else {
                        sdaOut = true
                        state = State.IDLE
                        ackPhase = 0
                    }
                }
                else -> { /* not driving anything */ }
            }
        }

        scl = newScl
        sda = newSda
    }

    /**
     * Apply a CPU read of register `$D`. Returns the current SCL and the
     * slave-driven SDA — bit 5 = SCL, bit 6 = SDA.
     */
    fun read(): Int {
        var v = 0
        if (scl) v = v or 0x20
        if (sdaOut) v = v or 0x40
        return v
    }

    fun saveState(out: java.io.DataOutput) {
        out.writeInt(if (scl) 1 else 0)
        out.writeInt(if (sda) 1 else 0)
        out.writeInt(if (sdaOut) 1 else 0)
        out.writeInt(state.ordinal)
        out.writeInt(shiftReg)
        out.writeInt(bitCount)
        out.writeInt(wordAddress)
        out.writeInt(if (readMode) 1 else 0)
        out.writeInt(ackPhase)
        out.write(memory)
    }

    fun loadState(input: java.io.DataInput) {
        scl = input.readInt() != 0
        sda = input.readInt() != 0
        sdaOut = input.readInt() != 0
        state = State.values()[input.readInt()]
        shiftReg = input.readInt()
        bitCount = input.readInt()
        wordAddress = input.readInt()
        readMode = input.readInt() != 0
        ackPhase = input.readInt()
        input.readFully(memory)
    }
}
