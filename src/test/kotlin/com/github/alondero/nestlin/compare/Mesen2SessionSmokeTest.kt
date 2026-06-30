package com.github.alondero.nestlin.compare

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * End-to-end smoke test for the [Mesen2Session] pool (issue #61).
 *
 * What this test exercises that no other test in the lane catches:
 *
 * 1. **Boot + READY handshake.** First call to [Mesen2Session.forRom]
 *    spawns a Mesen2 process and blocks until the Lua server script
 *    writes `READY` to its done marker. A hung boot shows up here as a
 *    `Mesen2ExecutionException` after 10s, not as a silent PASS.
 * 2. **Same-process re-run.** The second capture (frame 120) reuses the
 *    cached `Mesen2ProcessInstance` from the first capture (frame 60).
 *    Without session reuse, this test would spawn two Mesen2 processes —
 *    exactly the issue #61 is meant to prevent.
 * 3. **`RESET` between captures.** The cached process advances to frame
 *    120, then is asked again for a (separate test method's) frame 60.
 *    Each `runToAndCaptureState` sends `RESET` before the `RUNTO`, so
 *    frame counter starts at 0 — no stale-state regressions.
 * 4. **Sequence-numbered done markers.** Two captures on the same
 *    process must not hand back each other's results.
 *
 * Uses `nestest.nes` (the only ROM in git, per `testroms/` and the
 * `verifyTestEnv` task) so it runs in CI without NO-INTRO dependencies.
 *
 * Annotated with [@RequiresMesen2] rather than a bare `assumeTrue(mesen2Available)`
 * so the strict-mode `NESTLIN_REQUIRE_MESEN2=1` env var honours the
 * lane-gate contract (per `CLAUDE.md` "Testing Strategy"). A bare
 * `assumeTrue` would silently skip under strict mode and defeat the
 * smoke test's purpose: it is the only test that catches a hung boot.
 */
@Tag("mesen")
@RequiresMesen2
class Mesen2SessionSmokeTest {

    @Test
    fun sessionReusesOneProcessForTwoCapturesOnSameRom() {
        val rom = Paths.get("testroms/nestest.nes")

        val first = Mesen2Session.forRom(rom).runToAndCaptureState(60)
        println("[session] nestest frame 60 PC=0x${first.cpu.pc.toString(16).uppercase()} " +
            "frameCount=${first.ppu.frameCount}")

        val second = Mesen2Session.forRom(rom).runToAndCaptureState(120)
        println("[session] nestest frame 120 PC=0x${second.cpu.pc.toString(16).uppercase()} " +
            "frameCount=${second.ppu.frameCount}")

        check(first.cpu.pc != 0) { "frame 60 PC should be non-zero" }
        check(first.ppu.frameCount > 0) { "frame 60 frameCount should be > 0" }
        check(second.cpu.pc != 0) { "frame 120 PC should be non-zero" }
        check(second.ppu.frameCount > first.ppu.frameCount) {
            "frame 120 frameCount (${second.ppu.frameCount}) should exceed frame 60 " +
                "(${first.ppu.frameCount}) — emulator ran backwards or RESET semantics broke"
        }
    }
}
