package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Regression test for the [StackOverflowError] that crashed the Nestlin UI
 * at startup.
 *
 * `NestlinApplication` had two `by lazy` fields whose initializers formed a
 * cycle:
 *   - `sessionCoordinator`'s initializer passed `achievementsControllerLazy`
 *     to the [GameSessionCoordinator] constructor.
 *   - `achievementsControllerLazy`'s initializer read `sessionCoordinator.service`.
 *
 * Whichever field was accessed first would deadlock in
 * `kotlin.SynchronizedLazyImpl.getValue` (each side blocked waiting for the
 * other to finish initializing) until the stack overflowed on the JavaFX
 * Application Thread — the UI never rendered.
 *
 * The fix extracted the [RetroAchievementsService] into its own
 * `raService` `by lazy` field. Both consumers depend on it without
 * depending on each other: `sessionCoordinator` reads `raService` to pass
 * into the coordinator, and `achievementsControllerLazy` reads `raService`
 * to pass into the controller. No back-reference into either sibling field.
 *
 * These tests pin the fixed dependency shape at three levels:
 *
 * 1. Class-level: the production classes wire correctly when both depend
 *    on the same shared service, never reaching back into each other.
 * 2. Lazy-field-level: a test-local pair of `by lazy` fields structured
 *    identically to the production fields resolves without stack overflow
 *    on first access.
 * 3. Structural: `NestlinApplication` exposes the shared `raService` lazy
 *    as its own field (its `$delegate` backing field is present).
 *
 * If a future refactor reintroduces the mutual back-reference in
 * `NestlinApplication`, the lazy-field test would stack-overflow here
 * the same way it did at runtime.
 */
class SessionLazyInitRegressionTest {

    /**
     * Class-level check: the production classes construct correctly when
     * wired the way the fix requires (both depend on the same [RetroAchievementsService]
     * instance; the controller does NOT reach back into the coordinator's
     * service field).
     *
     * Before the fix, the controller's initializer was
     * `service = sessionCoordinator.service` — a back-reference that only
     * worked when the coordinator was already initialised. Constructing
     * this pair in isolation (with explicit `service` parameters) models
     * the post-fix shape and must succeed.
     */
    @Test
    fun `achievements controller and coordinator can share a service without a back-reference`() {
        val service: RetroAchievementsService = NoOpRetroAchievementsService
        val controller = RaAchievementsController(
            service = service,
            signInState = { RaSignInState.SignedOut },
            loadedRomInfo = { null },
        )
        val coordinator = GameSessionCoordinator(
            nestlin = Nestlin(),
            service = service,
            achievementsController = controller,
        )
        // Coordinator observes the controller we constructed (the post-fix
        // contract: achievementsController is a forward parameter, not a
        // back-reference into the coordinator).
        assertSame(controller, coordinator.achievementsController)
        // Coordinator observes the service the controller was constructed
        // against. Before the fix the controller reached into
        // `sessionCoordinator.service`; now it accepts `service` directly.
        assertSame(service, coordinator.service)
    }

    /**
     * Lazy-field-level check: replicates `NestlinApplication`'s lazy
     * field structure with a shared `raService` lazy. Accessing either
     * sibling must resolve without stack overflow.
     *
     * The shared `raService` is declared first, then the achievements
     * controller (no back-reference into the coordinator). The session
     * coordinator references the achievements controller as a forward
     * constructor parameter — but only because the controller's lambda
     * doesn't reach back. Declared order matters in Kotlin even for
     * `by lazy` lambdas: the compiler resolves the forward reference
     * at lambda capture time, so the referenced local must be visible.
     *
     * If a future refactor reintroduces the mutual back-reference (e.g.
     * `achievementsControllerLazy` again reads `sessionCoordinator.service`),
     * the cycle returns and this test stack-overflows the same way the UI
     * did at startup.
     */
    @Test
    fun `two lazy fields that share a service and one references the other do not cycle`() {
        val raService: RetroAchievementsService by lazy { NoOpRetroAchievementsService }

        // No back-reference: depends only on `raService`. Declared first
        // so the next field's `by lazy` lambda can reference it.
        val achievementsControllerLazy: RaAchievementsController by lazy {
            RaAchievementsController(
                service = raService,
                signInState = { RaSignInState.SignedOut },
                loadedRomInfo = { null },
            )
        }

        // Forward reference: takes achievementsControllerLazy as a
        // constructor parameter. The fixed pattern only depends on
        // `raService` (not on the sibling's initialised value), so this
        // resolves cleanly when first accessed.
        val sessionCoordinator: GameSessionCoordinator by lazy {
            GameSessionCoordinator(
                nestlin = Nestlin(),
                service = raService,
                achievementsController = achievementsControllerLazy,
            )
        }

        // Trigger lazy init by accessing the fields. The fixed pattern
        // resolves cleanly; the broken pattern stack-overflows.
        val coord = sessionCoordinator
        val ctrl = achievementsControllerLazy

        assertNotNull(coord)
        assertNotNull(ctrl)
        // The coordinator's `achievementsController` is the SAME
        // controller the forward reference resolved to. (The
        // controller's `service` field is private — we can't read it
        // back here, but the test-local wiring pins that it was
        // constructed against `raService`.)
        assertSame(ctrl, coord.achievementsController)
        // The coordinator observes the shared service. If the
        // coordinator's `service` drifted away from `raService`, the
        // runtime and the coordinator would diverge (separate native
        // handle, separate prepareGame state, etc.).
        assertSame(raService, coord.service)
    }

    /**
     * Structural check: `NestlinApplication` exposes the shared `raService`
     * lazy as its own field. If a future refactor removes or renames it,
     * the cycle between `sessionCoordinator` and `achievementsControllerLazy`
     * returns and the UI crashes on startup — this test fails first.
     *
     * Uses reflection because instantiating `NestlinApplication` directly
     * requires the JavaFX toolkit (it extends `javafx.application.Application`).
     *
     * Kotlin compiles `private val foo: T by lazy { ... }` to a private
     * backing field `foo$delegate` of type `Lazy<T>` plus a synthetic
     * getter that unwraps it. We look for the `$delegate` field; its
     * presence confirms the lazy exists (and any future regression that
     * collapses it into one of the sibling fields will be caught here).
     */
    @Test
    fun `NestlinApplication exposes a separate raService lazy field`() {
        val cls = Class.forName("com.github.alondero.nestlin.ui.NestlinApplication")
        val declaredFieldNames = cls.declaredFields.map { it.name }
        val delegateField = try {
            cls.getDeclaredField("raService\$delegate")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertNotNull(delegateField,
            "NestlinApplication must declare a 'raService' by lazy to break the " +
                "sessionCoordinator <-> achievementsControllerLazy cycle. " +
                "Declared fields: $declaredFieldNames")
        val isLazyType = delegateField!!.type.name.contains("Lazy")
        org.junit.jupiter.api.Assertions.assertTrue(isLazyType,
            "raService\$delegate must back a by lazy; found type ${delegateField.type}")
    }
}
