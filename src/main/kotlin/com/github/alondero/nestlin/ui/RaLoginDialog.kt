package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.session.RaCredentialsStore
import com.github.alondero.nestlin.session.RaSignInManager
import com.github.alondero.nestlin.session.RaSignInState
import com.github.alondero.nestlin.util.Redactor
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Modality
import javafx.stage.Stage

/**
 * Modal username/password dialog for RetroAchievements sign-in (issue #268).
 *
 * Submits the entered credentials via [RaSignInManager.signInWithPassword]
 * which discards the password locally; only the returned token survives
 * (in the OS-level Java Preferences). The dialog is modal so the user
 * can't interact with the rest of the UI during the round-trip.
 *
 * The dialog renders the current sign-in state at the bottom so the user
 * can see whether the request is in flight, succeeded, or failed. The
 * "Sign In" button is disabled while the manager reports
 * [RaSignInState.Authenticating] to enforce the single-flight rule.
 */
class RaLoginDialog(
    private val manager: RaSignInManager,
    private val credentialsStore: RaCredentialsStore = RaCredentialsStore(),
) {
    private val stage: Stage = Stage()
    private val usernameField: TextField = TextField()
    private val passwordField: PasswordField = PasswordField()
    private val statusLabel: Label = Label("Enter your RetroAchievements credentials.")
    private val signInButton: Button = Button("Sign In")
    private val cancelButton: Button = Button("Cancel")

    private var token: RaSignInManager.ListenerToken? = null

    init {
        stage.title = "Sign In to RetroAchievements"
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.isResizable = false

        // Pre-fill the username from the last successful login (case-corrected).
        credentialsStore.load()?.let { saved ->
            if (saved.username.isNotEmpty()) {
                usernameField.text = saved.username
            }
        }

        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            padding = Insets(14.0, 14.0, 14.0, 14.0)
        }
        grid.add(Label("Username:"), 0, 0)
        grid.add(usernameField, 1, 0)
        grid.add(Label("Password:"), 0, 1)
        grid.add(passwordField, 1, 1)

        signInButton.setOnAction {
            val username = usernameField.text.trim()
            val password = passwordField.text
            if (username.isEmpty() || password.isEmpty()) {
                statusLabel.text = "Username and password are required."
                return@setOnAction
            }
            // Hand off to the manager — the password is consumed locally and
            // never reaches the credentials store. The manager updates state
            // asynchronously; we re-render the status label on the listener.
            manager.signInWithPassword(username, password)
            // Don't close the dialog — let the user see the result. The
            // listener below closes the dialog on a successful SignedIn.
        }
        cancelButton.setOnAction { stage.close() }

        val buttons = HBox(8.0).apply {
            alignment = Pos.CENTER_RIGHT
            children.addAll(cancelButton, signInButton)
            ButtonBar.setButtonData(signInButton, ButtonBar.ButtonData.OK_DONE)
            ButtonBar.setButtonData(cancelButton, ButtonBar.ButtonData.CANCEL_CLOSE)
        }

        val root = VBox(8.0).apply {
            children.addAll(
                Label("Sign in to RetroAchievements").apply {
                    font = Font.font("System", FontWeight.BOLD, 14.0)
                },
                grid,
                statusLabel.apply {
                    font = Font.font("System", FontWeight.NORMAL, 11.0)
                    style = "-fx-text-fill: #808080;"
                    isWrapText = true
                    maxWidth = 280.0
                },
                buttons,
            )
        }

        stage.scene = Scene(root)

        // Close on success — the listener fires on the manager's bridge
        // thread; we hop to JavaFX before touching the scene graph.
        token = manager.addListener { state -> onStateChanged(state) }
        renderState(manager.state)

        // Re-render on close so we always tear down the listener.
        stage.setOnCloseRequest {
            token?.let { manager.removeListener(it) }
            token = null
        }
    }

    /** Show the dialog. Blocks (modally) until the user signs in or cancels. */
    fun showAndWait() {
        stage.showAndWait()
    }

    private fun onStateChanged(state: RaSignInState) {
        Platform.runLater { renderState(state) }
    }

    private fun renderState(state: RaSignInState) {
        when (state) {
            is RaSignInState.SignedIn -> {
                statusLabel.text = "Signed in as ${state.account.displayName}."
                // Don't auto-close — let the user dismiss with OK; auto-close
                // races the user reading the confirmation.
                signInButton.isDisable = true
                passwordField.text = ""  // clear from memory
                stage.close()
            }
            is RaSignInState.Authenticating -> {
                statusLabel.text = "Signing in…"
                signInButton.isDisable = true
                passwordField.isDisable = true
                usernameField.isDisable = true
            }
            is RaSignInState.SignedOut -> {
                statusLabel.text = "Enter your RetroAchievements credentials."
                signInButton.isDisable = false
                passwordField.isDisable = false
                usernameField.isDisable = false
            }
            is RaSignInState.Offline -> {
                statusLabel.text = "Sign-in failed: ${Redactor.redactMessage(state.cause)}. Try again."
                signInButton.isDisable = false
                passwordField.isDisable = false
                usernameField.isDisable = false
                passwordField.text = ""
            }
            is RaSignInState.Unavailable -> {
                statusLabel.text = "RetroAchievements integration is not available in this build."
                signInButton.isDisable = true
                passwordField.isDisable = true
                usernameField.isDisable = true
            }
        }
    }
}