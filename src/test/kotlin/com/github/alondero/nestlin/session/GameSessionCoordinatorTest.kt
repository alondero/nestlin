package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.testutil.TestRoms
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the [GameSessionCoordinator] ordering contract documented in
 * `GameSessionCoordinator.kt`'s class doc: every ROM-lifecycle entry point
 * produces a deterministic sequence of emulator + service + hook calls,
 * and a failure inside one step never leaves the next step skipped.
 *
 * The coordinator is the only boundary that knows the "right" order — the
 * tests below pin it so a future refactor of the entry points can't
 * silently drop a step (e.g. forget to flush outgoing battery before
 * replacing the ROM, or forget to unload the previous game before
 * preparing a new one). The fake service records every call; the hooks
 * are simple counters so the test can assert both the *order* and the
 * *count* of each side effect.
 */
class GameSessionCoordinatorTest {

    private val romPath: Path = TestRoms.nestestPath()

    /** Build a coordinator whose hooks are observable counters. */
    private fun coordinator(
        nestlin: Nestlin = Nestlin(),
        service: FakeRetroAchievementsService = FakeRetroAchievementsService(),
        hooks: GameSessionHooks = GameSessionHooks.NONE,
    ): Pair<GameSessionCoordinator, FakeRetroAchievementsService> {
        val coord = GameSessionCoordinator(nestlin, service, hooks)
        return coord to service
    }

    // ---------------------------------------------------------------------
    // loadRom — the canonical ROM-replacement ordering.
    // ---------------------------------------------------------------------

