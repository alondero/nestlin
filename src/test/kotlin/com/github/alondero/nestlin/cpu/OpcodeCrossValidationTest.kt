package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.cpu.opcode.OpcodesRefactor
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Phase 2 of issue #192 — now a smoke test against the new dispatcher.
 *
 * Originally this test cross-validated the OLD [com.github.alondero.nestlin.cpu.Opcodes]
 * against the NEW [OpcodesRefactor]. After Phase 3 deleted the old
 * dispatcher, that comparison became moot — there's only one dispatcher
 * now. This test was rewritten to validate the new dispatcher's API
 * surface: byte-set coverage, sanity bounds on cycle counts, and that
 * every mapped opcode has the required polymorphic `evaluate` method.
 *
 * The exhaustive cycle-count assertions live in
 * [OpcodeCycleTableTest]; byte-set assertions live in
 * [OpcodeDispatchCompletenessTest]. This file is the integration smoke
 * check that the new dispatcher is functionally usable end-to-end.
 */
class OpcodeCrossValidationTest {

    @Test
    fun `new dispatcher covers the canonical 250-byte set`() {
        // After Phase 3 the new dispatcher IS the production dispatcher.
        // This is a sanity check that the byte set matches the baseline
        // locked in by OpcodeDispatchCompletenessTest.
        assertThat(OpcodesRefactor.map.size, equalTo(250))
    }

    @Test
    fun `every mapped opcode has a non-null Opcode instance with evaluate`() {
        // `evaluate(cpu)` is the abstract method on the sealed `Opcode`
        // base class — every concrete subclass MUST implement it. The
        // compiler enforces this, so the only way for this test to fail
        // is if a subclass was added without an `evaluate` override (a
        // compile error). The test exists as a documented check that the
        // dispatcher's byte coverage maps to a polymorphic Opcode.
        val count = OpcodesRefactor.map.values.count { it != null }
        assertThat(
            "every mapped byte must have a non-null Opcode instance",
            count, equalTo(OpcodesRefactor.map.size),
        )
    }

    @Test
    fun `cycle counts are within the 6502-realistic 1-10 range`() {
        // Real-6502 base cycles for any addressing mode range from 2
        // (implied/imm) to 8 (rare RMW combinations). We use a slightly
        // wider bound (10) to catch obvious errors like a 0 or 100.
        val outOfRange = OpcodesRefactor.map.entries.filter { (_, op) ->
            op.cycles < 1 || op.cycles > 10
        }.map { (byte, op) -> "0x%02X=%d".format(byte, op.cycles) }
        assertThat(
            "all opcodes must have plausible cycle counts: " +
                outOfRange.joinToString(", "),
            outOfRange, equalTo(emptyList<String>()),
        )
    }
}