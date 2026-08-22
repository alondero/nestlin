package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.session.GameSessionCoordinator
import com.github.alondero.nestlin.session.RaSignInManager
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.Region

/**
 * Modal dialog shown when the user signs in to RetroAchievements while a
 * game is already running (issue #269 AC #9).
 *
 * Activating achievements silently against a partial timeline would
 * produce inconsistent state — some frames evaluated as signed-out,
 * others as signed-in — so the policy is: explain that achievements
 * require a restart, and provide an explicit restart action that
 * preserves battery-backed save data.
 *
 * The dialog is a single screen with two buttons:
 *  - "Restart for achievements" — calls [GameSessionCoordinator.restartForAchievements],
 *    which flushes battery RAM, reloads the same ROM, restores battery,
 *    and re-prepares the service against the new sign-in state.
 *  - "Continue without achievements" — dismisses the dialog; the user
 *    keeps playing as signed-in for any future ROM loads.
 *
 * The dialog is non-blocking — it does NOT pause the emulation thread.
 * The user can read the message at their leisure; if they choose
 * "Continue without achievements" the coordinator's existing
 * prepareGame handling will continue to produce an unsigned-in
 * service state for the current game.
 */
object RaAchievementRestartDialog {

    /**
     * Show the dialog against [coordinator]. Safe to call from any
     * thread — re-posts to the JavaFX thread before touching scene-graph
     * nodes.
     *
     * The [RaSignInManager] reference is retained for callers that want
     * to extend the dialog (e.g. add a "don't ask again" toggle); the
     * base dialog does not need it.
     */
    fun show(@Suppress("UNUSED_PARAMETER") manager: RaSignInManager?, coordinator: GameSessionCoordinator) {
        if (Platform.isFxApplicationThread()) {
            showNow(coordinator)
        } else {
            Platform.runLater { showNow(coordinator) }
        }
    }

    private fun showNow(coordinator: GameSessionCoordinator) {
        val alert = Alert(Alert.AlertType.INFORMATION)
        alert.title = "Restart for achievements"
        alert.headerText = "Achievements require a restart"
        alert.contentText = buildString {
            append("You've signed in to RetroAchievements while ")
            append(coordinator.nestlin.currentGameName())
            append(" is running.\n\n")
            append("Activating achievements now would observe only part of the current ")
            append("play session — some frames were already evaluated without the ")
            append("achievement runtime active. To get accurate achievement tracking, ")
            append("restart the game. Your battery-backed save data will be preserved.\n\n")
            append("Continue without achievements to keep playing now and enable them ")
            append("on your next ROM load.")
        }
        alert.dialogPane.minWidth = 480.0
        alert.dialogPane.minHeight = Region.USE_PREF_SIZE

        val restartType = ButtonType("Restart for achievements", ButtonBar.ButtonData.OK_DONE)
        val continueType = ButtonType("Continue without achievements", ButtonBar.ButtonData.CANCEL_CLOSE)
        alert.buttonTypes.setAll(restartType, continueType)

        // Don't pause the emulator — the dialog is informational, the
        // game keeps running while the user reads.
        val result = alert.showAndWait()
        if (result.isPresent && result.get() === restartType) {
            // AC #9: the restart must preserve battery RAM. The coordinator's
            // restartForAchievements goes through the documented
            // battery-flush + reload + battery-restore + service-prepare
            // ordering — battery survives by construction.
            coordinator.restartForAchievements()
        }
    }
}
