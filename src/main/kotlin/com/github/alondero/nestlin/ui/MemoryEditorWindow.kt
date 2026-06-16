package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.toUnsignedInt
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.util.Duration

/**
 * The Memory Editor's live hex viewer — the tracer bullet for issue #168.
 *
 * A standalone floating [Stage] showing the entire CPU bus (`$0000-$FFFF`) as a
 * scrollable hex grid: one row per 16 bytes, 4096 rows. A 10 Hz [Timeline]
 * (ADR-0001) repaints the grid so values update live while the game runs.
 *
 * **Why a `ListView<Int>` of row indices.** JavaFX virtualises [ListView]: only
 * the ~30-40 rows currently scrolled into view have live cells. The cell factory
 * [peek]s memory directly inside `updateItem`, so [ListView.refresh] — which only
 * re-runs `updateItem` on the *rendered* cells — naturally peeks just the visible
 * rows per tick, never the full 64 KB. Scrolling to a new region costs nothing
 * extra: the newly-visible cells peek their own bytes on render.
 *
 * This tracer is read-only (no editing, no change highlighting yet — those land
 * with `poke` in a later slice of the parent #167).
 */
class MemoryEditorWindow(private val peek: (Int) -> Byte) {

    val stage = Stage()

    // Instance-level (not companion) so the pure [formatRow] in the companion can
    // be unit-tested without booting the JavaFX toolkit a Font would require.
    private val monoFont: Font = Font.font("Monospaced", 13.0)

    private val listView = ListView<Int>().apply {
        // One item per 16-byte row: row index 0..4095 → base address index*16.
        items.setAll((0 until ROWS).toList())
        isFocusTraversable = true
        cellFactory = javafx.util.Callback {
            object : ListCell<Int>() {
                init {
                    font = monoFont
                }
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else formatRow(item, peek)
                }
            }
        }
    }

    // 10 Hz repaint (ADR-0001). refresh() re-runs the cell factory on the visible
    // cells only, so each tick peeks ~30-40 rows, not all 4096.
    private val refreshTimer = Timeline(
        KeyFrame(Duration.millis(REFRESH_INTERVAL_MS), EventHandler { listView.refresh() })
    ).apply { cycleCount = Animation.INDEFINITE }

    init {
        stage.title = "Memory Editor"
        // Float independently of the game canvas — not modal, not owned, so it can
        // sit on a second monitor and never affects the game window's aspect ratio.
        stage.scene = Scene(listView, 480.0, 600.0)
        // Run the timer only while the window is actually showing, so a closed
        // editor costs nothing.
        stage.onShown = EventHandler { refreshTimer.play() }
        stage.onHidden = EventHandler { refreshTimer.stop() }
    }

    /** Show the window, or bring it to the front if it's already open. */
    fun show() {
        if (stage.isShowing) stage.toFront() else stage.show()
    }

    companion object {
        /** 16 bytes per row × 4096 rows = the full 64 KB CPU bus. */
        const val ROWS = 0x10000 / 16

        /** 10 Hz refresh per ADR-0001. */
        const val REFRESH_INTERVAL_MS = 100.0

        /**
         * Render one 16-byte row as `"ADDR  B0 B1 ... B15"` (address + 16 hex bytes).
         * Pure function of [rowIndex] and the [peek] provider — no JavaFX, so it's
         * unit-testable on its own. [rowIndex] is 0..[ROWS]-1; the row covers CPU
         * addresses `rowIndex*16 .. rowIndex*16+15`.
         */
        fun formatRow(rowIndex: Int, peek: (Int) -> Byte): String {
            val base = rowIndex * 16
            val sb = StringBuilder()
            sb.append(String.format("%04X", base)).append("  ")
            for (i in 0 until 16) {
                sb.append(String.format("%02X", peek(base + i).toUnsignedInt()))
                if (i != 15) sb.append(' ')
            }
            return sb.toString()
        }
    }
}
