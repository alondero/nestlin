package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GitHub issue #230, items 2 & 3:
 *
 *   2. "Sweep does not mute for `shift == 0` on target overflow — hardware
 *       still computes the target continuously (with shift 0 + add mode the
 *       target is `2×period`, muting when `period > 0x3FF`)."
 *
 *   3. "Sweep skips writing target periods < 8 — hardware writes it whenever
 *       it isn't a muting condition."
 *
 * Both items split `Sweep` into two responsibilities:
 *
 *  - `isMuting(currentPeriod)` — pure output layer gate, called by
 *    `PulseChannel.output()` to decide silence. Must compute the target
 *    via `currentPeriod + (currentPeriod shr shift)` even when shift=0
 *    (so changeAmount collapses to currentPeriod, giving target=2*period).
 *
 *  - `clock(currentPeriod, updatePeriod)` — state-mutating. The lower-bound
 *    (`targetPeriod >= 8`) is NOT a hardware muting condition; the output
 *    layer's own `< 8` silence is what masks the audible effect.
 */
class SweepEdgeCasesTest {

    // ---------------------------------------------------------------------
    // Item 2 — isMuting must compute the target even when shift == 0
    // ---------------------------------------------------------------------

    @Test
    fun `isMuting mutes when shift=0 and add-mode period exceeds 0x3FF`() {
        // With shift=0, changeAmount = currentPeriod shr 0 = currentPeriod.
        // In add mode: target = currentPeriod + currentPeriod = 2*currentPeriod.
        // For currentPeriod = 0x400 (= 1024): target = 0x800 (overflow),
        // so the channel must be muted.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 0)
        assertTrue(
            s.isMuting(currentPeriod = 0x400),
            "shift=0 add-mode at period 0x400: target 0x800 > 0x7FF, must mute",
        )
    }

    @Test
    fun `isMuting does NOT mute when shift=0 add-mode target stays in range`() {
        // currentPeriod = 0x3FF → target = 0x7FE → in range, so NOT muted.
        // Pre-fix this passes by accident (shift==0 short-circuit), but we
        // also expect the post-fix path to keep it not-muted. The next test
        // (currentPeriod = 0x400) is the one that proves the fix is live.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 0)
        assertFalse(
            s.isMuting(currentPeriod = 0x3FF),
            "shift=0 add-mode at period 0x3FF: target 0x7FE <= 0x7FF, must NOT mute",
        )
    }

    @Test
    fun `isMuting mutes when shift=0 subtract-mode target underflows`() {
        // channelId=1, negate=true: target = currentPeriod - changeAmount - 1.
        // With shift=0: changeAmount = currentPeriod, so target = -1.
        // Output should be silenced because the target underflowed.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = true, shift = 0)
        assertTrue(
            s.isMuting(currentPeriod = 0x10),
            "shift=0 subtract ch1 at period 0x10: target -1, must mute",
        )
    }

    @Test
    fun `isMuting keeps existing shift-greater-than-zero behaviour`() {
        // Sanity check that the fix doesn't regress the non-zero-shift path.
        // period=0x100, shift=1, add mode: changeAmount = 0x80, target = 0x180
        // (within range). No overflow → not muted.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 1)
        assertFalse(
            s.isMuting(currentPeriod = 0x100),
            "shift=1 add-mode at period 0x100: target 0x180, must NOT mute",
        )
    }

    // ---------------------------------------------------------------------
    // Item 3 — clock() must write the target even when targetPeriod < 8
    // ---------------------------------------------------------------------

    @Test
    fun `clock writes target even when target drops below 8`() {
        // currentPeriod = 5, shift = 1: changeAmount = 2, target = 7.
        // Pre-fix the guard `targetPeriod in 8..0x7FF` skips the write.
        // Post-fix the guard is only the upper-overflow gate; the output
        // layer silences < 8 separately.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 1)
        var written: Int? = null
        s.clock(currentPeriod = 5) { written = it }
        assertEquals(
            7,
            written,
            "hardware writes target=7 even though it is below the audible threshold of 8",
        )
    }

    @Test
    fun `clock still omits write when target overflows above 0x7FF`() {
        // currentPeriod = 0x600, shift = 1, add mode: changeAmount = 0x300,
        // target = 0x900. That is past 0x7FF — the hardware muting gate
        // must suppress the update.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 1)
        var written: Int? = null
        s.clock(currentPeriod = 0x600) { written = it }
        assertNull(
            written,
            "hardware must NOT write a target that overflows above 0x7FF",
        )
    }

    @Test
    fun `clock still writes target inside the in-range window`() {
        // currentPeriod = 0x10, shift = 2, add mode: changeAmount = 4,
        // target = 0x14. In range; must write.
        val s = Sweep(channelId = 1)
        s.configure(enabled = true, period = 1, negate = false, shift = 2)
        var written: Int? = null
        s.clock(currentPeriod = 0x10) { written = it }
        assertEquals(0x14, written, "in-range target must write through to updatePeriod")
    }
}
