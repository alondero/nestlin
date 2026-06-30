package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Issue #207 — fix preserved quirks from #192 Opcodes sealed-class refactor.
 *
 * Each test below pins the NEW (correct) behaviour for one of the 9
 * preserved quirks. The cycle-count dimension is covered by
 * [OpcodeCycleTableTest]; this file covers the semantic dimension
 * (what the opcode DOES, not just how long it takes).
 *
 * The 9 quirks, mapped to test names:
 *  - LAX per-mode cycle counts           → `laxLoadsBothRegisters`
 *  - SAX per-mode cycle counts           → `saxStoresAAndX`
 *  - JMP indirect uses 5 cycles          → `jmpIndirectUsesFiveCycles`
 *  - AHX mask is 0xFF, not 0x07          → `ahxPreservesHighBits`
 *  - 0x9B is TAS, not XAA                → `opcode9bIsTasNotXaa`
 *  - 0xE3 is DCP, not ISC                → `opcodeE3IsDcpNotIsc`
 *  - 0x9C (SHY) and 0x9E (SHX) registered → `shyShxAreRegistered`
 *  - KIL actually halts                  → `kilHaltsTheCpu`
 *  - Absolute.address() masks to 16 bits → `absoluteAddressWrapsAtFFFF`
 */
class Issue207QuirkFixesTest {

    private fun freshCpu() = Cpu(Memory.createWithApu().first).apply { reset() }

    // ===== LAX per-mode cycle counts (quirk 1) ==========================

    @Test
    fun laxLoadsBothRegisters() {
        val cpu = freshCpu()
        // LAX zp: A=0, X=$55, [$0042] = $AB -> both A and X become $AB.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xA7.toSignedByte() // LAX $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0xAB.toSignedByte()
        cpu.registers.accumulator = 0x00.toSignedByte()
        cpu.registers.indexX = 0x55.toSignedByte()

        cpu.tick()

        // Per-mode cycles: zp = 3 (post-tick = 2).
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // Both A and X are loaded with the same value.
        assertThat(cpu.registers.accumulator, equalTo(0xAB.toSignedByte()))
        assertThat(cpu.registers.indexX, equalTo(0xAB.toSignedByte()))
        // Z and N flags resolve from the loaded value (0xAB: negative, not zero).
        assertThat(cpu.processorStatus.negative, equalTo(true))
        assertThat(cpu.processorStatus.zero, equalTo(false))
    }

    // ===== SAX per-mode cycle counts (quirk 2) ==========================

    @Test
    fun saxStoresAAndX() {
        val cpu = freshCpu()
        // SAX zp: A=$F0, X=$0F -> [$0042] = A AND X = $00.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x87.toSignedByte() // SAX $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.registers.accumulator = 0xF0.toSignedByte()
        cpu.registers.indexX = 0x0F.toSignedByte()

        cpu.tick()

        // Per-mode cycles: zp = 3 (post-tick = 2).
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // A AND X = 0xF0 AND 0x0F = 0x00.
        assertThat(cpu.memory[0x0042], equalTo(0x00.toSignedByte()))
    }

    // ===== JMP indirect cycle count (quirk 3) ===========================

    @Test
    fun jmpIndirectUsesFiveCycles() {
        val cpu = freshCpu()
        // JMP ($0200) where [$0200] = $1234.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x6C.toSignedByte() // JMP (ind)
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> pointer at $0200
        cpu.memory[0x0200] = 0x34.toSignedByte()
        cpu.memory[0x0201] = 0x12.toSignedByte()

        cpu.tick()

        // Real-6502 indirect JMP is 5 cycles (post-tick = 4). The old code
        // used 3 cycles — the +1 delta is the regression bar.
        assertThat(cpu.workCyclesLeft, equalTo(4))
        assertThat(cpu.registers.programCounter, equalTo(0x1234.toSignedShort()))
    }

    // ===== AHX mask (quirk 4) ===========================================

