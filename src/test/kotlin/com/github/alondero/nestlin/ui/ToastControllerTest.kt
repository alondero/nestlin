package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.present
import org.junit.jupiter.api.Test

/**
 * Tests for the pure-data layer behind the save-state toast/HUD (GitHub issue #129).
 *
 * The JavaFX `Text` overlay node (built in `ToastOverlay`) is driven by this
 * controller; the controller itself owns the "what should be on screen right
 * now?" decision so the rules are exercised without spinning up a JavaFX
 * toolkit. The render loop polls [ToastController.currentToast] every frame
 * (cheap) and reflects the result into the scene-graph node.
 */
class ToastControllerTest {

    @Test
    fun `controller is empty before any show call`() {
        val c = ToastController()
        assertThat(c.currentToast(nowMillis = 0L), absent())
    }

    @Test
    fun `show makes the message visible immediately`() {
        val c = ToastController()
        c.show("Saved to slot 3", ToastSeverity.INFO, nowMillis = 1_000L)

        val toast = c.currentToast(nowMillis = 1_000L)
        assertThat(toast, present())
        assertThat(toast!!.text, equalTo("Saved to slot 3"))
        assertThat(toast.severity, equalTo(ToastSeverity.INFO))
    }

    @Test
    fun `INFO toast auto-expires after its default duration`() {
        val c = ToastController()
        c.show("Saved to slot 3", ToastSeverity.INFO, nowMillis = 0L)

        // Still visible at the very end of the window.
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.INFO.durationMillis - 1),
            present()
        )
        // Gone the instant the window closes.
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.INFO.durationMillis),
            absent()
        )
    }

    @Test
    fun `ERROR toast lives 2500ms — longer than INFO`() {
        val c = ToastController()
        c.show("Save Failed: disk full", ToastSeverity.ERROR, nowMillis = 0L)

        // Still visible at the moment INFO would have expired.
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.INFO.durationMillis),
            present()
        )
        // Visible just before the ERROR cutoff …
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.ERROR.durationMillis - 1),
            present()
        )
        // … and gone at it.
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.ERROR.durationMillis),
            absent()
        )
    }

    @Test
    fun `SUBTLE toast for empty-slot loads has its own duration`() {
        val c = ToastController()
        c.show("Slot 3 is empty", ToastSeverity.SUBTLE, nowMillis = 0L)

        assertThat(
            c.currentToast(nowMillis = ToastSeverity.SUBTLE.durationMillis - 1),
            present()
        )
        assertThat(
            c.currentToast(nowMillis = ToastSeverity.SUBTLE.durationMillis),
            absent()
        )
    }

    @Test
    fun `second show replaces the first — no queue, no flicker`() {
        val c = ToastController()
        c.show("Loaded slot 1", ToastSeverity.INFO, nowMillis = 0L)
        // 50ms later, user fires Shift+F2.
        c.show("Saved to slot 2", ToastSeverity.INFO, nowMillis = 50L)

        val toast = c.currentToast(nowMillis = 60L)
        assertThat(toast, present())
        // Replace semantics: only the latest message is visible.
        assertThat(toast!!.text, equalTo("Saved to slot 2"))
    }

    @Test
    fun `replacement extends the visible window — new clock from latest show`() {
        val c = ToastController()
        c.show("Loaded slot 1", ToastSeverity.INFO, nowMillis = 0L)
        c.show("Saved to slot 2", ToastSeverity.INFO, nowMillis = 800L)

        // The original toast would have expired at INFO.durationMillis.
        // After the replacement, the new toast must still be visible at that
        // moment because its window starts at 800ms.
        val deepIntoOriginalWindow = ToastSeverity.INFO.durationMillis - 1
        val replacementShouldStillBeVisible = c.currentToast(nowMillis = deepIntoOriginalWindow)
        assertThat(replacementShouldStillBeVisible, present())
        assertThat(replacementShouldStillBeVisible!!.text, equalTo("Saved to slot 2"))

        // And the replacement is visible at 800 + INFO.durationMillis - 1.
        assertThat(
            c.currentToast(nowMillis = 800L + ToastSeverity.INFO.durationMillis - 1),
            present()
        )
        // Gone the instant the replacement's own window closes.
        assertThat(
            c.currentToast(nowMillis = 800L + ToastSeverity.INFO.durationMillis),
            absent()
        )
    }

    @Test
    fun `INFO toast lasts approximately one second per the issue spec`() {
        // The issue asks for ~1s for INFO and ~2.5s for ERROR. Pinning the
        // exact values here means any future tweak has to be conscious and
        // gets reviewed in the diff.
        assertThat(ToastSeverity.INFO.durationMillis, equalTo(1_000L))
        assertThat(ToastSeverity.SUBTLE.durationMillis, equalTo(1_200L))
        assertThat(ToastSeverity.ERROR.durationMillis, equalTo(2_500L))
    }

    @Test
    fun `clear immediately removes the current toast`() {
        val c = ToastController()
        c.show("Loaded slot 1", ToastSeverity.INFO, nowMillis = 0L)
        c.clear()
        assertThat(c.currentToast(nowMillis = 1L), absent())
    }
}
