package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.session.BootPlacardEvent
import com.github.alondero.nestlin.session.RaBootPlacardController
import com.github.alondero.nestlin.session.RaImageCache
import com.github.alondero.nestlin.session.RaSignInState
import javafx.application.Platform
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * JavaFX overlay that renders the boot-placard event from
 * [RaBootPlacardController] (issue #269 AC #6-7).
 *
 * Three documented states:
 *  - **Recognized**: ~3-second placard showing badge image, game title,
 *    "Unlocked X of Y achievements", "Earned A of B points".
 *  - **Recognized with no core achievements**: clear "this game has no
 *    core achievements" message, no nag.
 *  - **Unrecognized / Service unavailable**: subtle "ROM not recognized
 *    on RetroAchievements" message, no nag.
 *
 * Signed-out loads (AC #8) NEVER display anything — the controller
 * publishes [BootPlacardEvent.SignedOut] and the overlay clears.
 *
 * The overlay uses the same [StackPane] + transparency pattern as
 * [ToastController] so it can sit on top of the canvas without
 * intercepting input.
 */
class RaBootPlacard(
    private val controller: RaBootPlacardController,
    private val signInState: () -> RaSignInState,
    private val imageCache: RaImageCache = RaImageCache(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Length of the recognized-game placard (issue #269: "approximately three seconds"). */
    private val placardDurationMillis: Long = 3_000L

    /** Length of the unrecognized / no-achievements message (shorter — subtle, not nag). */
    private val subtleDurationMillis: Long = 2_500L

    private val pane: StackPane = StackPane().apply {
        // Transparent — the placard is just a coloured rounded rectangle
        // inside. We need the StackPane so the placard can be centred at
        // the top of the canvas without absorbing input.
        isPickOnBounds = false
        isMouseTransparent = true
        style = "-fx-background-color: transparent;"
    }

    /** The currently visible node, or null when nothing should be on screen. */
    private val currentNode: AtomicReference<javafx.scene.Node?> = AtomicReference(null)

    /** The future for the in-flight badge image fetch, if any. Used to cancel on rapid switches. */
    private var inFlightBadge: CompletableFuture<BufferedImage?>? = null

    /**
     * Hook the overlay to the controller. Listeners fire synchronously on
     * the publishing thread — we re-post to the JavaFX thread before
     * mutating scene-graph nodes.
     */
    fun install() {
        controller.addListener { event ->
            // Generation guard at the consumer level: a stale event from a
            // background image fetch must not overwrite the placard for
            // the active ROM.
            if (event.generation != controller.generation) return@addListener
            if (Platform.isFxApplicationThread()) {
                applyEvent(event)
            } else {
                Platform.runLater { applyEvent(event) }
            }
        }
    }

    /**
     * Apply the event to the overlay. Visible-for window starts now; the
     * overlay's render loop checks against the current time and hides
     * itself when the window closes.
     */
    private fun applyEvent(event: BootPlacardEvent) {
        when (event) {
            is BootPlacardEvent.Idle, is BootPlacardEvent.SignedOut -> {
                // AC #8: signed-out loads show no placard. Clear whatever
                // was on screen.
                hideNow()
            }
            is BootPlacardEvent.ServiceUnavailable -> {
                showSubtle("RetroAchievements unavailable — playing without achievements")
                scheduleHide(subtleDurationMillis)
            }
            is BootPlacardEvent.Recognized -> {
                val summary = event.summary
                val unlocked = summary.numUnlockedAchievements
                val total = summary.numCoreAchievements
                val earned = summary.pointsUnlocked
                val max = summary.pointsCore
                val badge = event.badgeImage
                if (badge != null) {
                    showRecognized(badge, summary.title, unlocked, total, earned, max)
                } else {
                    // No badge yet — fetch async, then re-apply the event
                    // when the image lands (the generation guard prevents
                    // a stale completion from overwriting the new placard).
                    showRecognized(placeholderBadge(), summary.title, unlocked, total, earned, max)
                    fetchBadgeAsync(summary.imageUrl, summary.title, unlocked, total, earned, max)
                }
                scheduleHide(placardDurationMillis)
            }
            is BootPlacardEvent.RecognizedNoCore -> {
                val title = event.summary.title
                showSubtle("$title has no core achievements")
                scheduleHide(subtleDurationMillis)
            }
            is BootPlacardEvent.Unrecognized -> {
                showSubtle(
                    "${event.displayName} not recognized on RetroAchievements " +
                        "(possible ROM hack / translation / alternate dump)"
                )
                scheduleHide(subtleDurationMillis)
            }
        }
    }

    private fun fetchBadgeAsync(url: String, title: String, unlocked: Int, total: Int, earned: Int, max: Int) {
        if (url.isBlank()) return
        inFlightBadge?.cancel(false)
        val capturedGen = controller.generation
        val future = imageCache.fetch(url)
        inFlightBadge = future
        future.whenComplete { image, _ ->
            Platform.runLater {
                // The generation guard at the consumer level — if the
                // controller moved on, this completion is stale and we
                // must NOT overwrite the current placard.
                if (controller.generation != capturedGen) return@runLater
                if (image != null) {
                    showRecognized(image, title, unlocked, total, earned, max)
                    scheduleHide(placardDurationMillis)
                }
            }
        }
    }

    private fun showRecognized(badge: BufferedImage, title: String, unlocked: Int, total: Int, earned: Int, max: Int) {
        val root = HBox(8.0).apply {
            style = "-fx-background-color: rgba(0,0,0,0.85); " +
                "-fx-background-radius: 8; -fx-padding: 8 12 8 12;"
        }
        val badgeNode = ImageView(toFxImage(badge)).apply {
            fitHeight = 48.0
            isPreserveRatio = true
            isSmooth = false  // pixel-art crisp
        }
        val text = VBox(2.0).apply {
            val titleLabel = Label(title).apply {
                font = Font.font("System", FontWeight.BOLD, 14.0)
                textFill = Color.WHITE
            }
            val unlockedLabel = Label("Unlocked $unlocked of $total achievements").apply {
                font = Font.font("System", 12.0)
                textFill = Color.LIGHTGRAY
            }
            val earnedLabel = Label("Earned $earned of $max points").apply {
                font = Font.font("System", 12.0)
                textFill = Color.LIGHTGRAY
            }
            children.addAll(titleLabel, unlockedLabel, earnedLabel)
        }
        root.children.addAll(badgeNode, text)
        showNode(root)
    }

    private fun showSubtle(message: String) {
        val root = Label(message).apply {
            font = Font.font("System", 12.0)
            textFill = Color.LIGHTGRAY
            style = "-fx-background-color: rgba(0,0,0,0.75); " +
                "-fx-background-radius: 8; -fx-padding: 6 10 6 10;"
        }
        showNode(root)
    }

    private fun showNode(node: javafx.scene.Node) {
        // Replace whatever was on screen. The placard is single-instance
        // by design — multiple ROMs in quick succession would otherwise
        // pile up overlapping labels.
        pane.children.clear()
        pane.children.add(node)
        // Position near the top of the canvas with a small inset.
        pane.maxWidth = Region.USE_PREF_SIZE
        StackPane.setAlignment(node, javafx.geometry.Pos.TOP_CENTER)
        StackPane.setMargin(node, javafx.geometry.Insets(16.0, 0.0, 0.0, 0.0))
        currentNode.set(node)
    }

    private fun hideNow() {
        pane.children.clear()
        currentNode.set(null)
    }

    /**
     * Schedule the placard to be hidden after [durationMillis] ms. The
     * scheduler is a simple future-based timer; cancel-on-overwrite is
     * implicit (a new event replaces the existing node immediately).
     */
    private fun scheduleHide(durationMillis: Long) {
        val hideAt = clock() + durationMillis
        Thread {
            try {
                Thread.sleep(durationMillis)
                Platform.runLater {
                    if (clock() >= hideAt) hideNow()
                }
            } catch (_: InterruptedException) {
                // Cancellation — the placard was replaced before the timer
                // fired. Nothing to do.
            }
        }.apply {
            isDaemon = true
            name = "ra-boot-placard-hide"
        }.start()
    }

    private fun placeholderBadge(): BufferedImage {
        // A small grey rectangle — what the UI shows while the real
        // badge image is in flight. Matches the documented "placeholder-
        // backed" AC #11.
        return BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            try {
                g.color = java.awt.Color.DARK_GRAY
                g.fillRect(0, 0, 48, 48)
            } finally {
                g.dispose()
            }
        }
    }

    private fun toFxImage(buf: BufferedImage): Image {
        // WritableImage is the standard JavaFX bridge from BufferedImage.
        // We marshal through a PNG-encoded ByteArrayInputStream so the
        // conversion doesn't depend on JavaFX's internal pixel format
        // matching the BufferedImage's.
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(buf, "PNG", baos)
        return Image(ByteArrayInputStream(baos.toByteArray()))
    }

    /**
     * The JavaFX overlay node. Application wires this into its scene
     * graph at the top of the canvas. Visible-only — never blocks input.
     */
    val node: javafx.scene.Node get() = pane
}