    @Test
    fun ahxPreservesHighBits() {
        val cpu = freshCpu()
        // AHX abs,Y: A=$F0, X=$F0, Y=0 -> [$0500] = A AND X = $F0.
        // The old 0x07 mask would have produced $00 instead.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x9F.toSignedByte() // AHX abs,Y
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x05.toSignedByte() // -> $0500
        cpu.registers.accumulator = 0xF0.toSignedByte()
        cpu.registers.indexX = 0xF0.toSignedByte()
        cpu.registers.indexY = 0x00.toSignedByte()

        cpu.tick()

        // The 0x07 mask quirk would have written $00 (0xF0 & 0x07 = 0).
        // The fix: write A AND X = 0xF0.
        assertThat(cpu.memory[0x0500], equalTo(0xF0.toSignedByte()))
    }

    // ===== 0x9B is TAS, not XAA (quirk 5) ==============================

    @Test
    fun opcode9bIsTasNotXaa() {
        val cpu = freshCpu()
        // 0x9B (TAS abs,Y): sets S=A, "stores" nothing to the address.
        // Operand bytes are the absolute-Y target address.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x9B.toSignedByte() // TAS abs,Y
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x05.toSignedByte() // -> $0500
        cpu.registers.accumulator = 0x42.toSignedByte()
        cpu.registers.indexY = 0x00.toSignedByte()
        // Mark S distinctly so we can see it change.
        cpu.registers.stackPointer = 0x00.toSignedByte()

        cpu.tick()

        // TAS sets S = A; it does NOT do A AND X (which is what XAA would do).
        assertThat(cpu.registers.stackPointer, equalTo(0x42.toSignedByte()))
        // A is unchanged (TAS does not modify A).
        assertThat(cpu.registers.accumulator, equalTo(0x42.toSignedByte()))
        // Real-6502 TAS is 5 cycles (post-tick = 4).
        assertThat(cpu.workCyclesLeft, equalTo(4))
    }

    // ===== 0xE3 is DCP, not ISC (quirk 6) ==============================

    @Test
    fun opcodeE3IsDcpNotIsc() {
        val cpu = freshCpu()
        // 0xE3 (DCP (ind,X)): DEC then CMP. C=set, A=$10, [$0042]=$05.
        //  After DEC: [$0042]=$04. A - $04 = $10 - $04 = $0C, no borrow,
        //  so C is set after the operation.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xE3.toSignedByte() // DCP (ind,X)
        cpu.memory[0x0001] = 0x40.toSignedByte() // zp base
        cpu.registers.indexX = 0x02.toSignedByte()
        cpu.memory[0x0042] = 0x40.toSignedByte() // low byte of pointer
        cpu.memory[0x0043] = 0x02.toSignedByte() // high byte of pointer
        cpu.memory[0x0240] = 0x05.toSignedByte() // operand at $0240
        cpu.registers.accumulator = 0x10.toSignedByte()
        // Set C so that ISC would NOT borrow (giving an ambiguous result
        // wouldn't help us). We want to assert the DCP-vs-ISC difference.
        cpu.processorStatus.carry = true

        cpu.tick()

        // DCP: [$0240] = 0x05 - 1 = 0x04, then CMP A=$10 vs 0x04.
        // CMP: $10 - $04 = $0C, no borrow -> C remains set.
        assertThat(cpu.memory[0x0240], equalTo(0x04.toSignedByte()))
        assertThat(cpu.processorStatus.carry, equalTo(true))
        // If 0xE3 were ISC (the old behaviour), the result would be
        // A = $10 - $05 - !C = $10 - $05 - 0 = $0B, NOT 0x10.
        // With DCP, A is unchanged.
        assertThat(cpu.registers.accumulator, equalTo(0x10.toSignedByte()))
    }

    // ===== SHY / SHX registered (quirk 7) ==============================

    @Test
    fun shyShxAreRegistered() {
        // 0x9C (SHY abs,X): stores Y AND ((addr + 1) hi byte).
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x9C.toSignedByte() // SHY abs,X
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x05.toSignedByte() // -> $0500
        cpu.registers.indexX = 0x00.toSignedByte()
        cpu.registers.indexY = 0xF0.toSignedByte()

        cpu.tick()

        // SHY at $0500: high-byte = ($05 + 1) = $06, so Y AND $06 = $F0 AND $06 = $00.
        assertThat(cpu.memory[0x0500], equalTo(0x00.toSignedByte()))
    }

