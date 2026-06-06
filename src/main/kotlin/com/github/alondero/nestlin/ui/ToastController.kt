package com.github.alondero.nestlin.ui

/**
 * Visual severity of a save-state toast (GitHub issue #129).
 *
 * Each severity carries its own auto-dismiss window. The numbers come from
 * the issue spec: ~1s for successful saves/loads ("Saved to slot 3"), a
 * slightly longer ~1.2s for subtle no-op feedback ("Slot 3 is empty"), and
 * ~2.5s for errors so the user has time to read the diagnostic before it
 * fades. The colours live on the JavaFX overlay so this enum stays pure
 * Kotlin and unit-testable without booting a JavaFX toolkit.
 */
enum class ToastSeverity(val durationMillis: Long) {
    /** Success path: "Saved to slot 3", "Loaded slot 3". */
    INFO(1_000L),
    /** Soft no-op: "Slot 3 is empty". Slightly longer because the user is
     *  most likely re-orienting after an unexpected non-effect. */
    SUBTLE(1_200L),
    /** Failure path: "Save Failed: …". Stays on screen long enough to read. */
    ERROR(2_500L),
}

/**
 * A single toast message scheduled for display, with the absolute wall-clock
 * time it should disappear. [ToastController] composes these from
 * `(text, severity, nowMillis)` triples; the JavaFX overlay reads them.
 */
data class ToastMessage(
    val text: String,
    val severity: ToastSeverity,
    val displayUntilMillis: Long,
)

/**
 * Pure-data controller for the save-state toast/HUD overlay (issue #129).
 *
 * Owns the single-toast-at-a-time policy:
 *  * `show(text, severity, nowMillis)` replaces whatever's currently on screen
 *    with a fresh message whose dismiss window starts at `nowMillis`.
 *  * `currentToast(nowMillis)` returns the current toast or null if it has
 *    expired. The JavaFX render loop polls this each frame and reflects the
 *    result onto its scene-graph `Text` node.
 *
 * Why pure: keeps the logic testable without a JavaFX toolkit, and lets the
 * clock be injected (`nowMillis`) so the "show fires while a previous toast
 * is still visible" case can be exercised deterministically. The call site
 * in `Application.kt` passes `System.currentTimeMillis()`.
 *
 * Why replace (not queue): when the user rattles off `F1, F2, F3` in a burst,
 * they want to see the *latest* outcome, not wait through three sequential
 * reads. The issue explicitly allows either "queue OR replace cleanly".
 */
class ToastController {
    @Volatile
    private var current: ToastMessage? = null

    /**
     * Display [text] at [severity] starting at [nowMillis]. Replaces any
     * existing toast — the new toast's dismiss window starts from this call,
     * NOT from the original (otherwise a burst of saves would dismiss the
     * latest message instantly because it inherited an older clock).
     */
    fun show(text: String, severity: ToastSeverity, nowMillis: Long) {
        current = ToastMessage(
            text = text,
            severity = severity,
            displayUntilMillis = nowMillis + severity.durationMillis,
        )
    }

    /**
     * The toast that should be visible at [nowMillis], or null if the most
     * recent toast has expired (or there has never been one). Cheap — safe
     * to call from the render loop.
     */
    fun currentToast(nowMillis: Long): ToastMessage? {
        val c = current ?: return null
        if (nowMillis >= c.displayUntilMillis) {
            // Clear the slot once the window closes so a stale reference
            // can't pin a now-invisible message in memory indefinitely.
            current = null
            return null
        }
        return c
    }

    /** Force-dismiss the current toast (e.g. on ROM unload). */
    fun clear() {
        current = null
    }
}
