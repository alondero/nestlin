package com.github.alondero.nestlin.gamepak

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class Mapper4IrqTest {

    private fun createTestGamePak(prgSize: Int = 2, chrSize: Int = 1): GamePak {
        val header = ByteArray(16)
        header[4] = prgSize.toByte()
        header[5] = chrSize.toByte()
        header[6] = 0x04.toByte()       // Mapper 4 (MMC3)
        header[7] = 0x40.toByte()       // Mapper 4 (upper nibble)

        val prgRom = ByteArray(prgSize * 16384) { (it and 0xFF).toByte() }
        val chrRom = if (chrSize > 0) ByteArray(chrSize * 8192) { (it and 0xFF).toByte() } else ByteArray(0)

        return GamePak(header + prgRom + chrRom)
    }

    @Test
    fun `a12RisingEdgesDecrementCounter - latch=5 reload enable fire 6 edges IRQ pending`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set IRQ latch to 5 ($C000)
        mapper.cpuWrite(0xC000, 5.toByte())
        // Trigger IRQ reload ($C001)
        mapper.cpuWrite(0xC001, 0x00)
        // Enable IRQ ($E001)
        mapper.cpuWrite(0xE001, 0x00)

        // Fire 6 rising A12 edges (counter should reach 0 after 5 decrements from 5)
        // After reload: counter = latch = 5, reload = false
        // Edge 1: counter 5 -> 4
        // Edge 2: counter 4 -> 3
        // Edge 3: counter 3 -> 2
        // Edge 4: counter 2 -> 1
        // Edge 5: counter 1 -> 0 -> IRQ pending, counter = latch = 5, reload = false
        // Edge 6: counter 5 -> 4
        for (i in 1..6) {
            mapper.notifyA12Edge(true)
        }

        assertThat(mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `counterReloadsOnZero - after first IRQ fire latch+1 more edges IRQ pending again`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set IRQ latch to 5
        mapper.cpuWrite(0xC000, 5.toByte())
        // Trigger reload
        mapper.cpuWrite(0xC001, 0x00)
        // Enable IRQ
        mapper.cpuWrite(0xE001, 0x00)

        // Fire 6 edges (counter goes 5->4->3->2->1->0, fires IRQ at edge 5)
        for (i in 1..6) {
            mapper.notifyA12Edge(true)
        }

        // Acknowledge IRQ
        mapper.acknowledgeIrq()
        assertThat(mapper.isIrqPending(), equalTo(false))

        // Now fire 6 more edges - counter was reloaded to 5 when it hit 0
        // So after edge 5 it should fire again
        for (i in 1..6) {
            mapper.notifyA12Edge(true)
        }

        assertThat(mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `fallingEdgesDoNotCount - interleave falling with rising only rising decrement`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set IRQ latch to 2
        mapper.cpuWrite(0xC000, 2.toByte())
        // Trigger reload
        mapper.cpuWrite(0xC001, 0x00)
        // Enable IRQ
        mapper.cpuWrite(0xE001, 0x00)

        // Fire: rising, falling, rising (counter: 2->1->1->0, IRQ fires after 2nd rising)
        mapper.notifyA12Edge(true)   // counter: 2 -> 1
        mapper.notifyA12Edge(false)  // ignored
        mapper.notifyA12Edge(true)   // counter: 1 -> 0 -> IRQ pending
        mapper.notifyA12Edge(true)   // counter: 0 -> reload to 2 -> 1 (IRQ already pending)

        assertThat(mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `disableClearsPending - write to E000 clears isIrqPending`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set IRQ latch to 1
        mapper.cpuWrite(0xC000, 1.toByte())
        // Trigger reload
        mapper.cpuWrite(0xC001, 0x00)
        // Enable IRQ
        mapper.cpuWrite(0xE001, 0x00)

        // Fire two edges to trigger IRQ
        // Edge 1: counter=1, reload=true -> counter=latch=1, reload=false
        // Edge 2: counter=1, reload=false -> counter=0, IRQ fires
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)

        assertThat(mapper.isIrqPending(), equalTo(true))

        // Disable IRQ ($E000)
        mapper.cpuWrite(0xE000, 0x00)

        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `reloadFlagTriggersReload - set reload flag then fire edge counter gets latch value`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set IRQ latch to 3
        mapper.cpuWrite(0xC000, 3.toByte())
        // Trigger reload ($C001) - this sets reload flag AND irqCounter=valueInt
        // Since valueInt=0, counter becomes 0
        mapper.cpuWrite(0xC001, 0x00)
        // Enable IRQ
        mapper.cpuWrite(0xE001, 0x00)

        // Fire five edges to trigger IRQ:
        // Edge 1: counter=0||reload=true -> counter=latch=3, reload=false (no decrement)
        // Edge 2: counter=3->2
        // Edge 3: counter=2->1
        // Edge 4: counter=1->0 -> IRQ pending, counter=latch=3
        // Edge 5: counter=3->2
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)
        mapper.notifyA12Edge(true)

        assertThat(mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `counterAtZeroWithoutReloadFlag also triggers reload per NESdev spec`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper4(gamePak)

        // Set latch to 0 and reload
        mapper.cpuWrite(0xC000, 0x00)
        mapper.cpuWrite(0xC001, 0x00)

        // Fire one rising edge - counter is 0, so reload happens, counter = 0
        mapper.notifyA12Edge(true) // counter: 0 -> 0 (reloaded), no IRQ

        // Fire another edge - still 0, reload again
        mapper.notifyA12Edge(true) // counter: 0 -> 0 (reloaded again)

        // Counter never decrements when latch is 0
        assertThat(mapper.isIrqPending(), equalTo(false))
    }
}
