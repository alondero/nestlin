package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller as NesController

/**
 * Thin facade over [MultiGamepadInput] (issue: 2-player support).
 *
 * GH #191 originally split the input pipeline into pure pieces
 * ([StrobeRegister], [InputSource], [PendingInputBuffer]). This class adds the
 * next layer — multi-gamepad polling — without churning the Application's
 * existing `GamepadInput` field or its `poll() / updateConfig / shutdown /
 * captureListener` call sites.
 *
 * Internally it delegates to a [MultiGamepadInput] hub that owns one
 * [GamepadPortBinding] per player slot. The facade exists so:
 *  - Application keeps a single `GamepadInput` field and a single `poll()` call.
 *  - The legacy `captureListener: ((GamepadBinding) -> Unit)?` API still works
 *    — the facade forwards to whichever binding captured (the UI doesn't need
 *    to know which player yet; Phase 5's tabbed config screen can use
 *    [captureListenerWithPlayer] for that).
 *
 * GH #182: the Controller Configuration screen needs to capture analog-stick axes and
 * POV/hat inputs as well as digital buttons. The capture listener callback now receives
 * a [GamepadBinding] (button index, axis direction, or POV direction) instead of just
 * an Int, and the matching logic in [GamepadEventClassifier] is pure and unit-tested.
 */
class GamepadInput(
    nesControllers: List<NesController>,
    initialConfig: GamepadConfig = GamepadConfig(),
) {
    private val hub = MultiGamepadInput(nesControllers, initialConfig)

    /**
     * When non-null, the next gamepad input that goes *active* is reported here as a
     * [GamepadBinding] instead of being routed to the NES pad. The Controller
     * Configuration screen sets this to capture a binding.
     */
    var captureListener: ((GamepadBinding) -> Unit)?
        get() = legacyCaptureListener
        set(value) {
            legacyCaptureListener = value
            // The legacy single-binding listener applies to whichever port captured,
            // which is fine for the single-tab UI. Phase 5's tab UI can use
            // [captureListenerWithPlayer] instead for explicit player routing.
            if (value == null) {
                hub.captureListener = null
            } else {
                hub.captureListener = { _, binding -> value(binding) }
            }
        }
    private var legacyCaptureListener: ((GamepadBinding) -> Unit)? = null

    /**
     * Player-aware capture listener for Phase 5's tabbed UI. Setting this
     * overrides [captureListener]; the next binding fired by any connected
     * gamepad reports here with the owning [Player].
     */
    var captureListenerWithPlayer: ((Player, GamepadBinding) -> Unit)?
        get() = hub.captureListener
        set(value) { hub.captureListener = value }

    /**
     * Swap in a new gamepad mapping at runtime (after the user saves new controls), without
     * tearing down and re-initialising JInput. [poll] reads [config] fresh each frame.
     */
    fun updateConfig(newConfig: GamepadConfig) {
        hub.updateConfig(newConfig)
    }

    /**
     * Initialize controller support.
     * Call this once at application startup.
     */
    fun initialize() {
        hub.initialize()
    }

    /**
     * Refresh the list of connected controllers.
     */
    fun refreshControllers() {
        hub.refreshControllers()
    }

    /**
     * Poll the current controller state and update NES controller.
     * Call this every frame (e.g., from AnimationTimer).
     */
    fun poll() {
        hub.poll()
    }

    /**
     * Get the per-player binding for tests and advanced callers. Internal because
     * most production code goes through [poll]/[updateConfig]; tests need it to
     * exercise the per-port routing helpers without booting JInput.
     */
    internal fun bindingFor(player: Player): GamepadPortBinding = hub.bindings.getValue(player)

    /**
     * Check if a controller is currently connected.
     */
    fun isControllerConnected(): Boolean = hub.isControllerConnected()

    /**
     * Get the name of the connected controller, or null if none.
     */
    fun getControllerName(): String? = hub.getControllerName()

    /**
     * Shutdown and cleanup resources.
     */
    fun shutdown() {
        hub.shutdown()
    }
}
