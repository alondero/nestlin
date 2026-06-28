package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller as NesController
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment

/**
 * Multi-gamepad hub (issue: 2-player support).
 *
 * Owns the JInput [ControllerEnvironment] and a [GamepadPortBinding] per
 * [Player] slot. [refreshControllers] enumerates the OS's connected gamepads
 * and assigns them to free slots in deterministic order: the first connected
 * GAMEPAD or STICK goes to [Player.ONE], the second to [Player.TWO].
 *
 * Caveat: JInput's `Controller.name` can change on USB re-enumeration, so a
 * pad replugged on a different USB port may swap P1↔P2. Tracking by name would
 * need a stable per-host id; the simpler "enumeration order" rule is good
 * enough for v1 and is documented in the config screen.
 *
 * [GamepadInput] is a thin facade over this hub — the facade keeps the
 * Application's single [GamepadInput] field working without churn at call
 * sites.
 */
internal class MultiGamepadInput(
    nesControllers: List<NesController>,
    private var config: GamepadConfig,
) {
    /** Per-player adapter that polls one physical gamepad and routes to one NES port. */
    val bindings: Map<Player, GamepadPortBinding> = nesControllers
        .zip(Player.entries)
        .associate { (ctrl, player) -> player to GamepadPortBinding(player, ctrl, config) }

    /**
     * When non-null, the next gamepad input that goes active is reported here as a
     * [Player, GamepadBinding] pair instead of being routed to the NES pad. The
     * Controller Configuration screen uses this to capture a binding for whichever
     * tab is currently listening.
     */
    var captureListener: ((Player, GamepadBinding) -> Unit)? = null
        set(value) {
            field = value
            // Forward the listener to each binding — but with the player context baked in.
            bindings.forEach { (player, binding) ->
                binding.captureListener = value?.let { listener ->
                    { binding -> listener(player, binding) }
                }
            }
        }

    private var initialized = false

    fun initialize() {
        try {
            JInputNatives.prepare()
            initialized = true
            println("[GAMEPAD] Multi-gamepad hub initialized")
            refreshControllers()
        } catch (e: Exception) {
            println("[GAMEPAD] Failed to initialize: ${e.message}")
            initialized = false
        }
    }

    /**
     * Enumerate connected gamepads and assign them to free Player slots in order.
     * Already-attached controllers are skipped (so a re-enumeration doesn't
     * shuffle the assignments mid-session).
     */
    fun refreshControllers() {
        if (!initialized) return
        try {
            val controllers = ControllerEnvironment.getDefaultEnvironment().controllers
            val gamepads = controllers.filter {
                it.type == Controller.Type.GAMEPAD || it.type == Controller.Type.STICK
            }
            assignGamepads(gamepads)
        } catch (e: Exception) {
            println("[GAMEPAD] Error refreshing controllers: ${e.message}")
        }
    }

    /**
     * Pure routing logic: assign [gamepads] to free Player slots in enumeration
     * order. First unassigned gamepad → P1, second → P2. Already-attached
     * bindings are skipped. Exposed `internal` so tests can verify the routing
     * with controlled input lists.
     */
    internal fun assignGamepads(gamepads: List<Controller>) {
        assignGamepadsByName(gamepads.map { it.name }) { name ->
            gamepads.firstOrNull { it.name == name }
        }
    }

    /**
     * Routing logic split out so tests can drive it with name lists alone
     * (avoiding JInput's abstract Controller class). [resolveController] is
     * called once per name to attach the actual JInput Controller object; in
     * tests, callers can ignore it and just inspect `binding.jinputController`.
     */
    internal fun assignGamepadsByName(
        names: List<String>,
        resolveController: (String) -> Controller?,
    ) {
        val attachedNames = bindings.values.mapNotNull { it.jinputController?.name }.toSet()
        val unassigned = names.filter { it !in attachedNames }

        val slots = Player.entries.map { it to bindings.getValue(it) }
        var nextPad = 0
        for ((_, binding) in slots) {
            if (binding.jinputController != null) continue
            if (nextPad >= unassigned.size) break
            val name = unassigned[nextPad]
            resolveController(name)?.let { binding.attachController(it) }
            nextPad++
            println("[GAMEPAD] Controller connected ($name, player=${binding.player})")
        }
    }

    /**
     * Poll every attached binding. Drop bindings whose gamepad disappeared mid-frame.
     */
    fun poll() {
        if (!initialized) return
        bindings.values.toList().forEach { binding ->
            if (!binding.poll()) {
                // gamepad disconnected — try to fill the freed slot from any new arrivals
                refreshControllers()
            }
        }
    }

    fun updateConfig(newConfig: GamepadConfig) {
        config = newConfig
        bindings.values.forEach { it.updateConfig(newConfig) }
    }

    fun shutdown() {
        if (initialized) {
            bindings.values.forEach { it.detachController() }
            initialized = false
            println("[GAMEPAD] Shutdown complete")
        }
    }

    fun isControllerConnected(): Boolean =
        bindings.values.any { it.jinputController != null }

    fun getControllerName(): String? =
        bindings.values.firstNotNullOfOrNull { it.jinputController?.name }
}
