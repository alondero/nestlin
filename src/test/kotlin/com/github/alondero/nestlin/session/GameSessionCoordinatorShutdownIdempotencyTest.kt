package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins [GameSessionCoordinator.shutdown]'s documented idempotency contract.
 *
 * The class KDoc promises "Permanent shutdown. Idempotent — calling
 * shutdown twice is a no-op." The bug surfaced in production when
 * `Application.handleExit()` calls `sessionCoordinator.shutdown()` and
 * then `Platform.exit()`, which triggers JavaFX's `Application.stop()`,
 * which calls `sessionCoordinator.shutdown()` again.
 *
 * Without the guard, the second call runs `service.unloadGame()` on a
 * handle the first call's `service.shutdown()` has already destroyed.
 * JNA then throws `java.lang.Error: Invalid memory access` (NOT
 * `UnsatisfiedLinkError`, so the per-method narrow catch misses it),
 * JavaFX's stop() propagates the Error up, and the JVM exits with
 * code 1 — every Nestlin quit shows "Nestlin has stopped working"
 * to the user.
 *
 * The fix: early-return on a second `shutdown()` call. The fake
 * service records every call, so the second invocation must NOT
 * add to the call log — proving the no-op semantics.
 */
class GameSessionCoordinatorShutdownIdempotencyTest {

    @Test
    fun `shutdown is idempotent across consecutive calls`() {
        val nestlin = Nestlin()
        val (coord, fake) = coordinator(nestlin)

        coord.shutdown()
        val callsAfterFirst = fake.calls.toList()

        assertDoesNotThrow { coord.shutdown() }

        // The second shutdown must be a true no-op — it must NOT re-issue
        // unloadGame or shutdown against the (now-destroyed) service.
        assertEquals(callsAfterFirst, fake.calls,
            "second shutdown() must not produce additional service calls")
    }

    private fun coordinator(
        nestlin: Nestlin = Nestlin(),
        service: FakeRetroAchievementsService = FakeRetroAchievementsService(),
        prepareTimeoutMillis: Long = GameSessionCoordinator.DEFAULT_PREPARE_TIMEOUT_MS,
    ): Pair<GameSessionCoordinator, FakeRetroAchievementsService> {
        val coord = GameSessionCoordinator(
            nestlin = nestlin,
            service = service,
            romHasher = Sha256RomHasher,
            prepareTimeoutMillis = prepareTimeoutMillis,
        )
        return coord to service
    }
}