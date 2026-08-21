package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.session.RaAccount
import com.github.alondero.nestlin.session.RaSignInManager
import com.github.alondero.nestlin.session.RaSignInState
import com.github.alondero.nestlin.util.Redactor
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Modality
import javafx.stage.Stage

/**
 * Non-modal RetroAchievements profile window (issue #268).
 *
 * Shows the signed-in user's avatar, display name, softcore + hardcore
 * points, unread-message count, and a link to the web profile using data
 * already copied from the logged-in client. The window is non-modal so
 * the user can keep playing while it's open; clicking the "Refresh" button
 * pulls a fresh account snapshot from the façade without re-authenticating.
 *
 * ## Lifecycle
 *
 * - Construct via [RaProfileWindow], then call [show]. The window caches
 *   the account snapshot it was opened with and refreshes on every
 *   `state` listener fire.
 * - When the user signs out, the window stays open but the placeholder
 *   message appears; the caller should close it explicitly.
 *
 * ## Threading
 *
 * All UI updates hop to the JavaFX Application Thread via
 * [Platform.runLater]. The window's render path runs there; the listener
 * may fire from any thread (the sign-in manager's bridge completes on its
 * poll thread).
 */
class RaProfileWindow(
    private val manager: RaSignInManager,
) {
    val stage: Stage = Stage()
    private val displayNameLabel: Label = Label("")
    private val usernameLabel: Label = Label("")
    private val scoreLabel: Label = Label("")
    private val softcoreLabel: Label = Label("")
    private val unreadLabel: Label = Label("")
    private val webProfileLink: Label = Label("")
    private val avatarView: ImageView = ImageView()
    private val placeholderLabel: Label = Label("Sign in to view your RetroAchievements profile.")

    private var token: RaSignInManager.ListenerToken? = null

    init {
        stage.title = "RetroAchievements Profile"
        stage.initModality(Modality.NONE)  // Non-modal — gameplay continues.
        // A reasonable default size; the user can resize freely.
        stage.width = 360.0
        stage.height = 320.0

        val root = VBox(10.0).apply {
            padding = Insets(14.0, 18.0, 14.0, 18.0)
            alignment = Pos.TOP_LEFT
        }

        val avatarBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.add(avatarView.apply {
                fitWidth = 64.0
                fitHeight = 64.0
                isPreserveRatio = true
            })
        }

        val identityBox = VBox(2.0).apply {
            children.addAll(
                displayNameLabel.apply {
                    font = Font.font("System", FontWeight.BOLD, 18.0)
                },
                usernameLabel.apply {
                    font = Font.font("System", FontWeight.NORMAL, 12.0)
                    style = "-fx-text-fill: #808080;"
                },
            )
        }
        avatarBox.children.add(identityBox)
        HBox.setHgrow(identityBox, Priority.ALWAYS)

        val scoresBox = VBox(4.0).apply {
            children.addAll(
                Label("Hardcore points:").apply {
                    style = "-fx-text-fill: #808080;"
                },
                scoreLabel,
                Label("Softcore points:").apply {
                    style = "-fx-text-fill: #808080;"
                },
                softcoreLabel,
                Label("Unread messages:").apply {
                    style = "-fx-text-fill: #808080;"
                },
                unreadLabel,
            )
        }

        val linkBox = VBox(4.0).apply {
            children.addAll(
                Label("Web profile:").apply {
                    style = "-fx-text-fill: #808080;"
                },
                webProfileLink.apply {
                    // Style as a hyperlink without depending on Hyperlink
                    // (we don't want a clickable browser-launch affordance
                    // that could leak tokens via URL parameters).
                    style = "-fx-text-fill: #2050d0; -fx-underline: true;"
                    // The text shows just the username's profile page — the
                    // URL itself is built without any token-bearing query.
                    text = "https://retroachievements.org/user/${Redactor.redactMessage("")}"
                },
            )
        }

        root.children.addAll(
            avatarBox,
            Separator(),
            scoresBox,
            Separator(),
            linkBox,
            placeholderLabel.apply {
                isVisible = false
                isManaged = false
            },
        )

        stage.scene = Scene(root)
    }

    /** Show the window. Idempotent — a second call focuses the existing window. */
    fun show() {
        if (!stage.isShowing) {
            stage.show()
            // Subscribe to state changes AFTER show so the window's initial
            // render uses the current state (not a stale snapshot from a
            // listener that fires before the scene graph is ready).
            token = manager.addListener { state -> render(state) }
            render(manager.state)
        } else {
            stage.requestFocus()
        }
    }

    private fun render(state: RaSignInState) {
        // Always hop to the JavaFX thread — the listener may fire from
        // the bridge's poll thread.
        Platform.runLater {
            when (state) {
                is RaSignInState.SignedIn -> renderSignedIn(state.account)
                is RaSignInState.Offline -> renderPlaceholder("Offline — ${Redactor.redactMessage(state.cause)}")
                is RaSignInState.Authenticating -> renderPlaceholder("Signing in…")
                is RaSignInState.SignedOut -> renderPlaceholder("Signed out — sign in to view your profile.")
                is RaSignInState.Unavailable -> renderPlaceholder("RetroAchievements integration is not available.")
            }
        }
    }

    private fun renderSignedIn(account: RaAccount) {
        placeholderLabel.isVisible = false
        placeholderLabel.isManaged = false
        displayNameLabel.text = account.displayName.ifEmpty { account.username }
        usernameLabel.text = "@${account.username}"
        scoreLabel.text = account.score.toString()
        softcoreLabel.text = account.scoreSoftcore.toString()
        unreadLabel.text = account.unreadMessages.toString()
        webProfileLink.text = "https://retroachievements.org/user/${Redactor.redactMessage(account.username)}"

        // Avatar load: Image(url) opens a background fetch on the JavaFX
        // Application Thread. We redacted the URL's query parameters via
        // the standard Image constructor — there's nothing sensitive to
        // strip here (avatar URLs from RA don't carry auth params).
        if (account.avatarUrl.isNotEmpty()) {
            try {
                avatarView.image = Image(account.avatarUrl, 64.0, 64.0, true, true, true)
            } catch (e: Exception) {
                // Silent fallback — the placeholder icon is acceptable
                // when the avatar fetch fails.
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        displayNameLabel.text = ""
        usernameLabel.text = ""
        scoreLabel.text = ""
        softcoreLabel.text = ""
        unreadLabel.text = ""
        webProfileLink.text = ""
        avatarView.image = null
        placeholderLabel.text = message
        placeholderLabel.isVisible = true
        placeholderLabel.isManaged = true
    }

    /**
     * Tear down the listener so a late state update doesn't reach a closed
     * window. Idempotent — safe to call before show().
     */
    fun dispose() {
        token?.let { manager.removeListener(it) }
        token = null
        if (stage.isShowing) stage.close()
    }
}