package com.github.alondero.nestlin

class Controller {
    var buttons: Int = 0 // Bitmap: Right Left Down Up Start Select B A (0..7)
                         // Wait, standard order read is A, B, Select, Start, Up, Down, Left, Right.
                         // So bit 0 should be A.
    private var shiftRegister: Int = 0
    private var strobe: Boolean = false

    // Standard NES Controller Button bitmasks (internal storage matches shift order)
    // Bit 0: A
    // Bit 1: B
    // Bit 2: Select
    // Bit 3: Start
    // Bit 4: Up
    // Bit 5: Down
    // Bit 6: Left
    // Bit 7: Right
    enum class Button(val mask: Int) {
        A(0x01),
        B(0x02),
        SELECT(0x04),
        START(0x08),
        UP(0x10),
        DOWN(0x20),
        LEFT(0x40),
        RIGHT(0x80)
    }

    /**
     * Write to $4016 (Strobe)
     * Writing 1 to this bit sets strobe high.
     * Writing 0 sets strobe low.
     * While strobe is high, the shift register is continuously reloaded with current button state.
     * When strobe goes 1 -> 0, the current state is latched.
     */
    fun write(value: Byte) {
        val newStrobe = (value.toInt() and 1) == 1
        
        // If strobe is high, reload shift register
        if (newStrobe) {
            shiftRegister = buttons
        }
        
        strobe = newStrobe
    }

    /**
     * Read from $4016
     * Returns the next bit of the button state (Active Low logic usually, but emulators often treat 1 as pressed).
     * Standard NES controller returns 1 for pressed, 0 for released (inverted by inverter in hardware? No, 1 is pressed in register).
     * Actually:
     * "The shift register is 8 bits long. Reading $4016/4017 returns the LSB."
     * "1 = pressed, 0 = released" (Standard controller)
     * 
     * However, standard wiring implies 0 is pressed on the wire, but the inverter makes it 1 in the register?
     * Most docs say: return 1 if pressed.
     */
    fun read(): Byte {
        val data: Int
        if (strobe) {
            // When strobe is high, it always returns the status of A button
            data = buttons and Button.A.mask
        } else {
            // Read LSB
            data = shiftRegister and 1
            // Shift data
            shiftRegister = (shiftRegister shr 1) or 0x80 // Shift in 1s for subsequent reads
        }
        
        // Bits 5-7 are open bus, usually 0x40. Bit 0 is data.
        // We'll return 0x40 | data for safety, though 0x00 | data usually works.
        // Nestopia uses 0x40.
        return (0x40 or data).toByte()
    }

    fun setButton(button: Button, pressed: Boolean) {
        if (pressed) {
            buttons = buttons or button.mask
        } else {
            buttons = buttons and button.mask.inv()
        }
        
        // If strobe is active, update the shift register immediately (transparent latch)
        if (strobe) {
            shiftRegister = buttons
        }
    }
}