    @Test
    fun `loadRom from fresh state fires the documented ordering`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)

        // After loadRom: the emulator has the new ROM, the service has been
        // prepared exactly once with that ROM's GameSessionInfo.
        assertNotNull(nestlin.loadedRom)
        assertEquals(romPath, nestlin.loadedRom!!.sourcePath)
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    @Test
    fun `loadRom replaces an existing ROM with unload-then-prepare`() {
        // First load: a single PrepareGame call.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        val firstInfo = fake.lastPreparedInfo
        fake.calls.clear()

        // Second load (same path — irrelevant; we're testing the
        // outgoing-flush + service-unload ordering).
        coord.loadRom(romPath)

        // The service must have unloaded the previous game before the new
        // prepare — that's the "ROM changes clear incompatible transient
        // UI/session state" acceptance criterion.
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.UnloadGame,
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
        // The two PrepareGame calls carried distinct infos (the second
        // load's GamePak is a fresh snapshot — same bytes, same region,
        // but the equality is structural so a regression that aliased
        // the previous info would still satisfy the equality contract
        // and slip past this assertion; the count assertion above is the
        // load-bearing one).
        assertNotNull(firstInfo)
    }

    @Test
    fun `loadRom with prepareGame returning false still installs the ROM`() {
        // The parent PRD's rule: "a failing achievements service MUST
        // NOT prevent gameplay". A `false` return from prepareGame must
        // be absorbed — the ROM is loaded and nestlin.loadedRom is set
        // even though the service is idle for this session.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin, FakeRetroAchievementsService(prepareGameResult = false))
        coord.loadRom(romPath)

        assertNotNull(nestlin.loadedRom)
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    @Test
    fun `loadRom absorbs a thrown prepareGame`() {
        // Same contract as `prepareGameResult = false` — the throw is
        // converted to the same recovery path (coordinator swallows).
        val nestlin = Nestlin()
        val boom = RuntimeException("network unreachable")
        val (coord, fake) = coordinator(
            nestlin,
            FakeRetroAchievementsService(prepareGameException = boom),
        )
        coord.loadRom(romPath)

        assertNotNull(nestlin.loadedRom)
        // PrepareGameFailed records the exception and the call, so the
        // coordinator's absorption is visible in the log.
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.PrepareGameFailed(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    // ---------------------------------------------------------------------
    // loadBytes — bytes-only variant; no battery, no sourcePath.
    // ---------------------------------------------------------------------

    @Test
    fun `loadBytes installs a ROM without a source path`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        val bytes = TestRoms.nestestBytes()

        coord.loadBytes(bytes, "synthesised")

        assertNotNull(nestlin.loadedRom)
        assertNull(nestlin.loadedRom!!.sourcePath)
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    // ---------------------------------------------------------------------
    // powerReset — full reload for path-based; just-reset for bytes-only.
    // ---------------------------------------------------------------------

    @Test
    fun `powerReset with a path-based ROM unloads, reloads, and re-prepares`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.powerReset()

        // powerReset on a path-based ROM is a full "boot from power-on"
        // — the service must see Unload → Prepare in order.
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.UnloadGame,
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    @Test
    fun `powerReset with bytes-only ROM still fires the full service sequence`() {
        // Issue #266's "Reset... produce the documented service lifecycle
        // events exactly once" AC applies regardless of whether the ROM
        // has a source path. The bytes-only branch does UnloadGame +
        // PrepareGame (no battery to flush, no mapper to re-read from
        // disk) so the service's state machine is on a fresh baseline
        // matching the engine's just-power-cycled state.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadBytes(TestRoms.nestestBytes())
        fake.calls.clear()

        coord.powerReset()

        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.UnloadGame,
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
        )
    }

    @Test
    fun `powerReset without a loaded ROM is a no-op`() {
        // The user is sitting on the empty boot screen — no ROM, no
        // service session, nothing to power-cycle.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.powerReset()
        assertEquals(0, fake.calls.size)
    }

    // ---------------------------------------------------------------------
    // softReset — CPU soft reset + runtime reset.
    // ---------------------------------------------------------------------

    @Test
    fun `softReset resets the runtime without unloading the game`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.softReset()

        // No UnloadGame — the game is still active. Just a runtime
        // reset to the post-prepareGame baseline.
        fake.assertCallsInOrder(FakeRetroAchievementsService.Call.ResetRuntime)
    }

    @Test
    fun `softReset without a loaded ROM is a CPU-only reset`() {
        // nestlin.softReset is safe on an empty machine; the coordinator
        // still forwards to the service, which is the trivial no-op.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.softReset()
        fake.assertCallsInOrder(FakeRetroAchievementsService.Call.ResetRuntime)
    }

    // ---------------------------------------------------------------------
    // unloadRom / shutdown — idempotent teardown.
    // ---------------------------------------------------------------------

    @Test
    fun `unloadRom clears the ROM and unloads the service`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.unloadRom()

        assertNull(nestlin.loadedRom)
        fake.assertCallsInOrder(FakeRetroAchievementsService.Call.UnloadGame)
    }

    @Test
    fun `unloadRom forwards to the service even when no ROM is loaded`() {
        // The coordinator's unloadRom is safe to invoke when no ROM is
        // loaded — there's no exception, no null deref. The service's
        // own `unloadGame` is the idempotent one (the contract says it
        // is "safe to call when no game is prepared"), so the
        // coordinator simply forwards.
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.unloadRom()
        coord.unloadRom()
        // Two calls (one per invocation) — the SERVICE is idempotent, not the coordinator.
        assertEquals(2, fake.calls.size)
        assertEquals(listOf(
            FakeRetroAchievementsService.Call.UnloadGame,
            FakeRetroAchievementsService.Call.UnloadGame,
        ), fake.calls)
    }

    @Test
    fun `shutdown unloads the active game and tears down the service`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.shutdown()

        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.UnloadGame,
            FakeRetroAchievementsService.Call.Shutdown,
        )
    }

    @Test
    fun `shutdown without a loaded ROM still tears down the service`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.shutdown()
        fake.assertCallsInOrder(FakeRetroAchievementsService.Call.Shutdown)
    }

    @Test
    fun `shutdown is idempotent`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.shutdown()
        coord.shutdown()
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.Shutdown,
        )
    }

    // ---------------------------------------------------------------------
    // captureProgress / restoreProgress / evaluateFrame — the seams that
    // issue #268 (save-state format) and the per-frame wiring will use.
    // ---------------------------------------------------------------------

    @Test
    fun `captureProgress returns null when no ROM is loaded`() {
        val nestlin = Nestlin()
        val (coord, _) = coordinator(nestlin)
        assertNull(coord.captureProgress())
    }

    @Test
    fun `captureProgress returns the service bytes when a game is prepared`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)

        val progress = coord.captureProgress()
        // The fake produces "RA" + a monotonic token. We just check the
        // shape — the exact value is the fake's contract.
        assertNotNull(progress)
        assertEquals(3, progress!!.size)
        assertEquals(0x52.toByte(), progress[0])
        assertEquals(0x41.toByte(), progress[1])
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.PrepareGame(GameSessionInfo.from(nestlin.loadedRom!!, Region.NTSC)),
            FakeRetroAchievementsService.Call.SerializeProgress(1),
        )
    }

    @Test
    fun `restoreProgress is a no-op when no ROM is loaded`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.restoreProgress(byteArrayOf(1, 2, 3))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `restoreProgress forwards bytes to the service`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        val payload = byteArrayOf(0x11, 0x22, 0x33)
        coord.restoreProgress(payload)

        assertSame(payload, fake.lastRestoredProgress)
    }

    @Test
    fun `restoreProgress accepts null to reset the runtime`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.restoreProgress(null)

        assertNull(fake.lastRestoredProgress)
        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.RestoreProgress(null),
        )
    }

    @Test
    fun `evaluateFrame is a no-op when no ROM is loaded`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.evaluateFrame(42L)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `evaluateFrame forwards to the service when a game is prepared`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)
        coord.loadRom(romPath)
        fake.calls.clear()

        coord.evaluateFrame(0L)
        coord.evaluateFrame(1L)
        coord.evaluateFrame(2L)

        fake.assertCallsInOrder(
            FakeRetroAchievementsService.Call.EvaluateFrame(0L),
            FakeRetroAchievementsService.Call.EvaluateFrame(1L),
            FakeRetroAchievementsService.Call.EvaluateFrame(2L),
        )
    }

    // ---------------------------------------------------------------------
    // Hooks — every entry point fires onBefore/onAfter at the documented
    // moment, and every service call fires onServiceCallStart/End around
    // it. The application depends on these to pause/resume its emulation
    // thread and refresh UI.
    // ---------------------------------------------------------------------

    @Test
    fun `loadRom fires before-and-after hooks around the service sequence`() {
        val nestlin = Nestlin()
        val counts = HookCounter()
        val hooks = GameSessionHooks(
            onBeforeRomChange = { counts.before++ },
            onAfterRomChange = { counts.after++ },
            onServiceCallStart = { counts.serviceStart++ },
            onServiceCallEnd = { counts.serviceEnd++ },
        )
        val (coord, fake) = coordinator(nestlin, hooks = hooks)
        coord.loadRom(romPath)

        // Exactly one before / one after.
        assertEquals(1, counts.before)
        assertEquals(1, counts.after)
        // Two service calls (UnloadGame + PrepareGame), each bookended.
        assertEquals(2, counts.serviceStart)
        assertEquals(2, counts.serviceEnd)
        // Sanity: a PrepareGame actually fired.
        assertTrue(fake.calls.any { it is FakeRetroAchievementsService.Call.PrepareGame })
    }

    @Test
    fun `softReset does not fire onBefore-or-onAfter hooks`() {
        // Soft reset is an intra-session transition — no ROM change, no
        // UI teardown, just a runtime reset.
        val nestlin = Nestlin()
        val counts = HookCounter()
        val (coord, _) = coordinator(nestlin, hooks = GameSessionHooks(
            onBeforeRomChange = { counts.before++ },
            onAfterRomChange = { counts.after++ },
        ))
        coord.loadRom(romPath)
        counts.before = 0; counts.after = 0

        coord.softReset()

        assertEquals(0, counts.before)
        assertEquals(0, counts.after)
    }

    @Test
    fun `onBeforeRomChange fires before any battery or service work`() {
        // If a future hook implementation needs to cancel a movie session
        // before any emulator state mutates, the documented order says
        // it gets first dibs. We verify by counting the fake calls
        // before vs after the hook fires.
        val nestlin = Nestlin()
        val fake = FakeRetroAchievementsService()
        val serviceCallsAtBefore = AtomicInteger(0)
        val coord = GameSessionCoordinator(
            nestlin = nestlin,
            service = fake,
            hooks = GameSessionHooks(
                onBeforeRomChange = { serviceCallsAtBefore.set(fake.calls.size) },
            ),
        )
        coord.loadRom(romPath)

        // No service calls had fired by the time onBeforeRomChange
        // returned — the hook ran first, as documented.
        assertEquals(0, serviceCallsAtBefore.get())
    }

    @Test
    fun `onAfterRomChange receives the freshly loaded LoadedRom`() {
        val nestlin = Nestlin()
        var captured: LoadedRom? = null
        val (coord, _) = coordinator(
            nestlin,
            hooks = GameSessionHooks(onAfterRomChange = { captured = it }),
        )
        coord.loadRom(romPath)
        assertSame(nestlin.loadedRom, captured)
    }

    @Test
    fun `unloadRom passes null to onAfterRomChange`() {
        // unloadRom is "back to empty boot screen"; the hook needs to
        // see null so the application can render the "No Game Loaded"
        // state instead of the just-unloaded game's identity.
        val nestlin = Nestlin()
        var captured: LoadedRom? = nestlin.loadedRom  // sentinel: should be overwritten
        val (coord, _) = coordinator(
            nestlin,
            hooks = GameSessionHooks(onAfterRomChange = { captured = it }),
        )
        coord.loadRom(romPath)
        coord.unloadRom()
        assertNull(captured)
    }

    @Test
    fun `shutdown does not fire onAfterRomChange`() {
        // shutdown is the last call of the session — by the time it
        // returns the application is about to be torn down, so the
        // class doc says onAfterRomChange is intentionally not fired.
        val nestlin = Nestlin()
        var afterCount = 0
        val (coord, _) = coordinator(
            nestlin,
            hooks = GameSessionHooks(onAfterRomChange = { afterCount++ }),
        )
        coord.loadRom(romPath)
        coord.shutdown()
        // One after for the load, none for the shutdown.
        assertEquals(1, afterCount)
    }

    // ---------------------------------------------------------------------
    // Failure-path coverage. The coordinator's "never throw" contract
    // applies to service failures, not to bad inputs (a bad ROM path is
    // the caller's bug). The exception below makes sure an I/O error
    // from `nestlin.load` propagates instead of being silently swallowed
    // — the failure should be loud and visible at the call site.
    // ---------------------------------------------------------------------

    @Test
    fun `loadRom surfaces an I-O error from the underlying load`() {
        val nestlin = Nestlin()
        val (coord, _) = coordinator(nestlin)
        // A path that does not exist — the underlying loader raises a
        // checked-style exception (IOOBE) via the RomLoader.
        val missing = Paths.get("Z:/no/such/rom.nes")
        assertThrowsWithMessage<Exception>("Z:\\no\\such\\rom.nes") {
            coord.loadRom(missing)
        }
    }

    /** Plain counter box — clearer than four separate AtomicIntegers. */
    private class HookCounter {
        var before: Int = 0
        var after: Int = 0
        var serviceStart: Int = 0
        var serviceEnd: Int = 0
    }
}
