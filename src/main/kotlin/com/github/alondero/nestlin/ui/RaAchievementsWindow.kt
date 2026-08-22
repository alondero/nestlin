package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.session.RaAchievement
import com.github.alondero.nestlin.session.RaAchievementsController
import com.github.alondero.nestlin.session.RaAchievementsWindowViewModel
import com.github.alondero.nestlin.session.RaImageCache
import com.github.alondero.nestlin.session.RaSignInState
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.ProgressBar
import javafx.scene.control.Separator
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Modality
import javafx.stage.Stage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Non-modal loaded-game achievements window (issue #272).
 *
 * Shows the currently-loaded RetroAchievements game's badge + title +
 * unlocked/total counts + earned/total points + overall progress bar,
 * followed by a virtualized list of achievements grouped by the
 * official runtime's progress buckets (Recently Unlocked / Active
 * Challenges / Almost There / Unlocked / Locked / Unsupported / Unsynced).
 *
 * ## Lifecycle
 *
 * - Construct via [RaAchievementsWindow], then call [show]. The
 *   window binds a listener to [RaAchievementsController] and renders
 *   the latest view-model on every transition.
 * - When the user closes the window, the listener detaches (no
 *   dangling observer on a dead stage).
 * - When the user closes the emulator / shuts down, call [dispose]
 *   so a late state update doesn't reach a torn-down scene graph.
 *
 * ## Threading
 *
 * All UI updates hop to the JavaFX Application Thread via
 * [Platform.runLater]. The listener may fire from any thread (the
 * controller's `refresh()` is called from the coordinator's hook
 * surface — typically the JavaFX thread for UI paths but a worker
 * thread for boot paths).
 *
 * ## Generation guard
 *
 * The window compares the view-model's `generation` to the
 * controller's current generation before applying it. A late
 * view-model from a previous game cannot overwrite the current
 * view — matches the pattern used by [RaBootPlacard] for the placard.
 */
