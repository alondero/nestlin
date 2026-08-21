package com.github.alondero.nestlin.session

/**
 * Central construction point for [RetroAchievementsService] instances.
 *
 * The factory is the only place production code calls
 * [NativeRetroAchievementsService.load] — every other caller asks the
 * factory for a service and gets either the native implementation (when
 * the façade library is available) or [NoOpRetroAchievementsService]
 * (when it's absent, corrupt, or incompatible with the current JVM's
 * bitness). The fallback is silent except for a single one-line INFO
 * message that the [NativeRetroAchievementsService.load] call site
 * already emits, so a developer can see why the native path is degraded
 * without a stack trace.
 *
 * ## Usage
 *
 * Production code (Nestlin's UI / CLI) constructs a coordinator via:
 *
 * ```
 * val coord = GameSessionCoordinator(
 *     nestlin = ...,
 *     service = RetroAchievementsServiceFactory.create(),
 * )
 * ```
 *
 * Tests and headless tools use `NoOpRetroAchievementsService` or the
 * `FakeRetroAchievementsService` test fixture directly — they never
 * call this factory, so they never load the native library. That's
 * the "headless replay, bootcheck, and unrelated unit tests must not
 * load native library" requirement from issue #267.
 */
object RetroAchievementsServiceFactory {

    /**
     * Create a service instance.
     *
     * @param forceNoOp when true, return [NoOpRetroAchievementsService]
     *   even if the native library is available. Used by tests that
     *   want to assert on the fallback path without skipping the load
     *   attempt (so the test still covers the JNA error handling).
     */
    fun create(forceNoOp: Boolean = false): RetroAchievementsService {
        if (forceNoOp) return NoOpRetroAchievementsService
        return NativeRetroAchievementsService.load() ?: NoOpRetroAchievementsService
    }

    /**
     * Is the native library available on this JVM?
     *
     * Used by the JavaFX menu's availability indicator (see
     * `Application.kt` → `buildRetroAchievementsMenu`). Probes the
     * library without creating a service instance — cheaper than
     * `create()` and side-effect-free.
     */
    fun isNativeLibraryAvailable(): Boolean = RaFacadeBindings.load() != null

    /**
     * The version string of the rcheevos client the façade was compiled
     * against. Null when the native library isn't available. Used by
     * the menu's "About" / status line.
     */
    fun rcheevosVersion(): String? = RaFacadeBindings.load()?.ra_facade_rcheevos_version()
}
