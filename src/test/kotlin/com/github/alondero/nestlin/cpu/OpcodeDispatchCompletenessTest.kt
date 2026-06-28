package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.cpu.opcode.OpcodesRefactor
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression bar for issue #192 (Opcodes sealed-class refactor).
 *
 * Locks down the byte set currently in [OpcodesRefactor.map] so that a
 * refactor that drops or adds entries fails loudly at build/test time. The
 * current set covers 250 of 256 possible opcodes; the 6 unmapped bytes are
 * validated explicitly so any "is this byte really unmapped?" question has
 * a documented answer.
 *
 * **Why this test exists.** The OpcodesRefactor map is 250 lines of
 * `put(0xXX, ...)`. One missed entry during the refactor would compile
 * cleanly and pass GoldenLogTest (which only checks the opcodes its trace
 * exercises). This test fails the build if the byte set drifts.
 */
class OpcodeDispatchCompletenessTest {

    @Test
    fun `dispatch table covers exactly the 250 currently-mapped opcodes`() {
        val mapped = OpcodesRefactor.map.keys.toSet()
        assertThat(
            "OpcodesRefactor.map size — if this changes, the test was wrong about the baseline",
            mapped.size, equalTo(250),
        )
    }

    @Test
    fun `the 6 unmapped opcodes match the expected set`() {
        val mapped = OpcodesRefactor.map.keys.toSet()
        val unmapped = (0..0xFF).filter { it !in mapped }.toSet()
        // The 6 unmapped opcodes, derived empirically from the original
        // Opcodes.kt init block. If a refactor changes this set, decide
        // explicitly whether to update the test or the dispatcher.
        //   0x0B = AAC (unofficial); 0x2B = AAC; 0x8B = unknown/very unstable
        //   0x9C = SHY unhooked variant; 0x9E = SHX unhooked variant
        //   0xCB = very unstable SBC+DEC combo
        val expectedUnmapped = setOf(0x0B, 0x2B, 0x8B, 0x9C, 0x9E, 0xCB)
        assertThat(unmapped, equalTo(expectedUnmapped))
    }

    @Test
    fun `expectedUnmapped constant matches the derived set`() {
        val mapped = OpcodesRefactor.map.keys.toSet()
        val unmapped = (0..0xFF).filter { it !in mapped }.toSet()
        val expectedUnmapped = setOf(0x0B, 0x2B, 0x8B, 0x9C, 0x9E, 0xCB)
        assertThat(unmapped, equalTo(expectedUnmapped))
    }
}