class RaAchievementsWindow(
    private val controller: RaAchievementsController,
    private val signInState: () -> RaSignInState,
    private val imageCache: RaImageCache = RaImageCache(),
) {

    /** Opaque listener token; detached in [dispose] / [show] re-attach paths. */
    private var listenerToken: RaAchievementsController.ListenerToken? = null

    /** Last-applied view-model generation. Used to drop stale UI updates. */
    @Volatile private var lastAppliedGeneration: Long = -1L

    val stage: Stage = Stage()

    // Header — game badge + title + unlocked/total + earned/total + progress bar.
    private val headerBadge: ImageView = ImageView().apply {
        fitWidth = 64.0
        fitHeight = 64.0
        isPreserveRatio = true
        isSmooth = false  // pixel-art crisp at every scale
    }
    private val headerTitleLabel: Label = Label("").apply {
        font = Font.font("System", FontWeight.BOLD, 18.0)
    }
    private val headerCountsLabel: Label = Label("").apply {
        font = Font.font("System", 12.0)
        textFill = Color.DARKGRAY
    }
    private val headerPointsLabel: Label = Label("").apply {
        font = Font.font("System", 12.0)
        textFill = Color.DARKGRAY
    }
    private val headerProgressBar: ProgressBar = ProgressBar(0.0).apply {
        prefWidth = Double.MAX_VALUE
    }

    // Body — virtualized grouped list.
    private val listView: ListView<Row> = ListView<Row>().apply {
        isFocusTraversable = false
        // Virtualization is on by default in JavaFX; the fixedCellSize
        // hint lets the list pre-allocate cell heights so a 200-row set
        // renders the visible 12-15 without ever instantiating the rest.
        fixedCellSize = 64.0
    }

    // State placeholder — replaces the body when the view-model is not
    // [Recognized]. One label that re-uses itself across states.
    private val placeholderLabel: Label = Label("").apply {
        font = Font.font("System", 14.0)
        textFill = Color.DARKGRAY
        isWrapText = true
        alignment = Pos.TOP_LEFT
        maxWidth = Double.MAX_VALUE
    }
    private val placeholderContainer: VBox = VBox(placeholderLabel).apply {
        padding = Insets(20.0)
        alignment = Pos.TOP_LEFT
        isVisible = false
        isManaged = false
    }

    init {
        stage.title = "RetroAchievements — Loaded Game"
        stage.initModality(Modality.NONE)  // Non-modal — gameplay continues.
        // Default size — the user can resize freely. 480×640 fits the
        // ~12-row visible window on a typical monitor and is well below
        // the 1024×768 default the placard would otherwise impose.
        stage.width = 480.0
        stage.height = 640.0

        val root = VBox(10.0).apply {
            padding = Insets(14.0, 18.0, 14.0, 18.0)
            alignment = Pos.TOP_LEFT
        }

        // Header layout — badge on the left, title + counts + progress bar stacked on the right.
        val headerBox = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children.add(headerBadge)
        }
        val headerText = VBox(4.0).apply {
            children.addAll(headerTitleLabel, headerCountsLabel, headerPointsLabel, headerProgressBar)
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        headerBox.children.add(headerText)

        root.children.addAll(
            headerBox,
            Separator(),
            listView.apply {
                VBox.setVgrow(this, Priority.ALWAYS)
                // Cell factory is set once at construction; subsequent
                // refreshes only swap the items list, letting JavaFX
                // reuse existing cells. Rebuilding the factory on
                // every refresh would force cell re-creation and
                // erase the row recycling the virtualization depends on.
                cellFactory = javafx.util.Callback<ListView<Row>, javafx.scene.control.ListCell<Row>> {
                    AchievementRowCell(imageCache)
                }
                placeholder = placeholderContainer
                selectionModel = null  // read-only list; no selection state
            },
            placeholderContainer,
        )

        stage.scene = Scene(root)
    }

    /**
     * One row in the achievement list. The header variant is the bucket
     * section title; the achievement variant is a single achievement row.
     * The sealed type lets the cell factory exhaustively render every case.
     */
    sealed interface Row {
        data class Header(val bucketLabel: String, val count: Int) : Row
        data class Achievement(val achievement: RaAchievement) : Row
    }

    /**
     * Build the flat list of [Row]s for the [snapshot] — one header per
     * non-empty bucket, then each achievement under it. Empty buckets
     * are omitted (the issue #272 AC #4 sections are useful only when
     * there's something to show in them).
     */
    private fun flatten(snapshot: com.github.alondero.nestlin.session.RaAchievementListSnapshot): List<Row> {
        val rows = ArrayList<Row>(snapshot.buckets.sumOf { it.achievements.size } + snapshot.buckets.size)
        for (bucket in snapshot.buckets) {
            if (bucket.achievements.isEmpty()) continue
            rows += Row.Header(bucketLabel = bucket.label, count = bucket.achievements.size)
            for (a in bucket.achievements) rows += Row.Achievement(a)
        }
        return rows
    }

    /**
     * Show the window. Idempotent — a second call focuses the existing
     * window. Subscribes to the controller's listener AFTER the scene
     * graph is attached so the initial render uses the controller's
     * current state (not a stale snapshot from before the window was
     * ready).
     */
    fun show() {
        if (!stage.isShowing) {
            stage.show()
            listenerToken?.let { controller.removeListener(it) }
            listenerToken = controller.addListener { viewModel ->
                if (viewModel.generation != controller.generation) return@addListener
                Platform.runLater { render(viewModel) }
            }
            // Initial render against whatever the controller currently has.
            render(controller.currentViewModel)
        } else {
            stage.requestFocus()
        }
    }

    /**
     * Apply the view-model to the window. Visible-only; never blocks.
     * Compares the view-model's generation to the last-applied one and
     * to the controller's current generation so a stale publish can't
     * reach the scene graph (matches the [RaBootPlacard] pattern).
     */
    private fun render(viewModel: RaAchievementsWindowViewModel) {
        if (viewModel.generation != controller.generation) return
        if (viewModel.generation == lastAppliedGeneration) return
        lastAppliedGeneration = viewModel.generation
        when (viewModel) {
            is RaAchievementsWindowViewModel.Unavailable -> renderPlaceholder(
                "RetroAchievements is not available.\n\n${viewModel.reason}",
                showList = false,
                showHeader = false,
            )
            is RaAchievementsWindowViewModel.SignedOut -> renderPlaceholder(
                "Sign in to RetroAchievements to view achievements for the loaded game.\n\n" +
                    "Use RetroAchievements → Sign In… in the menu bar.",
                showList = false,
                showHeader = false,
            )
            is RaAchievementsWindowViewModel.Offline -> renderPlaceholder(
                "RetroAchievements is temporarily offline — achievements will refresh when the connection is restored.\n\n" +
                    "Cause: ${viewModel.cause}",
                showList = false,
                showHeader = false,
            )
            is RaAchievementsWindowViewModel.NoRom -> renderPlaceholder(
                "Load a game to view its achievements.",
                showList = false,
                showHeader = false,
            )
            is RaAchievementsWindowViewModel.Unrecognized -> renderPlaceholder(
                "${viewModel.displayName} is not recognized on RetroAchievements.\n\n" +
                    "This is typical for ROM hacks, translations, or alternate dumps. " +
                    "The game plays normally — achievements simply aren't available.",
                showList = false,
                showHeader = false,
            )
            is RaAchievementsWindowViewModel.NoCoreAchievements -> {
                renderHeader(
                    gameTitle = viewModel.gameTitle,
                    gameImageUrl = viewModel.gameImageUrl,
                    unlocked = 0,
                    total = 0,
                    points = 0,
                    maxPoints = 0,
                )
                renderPlaceholder(
                    "${viewModel.gameTitle} is recognized but has no core achievements.",
                    showList = false,
                    showHeader = true,
                )
            }
            is RaAchievementsWindowViewModel.Recognized -> {
                renderHeaderFromSnapshot(viewModel.snapshot)
                renderListFromSnapshot(viewModel.snapshot)
            }
        }
    }

    private fun renderHeaderFromSnapshot(snapshot: com.github.alondero.nestlin.session.RaAchievementListSnapshot) {
        renderHeader(
            gameTitle = snapshot.gameTitle,
            gameImageUrl = snapshot.gameImageUrl,
            unlocked = snapshot.unlockedCoreAchievements,
            total = snapshot.totalCoreAchievements,
            points = snapshot.unlockedCorePoints,
            maxPoints = snapshot.totalCorePoints,
        )
    }

    private fun renderHeader(
        gameTitle: String,
        gameImageUrl: String,
        unlocked: Int,
        total: Int,
        points: Int,
        maxPoints: Int,
    ) {
        headerTitleLabel.text = gameTitle
        headerCountsLabel.text = "Unlocked $unlocked of $total achievements"
        headerPointsLabel.text = "Earned $points of $maxPoints points"
        val ratio = if (total <= 0) 0.0 else unlocked.toDouble() / total.toDouble()
        headerProgressBar.progress = ratio
        // Badge — async fetch through the same cache the placard uses.
        if (gameImageUrl.isNotBlank()) {
            val future = imageCache.fetch(gameImageUrl)
            future.whenComplete { image, _ ->
                Platform.runLater {
                    if (image != null) headerBadge.image = bufferedImageToFxImage(image)
                }
            }
        } else {
            headerBadge.image = null
        }
    }

    private fun renderListFromSnapshot(snapshot: com.github.alondero.nestlin.session.RaAchievementListSnapshot) {
        // Only swap the items list — the cell factory is set once in
        // init() so JavaFX can recycle cells across refreshes.
        listView.items = javafx.collections.FXCollections.observableArrayList(flatten(snapshot))
        listView.isVisible = true
        listView.isManaged = true
        placeholderContainer.isVisible = false
        placeholderContainer.isManaged = false
    }

    private fun renderPlaceholder(message: String, showList: Boolean, showHeader: Boolean) {
        placeholderLabel.text = message
        listView.isVisible = showList
        listView.isManaged = showList
        placeholderContainer.isVisible = true
        placeholderContainer.isManaged = true
        // Clear the header fully when showHeader is false so a
        // Recognized → Unrecognized transition doesn't leave the
        // progress bar showing the old ratio above an empty header.
        if (!showHeader) {
            headerTitleLabel.text = ""
            headerCountsLabel.text = ""
            headerPointsLabel.text = ""
            headerBadge.image = null
            headerProgressBar.progress = 0.0
        }
    }

    /**
     * Tear down the listener so a late view-model doesn't reach a closed
     * window. Idempotent — safe to call before [show].
     */
    fun dispose() {
        listenerToken?.let { controller.removeListener(it) }
        listenerToken = null
        if (stage.isShowing) stage.close()
    }

    /**
     * Cell that renders a single [Row]. Header rows render as a labelled
     * section title; achievement rows render as a virtualized achievement
     * card (badge + title + description + points + measured progress).
     *
     * The cell re-uses its child nodes across [updateItem] calls so the
     * ListView's virtualization doesn't allocate a fresh set per visible
     * row on every refresh.
     */
    private class AchievementRowCell(
        private val imageCache: RaImageCache,
    ) : javafx.scene.control.ListCell<Row>() {

        private val headerLabel: Label = Label().apply {
            font = Font.font("System", FontWeight.BOLD, 13.0)
            textFill = Color.DARKSLATEGRAY
            padding = Insets(8.0, 0.0, 4.0, 0.0)
        }
        private val badge: ImageView = ImageView().apply {
            fitWidth = 32.0
            fitHeight = 32.0
            isPreserveRatio = true
            isSmooth = false
        }
        private val titleLabel: Label = Label().apply {
            font = Font.font("System", FontWeight.BOLD, 12.0)
        }
        private val descriptionLabel: Label = Label().apply {
            font = Font.font("System", 11.0)
            textFill = Color.DARKGRAY
            isWrapText = true
            maxWidth = Double.MAX_VALUE
        }
        private val pointsLabel: Label = Label().apply {
            font = Font.font("System", FontWeight.BOLD, 11.0)
            textFill = Color.DARKSLATEGRAY
        }
        private val progressLabel: Label = Label().apply {
            font = Font.font("System", 11.0)
            textFill = Color.DARKGRAY
        }
        private val lockLabel: Label = Label().apply {
            font = Font.font("System", FontWeight.BOLD, 11.0)
            textFill = Color.GRAY
        }
        private val row: HBox = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 4.0, 4.0, 4.0)
        }
        private val textColumn: VBox = VBox(2.0).apply {
            children.addAll(titleLabel, descriptionLabel)
        }
        private val rightColumn: VBox = VBox(2.0).apply {
            alignment = Pos.TOP_RIGHT
            children.addAll(pointsLabel, progressLabel, lockLabel)
        }

        @Volatile private var lastBadgeUrl: String? = null

        init {
            row.children.add(badge)
            row.children.add(textColumn.apply { HBox.setHgrow(this, Priority.ALWAYS) })
            row.children.add(rightColumn)
        }

        override fun updateItem(item: Row?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                graphic = null
                text = null
                return
            }
            when (item) {
                is Row.Header -> {
                    text = "${item.bucketLabel} (${item.count})"
                    graphic = null
                    // ListCell text styling is cheap; reset badge so a
                    // recycled cell doesn't briefly show the wrong one.
                    badge.image = null
                }
                is Row.Achievement -> {
                    val a = item.achievement
                    titleLabel.text = a.title
                    descriptionLabel.text = a.description
                    pointsLabel.text = "${a.points} pts"
                    text = null
                    graphic = row
                    if (a.isUnlocked) {
                        progressLabel.text = a.measuredProgress
                        lockLabel.text = ""
                    } else {
                        progressLabel.text = a.measuredProgress
                        lockLabel.text = if (a.measuredProgress.isBlank()) "Locked" else "Locked — ${a.measuredProgress}"
                    }
                    // Fetch the badge async. The cell re-uses the same
                    // URL key so the cache dedups across cells.
                    val url = if (a.isUnlocked) a.badgeUrlUnlocked else a.badgeUrlLocked.ifBlank { a.badgeUrlUnlocked }
                    if (url.isBlank()) {
                        badge.image = null
                    } else if (url != lastBadgeUrl) {
                        lastBadgeUrl = url
                        badge.image = null
                        imageCache.fetch(url).whenComplete { image, _ ->
                            Platform.runLater {
                                // The cell may have been recycled to a
                                // different achievement whose URL matches;
                                // re-check before applying so a slow
                                // completion for row N doesn't overwrite
                                // row M's badge.
                                if (lastBadgeUrl == url && image != null) {
                                    badge.image = bufferedImageToFxImage(image)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Convert a [BufferedImage] to a JavaFX [Image]. Marshalled through
 * a PNG [ByteArrayInputStream] so the conversion doesn't depend on
 * JavaFX's internal pixel format matching the BufferedImage's. Same
 * pattern as the boot placard / profile window.
 */
private fun bufferedImageToFxImage(buf: BufferedImage): Image {
    val baos = ByteArrayOutputStream()
    ImageIO.write(buf, "PNG", baos)
    return Image(ByteArrayInputStream(baos.toByteArray()))
}