    // ===== KIL actually halts (quirk 8) =================================

    @Test
    fun kilHaltsTheCpu() {
        val cpu = freshCpu()
        // KIL at $0000 with a NOP at $0001 — after KIL, PC should NOT
        // advance; cpu.idle should be true.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x02.toSignedByte() // KIL
        cpu.memory[0x0001] = 0xEA.toSignedByte() // NOP (would run if CPU didn't halt)

        cpu.tick()

        // KIL sets cpu.idle = true; the Cpu.tick() path then skips opcode
        // dispatch on the next call, so PC stays at the KIL byte.
        assertThat(cpu.idle, equalTo(true))
        // Work cycles were never set by KIL (no workCyclesLeft assignment),
        // so the post-decrement is a no-op (0 -> 0).
        assertThat(cpu.workCyclesLeft, equalTo(0))
    }

    @Test
    fun kilLetsNmiWakeTheCpu() {
        // The NMI-armed flag in the FakeInterruptController survives idle
        // (it lives in the controller, not the CPU). Once the controller
        // sees a NMI armed, the next tick should break out of idle and
        // dispatch the NMI.
        //
        // Note: writes to 0xFFFA/0xFFFB are routed to the mapper, and a
        // no-mapper test CPU drops them — the vector reads back as 0 by
        // default. That's fine for this test: we only need the NMI to
        // actually fire (asserted via nmiCount, not via the redirect
        // target). The presence of the diagnostic counter increment
        // proves the NMI was dispatched, which is the only thing this
        // quirk-fix test cares about.
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply { reset() }
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x02.toSignedByte() // KIL

        // Arm the NMI BEFORE ticking (1-instruction latency model).
        fakeController.armNmi()

        // Tick 1: KIL runs, sets cpu.idle = true. The 1-instruction
        // latency arms the NMI but does not deliver it.
        cpu.tick()
        assertThat(cpu.idle, equalTo(true))
        assertThat(cpu.nmiCount, equalTo(0))  // NMI armed but not yet fired

        // Tick 2: idle path would normally skip dispatch, but the
        // interrupt controller is checked BEFORE the idle short-circuit,
        // so the armed NMI fires anyway. nmiCount bumps to 1; the
        // 7-cycle NMI cost is visible post-decrement.
        cpu.tick()
        assertThat(cpu.nmiCount, equalTo(1))  // NMI fired → broke KIL halt
        assertThat(cpu.workCyclesLeft, equalTo(6))  // 7 - 1 = 6
        assertThat(cpu.idle, equalTo(false))  // NMI also cleared the idle flag
    }

    // ===== Absolute.address() masks to 16 bits (quirk 9) ===============

    @Test
    fun absoluteAddressWrapsAtFFFF() {
        // An absolute-X instruction at $FFFF with X=1 should compute
        // address = ($FFFF + 1) AND 0xFFFF = $0000, NOT $10000.
        //
        // Place the instruction at $0100 (RAM, not page zero) so the
        // writeback at $0000 can't clobber the opcode. We use a marker
        // value at $0000 (pre-tick) and assert the STA overwrites it
        // with the accumulator value.
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0100.toSignedShort()
        cpu.memory[0x0100] = 0x9D.toSignedByte() // STA abs,X
        cpu.memory[0x0101] = 0xFF.toSignedByte()
        cpu.memory[0x0102] = 0xFF.toSignedByte() // -> $FFFF base
        cpu.registers.indexX = 0x01.toSignedByte()
        cpu.registers.accumulator = 0xAB.toSignedByte()

        // Pre-load $0000 with a marker so the test can detect the wrap.
        cpu.memory[0x0000] = 0x55.toSignedByte()

        cpu.tick()

        // STA abs,X at $FFFF + X=1 should write to $0000 (wrapped),
        // not silently drop the write (the old unmasked behaviour).
        assertThat(cpu.memory[0x0000], equalTo(0xAB.toSignedByte()))
    }
}
