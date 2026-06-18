package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.toUnsignedInt
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import java.util.TreeSet

/**
 * The four colour-banded regions of the NES CPU bus (issue #171). Each row in
 * the Memory Editor's address gutter is shaded with the colour of the region
 * it falls in, so a player can orient themselves ("is that address RAM or a
 * mapper register?") without memorising the address boundaries.
 *
 * The range split mirrors the iNES / NESdev consensus:
 *  - [RAM]:     `$0000-$1FFF` (2KB mirrored)
 *  - [PPU]:     `$2000-$3FFF` (8-byte register file mirrored)
 *  - [APU_IO]:  `$4000-$401F` (APU + controller + DMA)
 *  - [CART]:    `$4020-$FFFF` (cartridge space, expansion, PRG-ROM)
 */
enum class MemoryRegion { RAM, PPU, APU_IO, CART }

/**
 * The Memory Editor's live hex viewer — the tracer bullet for issue #168,
 * extended with change highlighting for issue #169.
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
 * **Change highlighting (issue #169).** Each 10 Hz tick, [onRefreshTick] scopes
 * its peek + diff to the addresses currently visible (queried via the live
 * `ListCell`s the JavaFX skin has rendered). The diff result is captured as a
 * [MemorySnapshot]; the per-cell fade age is tracked in two [HashMap]s keyed by
 * CPU address so cells that scroll out naturally decay without holding a
 * 64 KB-state. The cell factory's [renderRow] consults those maps to colour
 * each byte green (UP) or blue (DOWN) with an alpha that scales linearly with
 * the remaining fade ticks. 5 ticks at 10 Hz = the 500 ms fade window from
 * ADR-0001.
 *
 * **Forced flash (ROM load / reset).** [markAllChanged] sets a one-shot flag
 * the next tick consumes to push every visible cell to fresh UP state. The
 * `previousVisibleBytes` is also dropped so the diff restarts from a clean
 * baseline — without that, cells that happened to share the same value across
 * the reset would never flash.
 *
 * **Click-to-edit (issue #170).** Clicking a byte cell selects it (drawn with a
 * border); typing two hex digits overwrites the byte via [poke] (the game sees
 * it on its next read); arrow keys move the selection. All the editing *rules*
 * live in the pure [HexEditState] — this window only translates JavaFX mouse /
 * key events into calls on it and renders its state. A completed edit's natural
 * change-highlight (the next tick's diff flashes the poked cell green/blue) is
 * the visual confirmation, so no special post-poke flash is needed.
 *
 * **Region bands / go-to / ASCII (issue #171).** The address gutter is wrapped
 * in a background-coloured [StackPane] tinted by the row's [MemoryRegion]
 * (RAM / PPU / APU_IO / CART) so the user can see the memory-map split at a
 * glance. A [TextField] above the grid accepts a hex address (`$2000` or
 * `2000`) and scrolls the [ListView] directly to that row, also selecting the
 * cell so the next hex keystroke pokes it. Each row's right edge carries a
 * 16-character ASCII decode — printable bytes 0x20-0x7E render as themselves,
 * everything else as `.` — so a player can spot embedded text strings.
 */
class MemoryEditorWindow(
    private val peek: (Int) -> Byte,
    private val poke: (Int, Byte) -> Unit,
) {

    // The pure selection / pending-nibble / navigation state machine (issue #170).
    // Mutated only on the JavaFX thread (mouse + key handlers); the cell factory
    // reads it to render the selection border and any partial edit.
    private val editState = HexEditState()

    val stage = Stage()

    // Instance-level (not companion) so the pure [formatRow] in the companion can
    // be unit-tested without booting the JavaFX toolkit a Font would require.
    private val monoFont: Font = Font.font("Monospaced", 13.0)

    /**
     * The "Go to address" input field (issue #171). The user types a hex
     * address (`$2000`, `2000`) and either presses Enter or clicks away
     * (focus loss); either way the [ListView] scrolls to that row and the
     * byte cell is selected so the next hex keystroke pokes it. Empty /
     * unparseable input is silently ignored — the field never blocks the
     * user from typing again, and the [ListView] focus is returned to the
     * grid on a successful go-to.
     */
    private val goToAddressField = TextField().apply {
        promptText = "Go to address (e.g. \$2000)"
        prefWidth = SCENE_WIDTH
        // A small left padding so the prompt text doesn't hug the border.
        padding = Insets(4.0, 6.0, 4.0, 6.0)
        // Two commit paths. setOnAction fires on Enter (the primary
        // "I'm done typing" gesture); the focusedProperty listener fires
        // when focus leaves the field (covers click-out, Tab-out, and
        // clicking into the grid) so a user who types `$2000` and then
        // clicks a cell still gets a navigation. Without the second
        // listener, the typed text would silently sit in the field.
        setOnAction { commitGoToAddress() }
        focusedProperty().addListener { _, _, hasFocus ->
            if (!hasFocus) commitGoToAddress()
        }
    }

    private val listView = ListView<Int>().apply {
        // One item per 16-byte row: row index 0..4095 → base address index*16.
        items.setAll((0 until ROWS).toList())
        isFocusTraversable = true
        cellFactory = javafx.util.Callback {
            object : ListCell<Int>() {
                init {
                    font = monoFont
                    // Force the cell to be wide enough for 16 bytes + address +
                    // spaces. Without this, the ListView can auto-size the cell
                    // to a narrower value (matching just the visible bytes), and
                    // even though [renderRow] uses an [HBox] (which doesn't wrap),
                    // a too-narrow cell would clip the rightmost bytes. The cell
                    // width is coupled to [SCENE_WIDTH] — if you change one,
                    // change the other. Issue #171 added the ASCII column to the
                    // right, so both values were bumped from 480.0 to 620.0.
                    minWidth = SCENE_WIDTH
                }
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                    } else {
                        // Cells render via the [graphic] (HBox of per-byte Text
                        // / StackPane nodes — see [renderRow]). [text] is nulled
                        // so the cell doesn't also try to draw a string behind
                        // the graphic. The font is passed in so the [Text]
                        // children inside the HBox use the monospaced font —
                        // they don't inherit the cell's font automatically.
                        text = null
                        graphic = renderRow(
                            item, peek, ::currentDirection, ::currentAge, monoFont,
                            editState.selectedAddress, editState.pendingNibble, ::onByteClicked,
                        )
                    }
                }
            }
        }
        // Arrow keys would otherwise scroll the ListView's own row selection; we
        // want them to move the *cell* selection instead. A KEY_PRESSED filter on
        // the ListView intercepts hex digits and arrows before the skin sees them.
        addEventFilter(KeyEvent.KEY_PRESSED) { event -> handleKeyPressed(event) }
    }

    // 10 Hz repaint (ADR-0001). [onRefreshTick] peeks + diffs the visible range
    // before triggering listView.refresh(); the cell factory then peeks again
    // inside updateItem to render the row text. Both peeks are side-effect-free
    // (issue #168's contract) so the duplication is harmless.
    private val refreshTimer = Timeline(
        KeyFrame(Duration.millis(REFRESH_INTERVAL_MS), EventHandler { onRefreshTick() })
    ).apply { cycleCount = Animation.INDEFINITE }

    // ---- Change-highlight state (issue #169) --------------------------------
    // [previousVisibleBytes] is the bytes we peeked at the previous tick, in
    // the same address order as [visibleAddresses]. Together they form the
    // "before" half of the diff. Null on the first tick (no baseline yet) and
    // after a forced flash via [markAllChanged].
    private var previousVisibleBytes: ByteArray? = null
    private var visibleAddresses: IntArray = IntArray(0)

    // Sparse per-address fade state. Only contains entries for cells that
    // have ever been highlighted since they last scrolled into view — every
    // other cell implicitly has age=0 and direction=UNCHANGED. Sparse maps
    // scale with the visible range, not the full 64 KB.
    private val highlightDirection = HashMap<Int, Direction>()
    private val highlightAge = HashMap<Int, Int>()

    // Set by [markAllChanged], consumed once on the next tick. The flag
    // (rather than a direct mutation) lets the call site be the JavaFX
    // thread or the emulation thread — both just set a boolean and let the
    // timer handle the rest.
    private var forceFullFlash: Boolean = false

    init {
        stage.title = "Memory Editor"
        // Float independently of the game canvas — not modal, not owned, so it can
        // sit on a second monitor and never affects the game window's aspect ratio.
        // Width matches [SCENE_WIDTH] which is also the cell's minWidth — see the
        // note on the cellFactory for why these two must stay in sync.
        //
        // The scene root is a VBox (issue #171) so the go-to-address TextField
        // can sit on top of the grid. VBox.growVertical is left at default for
        // the field (it sizes to its preferred height — ~28px for a 13pt
        // monospaced prompt); the listView gets `VBox.setVgrow(ALWAYS)` so it
        // claims the rest of the scene's [SCENE_HEIGHT].
        val root = VBox(goToAddressField, listView).apply {
            VBox.setVgrow(listView, Priority.ALWAYS)
        }
        stage.scene = Scene(root, SCENE_WIDTH, SCENE_HEIGHT)
        // Run the timer only while the window is actually showing, so a closed
        // editor costs nothing.
        stage.onShown = EventHandler { refreshTimer.play() }
        stage.onHidden = EventHandler { refreshTimer.stop() }
    }

    /** Show the window, or bring it to the front if it's already open. */
    fun show() {
        if (stage.isShowing) stage.toFront() else stage.show()
    }

    /**
     * Force every currently-visible cell to flash on the next tick (issue #169
     * acceptance criterion: "A ROM load or game reset causes a brief full-grid
     * highlight flash"). The previous-bytes baseline is dropped so the next
     * diff starts fresh — without this, a cell that happened to share its
     * value across the reset would never flash because the diff would
     * consider it unchanged.
     */
    fun markAllChanged() {
        forceFullFlash = true
        previousVisibleBytes = null
    }

    /**
     * The 10 Hz tick (ADR-0001). Three phases:
     *
     *  1. **Peek visible range.** [computeVisibleAddresses] walks the live
     *     `ListCell`s the JavaFX skin has rendered and produces a sorted
     *     `IntArray` of CPU addresses. We peek each into [newVisibleBytes].
     *  2. **Diff.** If the baseline ([previousVisibleBytes]) is the same length
     *     as the current peek, [MemorySnapshot.diff] produces per-cell
     *     directions; otherwise the snapshot is all-UNCHANGED (first tick, or
     *     the user scrolled to a different region).
     *  3. **Update fade state.** For each visible cell: if the diff saw a
     *     change, reset age to [FADE_TICKS] and store the new direction;
     *     otherwise decay the age and drop the entry at zero. For cells that
     *     scrolled OUT of view, also decay — so a cell that was UP last time
     *     we saw it doesn't keep glowing forever if the user scrolls away
     *     and never comes back.
     *
     * Finally, [previousVisibleBytes] / [visibleAddresses] are replaced and
     * [listView.refresh] re-runs `updateItem` on the visible cells, which
     * consults the updated maps via [currentDirection] / [currentAge].
     */
    private fun onRefreshTick() {
        val visibleAddrs = computeVisibleAddresses()
        val newVisibleBytes = ByteArray(visibleAddrs.size)
        for ((i, addr) in visibleAddrs.withIndex()) {
            newVisibleBytes[i] = peek(addr)
        }

        // Phase 2: build a snapshot from the diff. First tick (or after a
        // forced flash, which clears [previousVisibleBytes]) yields an
        // all-UNCHANGED snapshot — no highlight until something actually
        // changes.
        val snapshot = if (
            previousVisibleBytes != null &&
            previousVisibleBytes!!.size == newVisibleBytes.size
        ) {
            MemorySnapshot.diff(previousVisibleBytes!!, newVisibleBytes)
        } else {
            MemorySnapshot(newVisibleBytes, Array(newVisibleBytes.size) { Direction.UNCHANGED })
        }

        // Forced flash (ROM load / reset) goes FIRST so the per-cell
        // decay/reset pass below doesn't immediately overwrite the fresh
        // UP state. We track the set of addresses we just forced so the
        // phase-3 pass can skip them — otherwise, because the cleared
        // baseline makes every diff result UNCHANGED, the decay branch
        // would drop the freshly-set age from FADE_TICKS to FADE_TICKS-1
        // on the very tick the user is supposed to see the flash peak.
        val justForced: Set<Int> = if (forceFullFlash) {
            forceFullFlash = false
            for (addr in visibleAddrs) {
                highlightDirection[addr] = Direction.UP
                highlightAge[addr] = FADE_TICKS
            }
            // Reuse the same set membership for the phase-3 skip check.
            // visibleAddrs is already sorted; wrap in a small wrapper
            // for O(1) `in` lookup. The HashSet is discarded at the end
            // of the tick.
            visibleAddrs.toHashSet()
        } else {
            emptySet()
        }

        // Phase 3: per-cell fade update for the visible range.
        for ((i, addr) in visibleAddrs.withIndex()) {
            // Cells that were just forced-flashed keep their FADE_TICKS
            // age and don't get touched by the diff-driven update.
            if (addr in justForced) continue
            val direction = snapshot.directions[i]
            if (direction == Direction.UNCHANGED) {
                val age = highlightAge[addr] ?: 0
                if (age > 0) {
                    val newAge = age - 1
                    if (newAge == 0) {
                        highlightAge.remove(addr)
                        highlightDirection.remove(addr)
                    } else {
                        highlightAge[addr] = newAge
                    }
                }
            } else {
                highlightDirection[addr] = direction
                highlightAge[addr] = FADE_TICKS
            }
        }

        // Decay age for cells that scrolled out of view, so a long scroll
        // away from an UP cell doesn't leave it glowing if the user ever
        // scrolls back. Cheap: at most a few dozen entries after the first
        // tick.
        val visibleSet = visibleAddrs.toSet()
        val toDrop = ArrayList<Int>()
        for (addr in highlightAge.keys) {
            if (addr !in visibleSet) {
                val age = highlightAge[addr] ?: 0
                if (age > 0) {
                    val newAge = age - 1
                    if (newAge == 0) {
                        toDrop.add(addr)
                    } else {
                        highlightAge[addr] = newAge
                    }
                } else {
                    toDrop.add(addr)
                }
            }
        }
        for (addr in toDrop) {
            highlightAge.remove(addr)
            highlightDirection.remove(addr)
        }

        // Phase 4: store new baseline and repaint.
        previousVisibleBytes = newVisibleBytes
        visibleAddresses = visibleAddrs
        listView.refresh()
    }

    /**
     * The set of CPU addresses currently visible. Implemented by walking the
     * live `ListCell`s the JavaFX skin has rendered (JavaFX virtualises the
     * `ListView` so only on-screen cells are created); the cell's `index`
     * property is the row index in [items], which gives the row's base
     * address. Sorted so the diff and the snapshot share a stable order —
     * important because the diff is element-wise and a different ordering
     * would yield nonsense.
     */
    private fun computeVisibleAddresses(): IntArray {
        val rows = TreeSet<Int>()
        listView.lookupAll(".list-cell").forEach { node ->
            if (node is ListCell<*>) {
                val idx = node.index
                if (idx >= 0 && idx < ROWS) rows.add(idx)
            }
        }
        val sortedRows = rows.toIntArray()
        val addrs = IntArray(sortedRows.size * 16)
        for ((i, row) in sortedRows.withIndex()) {
            val base = row * 16
            for (j in 0 until 16) {
                addrs[i * 16 + j] = base + j
            }
        }
        return addrs
    }

    /** Look up the most recently observed direction at [addr]. Default UNCHANGED. */
    private fun currentDirection(addr: Int): Direction =
        highlightDirection[addr] ?: Direction.UNCHANGED

    /** Look up the fade age (in ticks) at [addr]. Default 0 (no highlight). */
    private fun currentAge(addr: Int): Int =
        highlightAge[addr] ?: 0

    // ---- Click-to-edit input handling (issue #170) --------------------------

    /**
     * A byte cell was clicked: select it and repaint so the border appears
     * immediately (rather than waiting up to 100 ms for the next refresh tick).
     * Also pull keyboard focus to the ListView so the subsequent hex/arrow keys
     * are delivered to [handleKeyPressed].
     */
    private fun onByteClicked(addr: Int) {
        editState.select(addr)
        listView.requestFocus()
        listView.refresh()
    }

    /**
     * Translate a key press into an edit action on [editState]:
     *  - a hex digit (0-9 / A-F, either case) feeds [HexEditState.typeHexDigit];
     *    a completed two-nibble byte is applied via [applyPoke];
     *  - an arrow key moves the selection one cell (consumed so the ListView's
     *    own row navigation doesn't also fire);
     *  - Escape clears the selection.
     *
     * Anything else is left for the ListView (e.g. Page Up/Down scrolling). The
     * handler is a no-op when nothing is selected, so the editor still scrolls
     * normally before the user has clicked a cell.
     */
    private fun handleKeyPressed(event: KeyEvent) {
        val nibble = hexDigit(event.code)
        when {
            // Only act on (and consume) a hex digit when a cell is actually
            // selected — otherwise leave the key for the ListView so we don't
            // silently swallow keystrokes the editor isn't going to use.
            nibble != null && editState.selectedAddress != null -> {
                val pokeRequest = editState.typeHexDigit(nibble)
                if (pokeRequest != null) applyPoke(pokeRequest)
                listView.refresh()
                event.consume()
            }
            event.code == KeyCode.LEFT -> moveSelection(dCol = -1, dRow = 0, event)
            event.code == KeyCode.RIGHT -> moveSelection(dCol = 1, dRow = 0, event)
            event.code == KeyCode.UP -> moveSelection(dCol = 0, dRow = -1, event)
            event.code == KeyCode.DOWN -> moveSelection(dCol = 0, dRow = 1, event)
            event.code == KeyCode.ESCAPE && editState.selectedAddress != null -> {
                editState.clearSelection()
                listView.refresh()
                event.consume()
            }
        }
    }

    /** Move the cell selection, scroll it into view, repaint. Consumes the event. */
    private fun moveSelection(dCol: Int, dRow: Int, event: KeyEvent) {
        if (editState.selectedAddress == null) return // let the ListView scroll
        editState.move(dCol, dRow)
        // Only scroll when the target row is off-screen — scrolling on every
        // arrow press (e.g. moving LEFT/RIGHT within a visible row) would jolt
        // the viewport unnecessarily.
        editState.selectedAddress?.let {
            val row = it / 16
            if (!isRowVisible(row)) listView.scrollTo(row)
        }
        listView.refresh()
        event.consume()
    }

    /**
     * Commit the current contents of [goToAddressField] (issue #171). Parses
     * the input via the pure [parseAddress] helper:
     *  - on success: scroll the [ListView] to the row, select the byte cell
     *    at that address (so the next hex keystroke can poke it), clear the
     *    field, and return keyboard focus to the grid for continuous
     *    editing;
     *  - on parse failure / out-of-range: do nothing. The field keeps the
     *    user's text so they can correct the typo, and we don't flag it red
     *    — a transient empty value is normal (focus events fire on every
     *    keystroke) and the editor must never block the user.
     */
    private fun commitGoToAddress() {
        val addr = parseAddress(goToAddressField.text) ?: return
        // Compute the row that *contains* the address, not addr / 16 alone —
        // rows are 16-byte aligned so the two are equivalent, but the named
        // constant makes the intent obvious in a future read.
        val row = addr / 16
        // scrollTo picks the closest visible position; clamp to the legal
        // range so an input that mapped to the very last row doesn't try to
        // scroll past the end.
        val clampedRow = row.coerceIn(0, ROWS - 1)
        listView.scrollTo(clampedRow)
        // Selecting the cell draws the amber selection ring (issue #170) so
        // the user can see *where* the editor landed. The next hex digit
        // they type will poke this address.
        editState.select(addr)
        listView.requestFocus()
        goToAddressField.clear()
        listView.refresh()
    }

    /** True if [row] currently has a live (on-screen) ListCell. */
    private fun isRowVisible(row: Int): Boolean =
        listView.lookupAll(".list-cell").any { it is ListCell<*> && it.index == row }

    /**
     * Apply a completed edit through the [poke] callback (which routes to
     * `Memory.poke` and its I/O blacklist). The poked cell isn't flashed here —
     * the next refresh tick's diff sees the new value and highlights it
     * green/blue naturally, which is the user's confirmation the write landed.
     */
    private fun applyPoke(request: HexEditState.Poke) {
        poke(request.address, request.value.toByte())
    }

    companion object {

        /**
         * Map a [KeyCode] to its hex nibble (0..15), or null if it isn't a hex
         * digit. Covers the number row, the numpad (DIGIT/NUMPAD variants), and
         * A-F. Case is irrelevant — [KeyCode] is the physical key, not the typed
         * character.
         */
        fun hexDigit(code: KeyCode): Int? = when (code) {
            KeyCode.DIGIT0, KeyCode.NUMPAD0 -> 0
            KeyCode.DIGIT1, KeyCode.NUMPAD1 -> 1
            KeyCode.DIGIT2, KeyCode.NUMPAD2 -> 2
            KeyCode.DIGIT3, KeyCode.NUMPAD3 -> 3
            KeyCode.DIGIT4, KeyCode.NUMPAD4 -> 4
            KeyCode.DIGIT5, KeyCode.NUMPAD5 -> 5
            KeyCode.DIGIT6, KeyCode.NUMPAD6 -> 6
            KeyCode.DIGIT7, KeyCode.NUMPAD7 -> 7
            KeyCode.DIGIT8, KeyCode.NUMPAD8 -> 8
            KeyCode.DIGIT9, KeyCode.NUMPAD9 -> 9
            KeyCode.A -> 0xA
            KeyCode.B -> 0xB
            KeyCode.C -> 0xC
            KeyCode.D -> 0xD
            KeyCode.E -> 0xE
            KeyCode.F -> 0xF
            else -> null
        }

        /** 16 bytes per row × 4096 rows = the full 64 KB CPU bus. */
        const val ROWS = 0x10000 / 16

        /** 10 Hz refresh per ADR-0001. */
        const val REFRESH_INTERVAL_MS = 100.0

        /**
         * Number of refresh ticks a highlight stays visible. 5 × 100 ms = 500 ms,
         * matching the "fades over ~500 ms" requirement from issue #169. The cell
         * factory scales the alpha linearly from full at [FADE_TICKS] to zero
         * at 0; one tick of `0` means the cell renders at the default background.
         */
        const val FADE_TICKS = 5

        /**
         * Default Stage / Scene width in pixels. **Must match the cell's
         * `minWidth` in the cellFactory** — if you change one, change the other.
         * The cell needs to be at least this wide so the 16-byte HBox never
         * overflows; the Scene needs to be exactly this wide so the cell
         * actually fills the column instead of being padded by empty space.
         *
         * Bumped from 480.0 to 620.0 in issue #171 to fit the new ASCII
         * decode column (16 extra characters on the right). The new layout
         * is "4-char address + space + 16 hex bytes (47 chars) + space + 16
         * ASCII chars = 70 chars"; at 13pt monospaced (~7.8px/char) that's
         * ~546px of content, plus the StackPane padding for the gutter and
         * byte cells we round up to 620 for headroom.
         */
        const val SCENE_WIDTH = 620.0

        /** Default Stage / Scene height in pixels. The VBox root has a 28px
         *  go-to-address [TextField] on top (issue #171) plus a 4px gap, so
         *  the listView gets ~568px. That still shows ~30 rows — enough that
         *  the user sees the region bands and the selected cell at a glance
         *  without scrolling.
         */
        const val SCENE_HEIGHT = 600.0

        /**
         * Classify a CPU address into its NES memory region. Pure function
         * (issue #171) — no side effects, safe to call from the cell factory
         * for every rendered row.
         *
         * Boundaries are inclusive at the low end and exclusive at the high
         * end, matching the actual hardware decode:
         *  - `0x0000 .. 0x1FFF` → [MemoryRegion.RAM] (2KB mirrored)
         *  - `0x2000 .. 0x3FFF` → [MemoryRegion.PPU] (8-byte register file mirrored)
         *  - `0x4000 .. 0x401F` → [MemoryRegion.APU_IO]
         *  - `0x4020 .. 0xFFFF` → [MemoryRegion.CART]
         *
         * Negative inputs are treated as [MemoryRegion.CART] (the largest
         * region) so a typo or future regression in row math never silently
         * drops into a smaller region. The editor never passes anything
         * outside 0..0xFFFF, but the defensive default is cheap.
         */
        fun regionForAddress(address: Int): MemoryRegion = when {
            address < 0 -> MemoryRegion.CART
            address < 0x2000 -> MemoryRegion.RAM
            address < 0x4000 -> MemoryRegion.PPU
            address < 0x4020 -> MemoryRegion.APU_IO
            else -> MemoryRegion.CART
        }

        /**
         * The pastel RGB triple used to tint the address gutter for a given
         * [MemoryRegion] (issue #171). The colours are deliberately light
         * so the black hex digits of the address stay readable through the
         * tint, and deliberately distinct from the green / blue change
         * highlights (issue #169) and the amber selection ring (issue #170)
         * — a region gutter and a fading byte must never look the same.
         */
        fun regionColor(region: MemoryRegion): Triple<Int, Int, Int> = when (region) {
            MemoryRegion.RAM -> RAM_GUTTER_COLOR
            MemoryRegion.PPU -> PPU_GUTTER_COLOR
            MemoryRegion.APU_IO -> APU_GUTTER_COLOR
            MemoryRegion.CART -> CART_GUTTER_COLOR
        }

        /**
         * Decode one byte of a row as ASCII for the right-hand column
         * (issue #171). Printable bytes (0x20..0x7E inclusive — the standard
         * ASCII printable range) render as themselves; everything else
         * renders as '.'. The output length always matches the input length,
         * so a 16-byte row produces a 16-character ASCII column.
         */
        fun formatAsciiColumn(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size)
            for (b in bytes) {
                val unsigned = b.toUnsignedInt()
                sb.append(if (unsigned in 0x20..0x7E) unsigned.toChar() else '.')
            }
            return sb.toString()
        }

        /**
         * Parse a hex address string from the Go-to-address field (issue #171).
         * Returns the parsed value (0..0xFFFF) on success, or null on any
         * failure mode so the caller can treat the input as a no-op rather
         * than crashing the editor.
         *
         * Accepted forms:
         *  - plain hex digits: `"2000"`, `"00A5"`, `"ffff"` (case-insensitive)
         *  - leading `$` (FCEUX / Mesen style): `"$2000"`, `"$00A5"`
         *  - surrounding whitespace: trimmed before parsing
         *
         * Rejected forms (return null):
         *  - empty / whitespace-only
         *  - non-hex characters anywhere (including `0x` prefix or trailing `h`)
         *  - out of range (more than 4 hex digits = > 0xFFFF)
         *
         * Pure function — the test suite exercises the full matrix in
         * [MemoryEditorWindowTest] without booting JavaFX.
         */
        fun parseAddress(text: String): Int? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            // Strip a single leading '$'. Only the first character — a stray
            // '$' mid-string is treated as a non-hex typo (returns null).
            val withoutDollar = if (trimmed.startsWith('$')) trimmed.substring(1) else trimmed
            if (withoutDollar.isEmpty()) return null
            if (withoutDollar.length > 4) return null // > 0xFFFF guaranteed
            // toLong with radix 16 throws NumberFormatException on non-hex
            // chars (and is faster + safer than rolling our own digit check).
            return try {
                val value = withoutDollar.toLong(16)
                if (value in 0..0xFFFF) value.toInt() else null
            } catch (e: NumberFormatException) {
                null
            }
        }

        /**
         * Render one 16-byte row as `"ADDR B0 B1 ... B15 ASCII"` (address + 16
         * hex bytes + 16-character ASCII column). Pure function of [rowIndex]
         * and the [peek] provider — no JavaFX, so it's unit-testable on its
         * own. [rowIndex] is 0..[ROWS]-1; the row covers CPU addresses
         * `rowIndex*16 .. rowIndex*16+15`.
         *
         * The layout changed in issue #171 — the 2-space separator after the
         * address was kept (the address gutter still claims 6 chars
         * visually) and a single space now separates the hex bytes from the
         * ASCII column. The pure form is what the cell factory's [renderRow]
         * mirrors when building the [HBox]; keeping them in lockstep means
         * a unit test of the format also validates the visible row shape.
         */
        fun formatRow(rowIndex: Int, peek: (Int) -> Byte): String {
            val base = rowIndex * 16
            val sb = StringBuilder()
            sb.append(String.format("%04X", base)).append("  ")
            val bytes = ByteArray(16)
            for (i in 0 until 16) {
                val b = peek(base + i)
                bytes[i] = b
                sb.append(String.format("%02X", b.toUnsignedInt()))
                if (i != 15) sb.append(' ')
            }
            sb.append(" ").append(formatAsciiColumn(bytes))
            return sb.toString()
        }

        /**
         * Build the per-cell coloured row for one 16-byte line (issue #169).
         * Returns an [HBox] of [Text] / [StackPane] children: the address gutter
         * first, then 16 byte cells separated by spaces. A byte that's currently
         * fading (age > 0) is wrapped in a [StackPane] with a semi-transparent
         * background tinted green for UP or blue for DOWN, with alpha scaling
         * linearly with the remaining fade age.
         *
         * **Why an [HBox] and not a [javafx.scene.text.TextFlow].** A `TextFlow`
         * wraps at the parent's width when the next child would overflow, and
         * the cell can be auto-sized to a width narrower than 16 bytes (e.g.
         * the cell's prefWidth matching its content's natural width minus the
         * visible highlighted bytes). That made highlighted bytes wrap onto a
         * second line, which looked broken. An `HBox` is a single-row layout —
         * children are placed left-to-right and never wrap, so the row is
         * always one line. The `minWidth = 480.0` on the cell forces the cell
         * wide enough that the HBox never overflows.
         *
         * [font] is propagated to every [Text] child because `Text` nodes
         * inside an `HBox` don't inherit the cell's font — without this the
         * hex digits would render in the default System font instead of the
         * monospaced one the rest of the editor uses.
         *
         * [directionAt] and [ageAt] are lookup functions rather than direct
         * collections so the cell factory can stay decoupled from the window's
         * internal maps — useful for testing and for future renderer swaps.
         *
         * **Selection + editing (issue #170).** The cell whose address equals
         * [selectedAddress] gets an amber ring so the user can see what they're
         * about to edit. While a partial edit is in progress (the user has typed
         * the first of two nibbles), [pendingNibble] is non-null and the selected
         * cell shows `"X_"` (e.g. `A_`) instead of its current byte — the
         * underscore reads as "waiting for the second digit". Every byte cell
         * gets a click handler ([onByteClick]) so a click selects it. A cell that
         * needs a background (fading) and/or a selection ring is wrapped in a
         * [StackPane]; the ring is drawn with layered `-fx-background-color` +
         * `-fx-background-insets` (NOT `-fx-border-width`) so a selected cell
         * stays the exact width of a bare byte — the issue #169 invariant.
         */
        fun renderRow(
            rowIndex: Int,
            peek: (Int) -> Byte,
            directionAt: (Int) -> Direction,
            ageAt: (Int) -> Int,
            font: Font,
            selectedAddress: Int?,
            pendingNibble: Int?,
            onByteClick: (Int) -> Unit,
        ): Node {
            val base = rowIndex * 16
            val row = HBox().apply {
                alignment = Pos.CENTER_LEFT
            }
            // Peek all 16 bytes of this row once (issue #171 review): the
            // hex cells below and the trailing ASCII column both need the
            // same values, and `formatRow` (the pure contract the test
            // suite pins) already uses this once-and-reuse pattern. Halving
            // the per-row peek count also matches the byte-cell's
            // contract — each visible byte is read exactly once per render.
            val rowBytes = ByteArray(16) { i -> peek(base + i) }

            // Address gutter (issue #171): 4-digit hex address, wrapped in a
            // StackPane whose background is the row's [MemoryRegion] colour.
            // A StackPane is the same wrap used for byte cells (issue #169),
            // so the gutter still sizes to its content and never adds row
            // width beyond the 6-char `"XXXX  "` footprint. The region tint
            // is the visual "where am I in the NES memory map" hint.
            val region = regionForAddress(base)
            val (rr, rg, rb) = regionColor(region)
            row.children.add(StackPane().apply {
                alignment = Pos.CENTER
                this.style = "-fx-background-color: rgb($rr, $rg, $rb);" +
                    "-fx-background-radius: 2;"
                children.add(Text(String.format("%04X", base)).apply { this.font = font })
            })
            // Two bare-space Text nodes between the gutter and the first
            // byte — mirrors the pure `formatRow` output of `"ADDR  B0..."`
            // (2 spaces) so the cell factory and the test-pinned contract
            // produce the same column alignment. The StackPane above
            // provides the visual region band; the spaces preserve the
            // "feels like one column" hex reader has learned from prior
            // slices.
            row.children.add(Text(" ").apply { this.font = font })
            row.children.add(Text(" ").apply { this.font = font })

            for (i in 0 until 16) {
                val addr = base + i
                val selected = addr == selectedAddress

                // Selected cell mid-edit shows the half-typed nibble as "X_";
                // otherwise the live byte value. Both are two monospaced chars,
                // so the cell width never changes between display and edit.
                val label = if (selected && pendingNibble != null) {
                    String.format("%X_", pendingNibble)
                } else {
                    String.format("%02X", rowBytes[i].toUnsignedInt())
                }
                val byteText = Text(label).apply { this.font = font }

                val age = ageAt(addr)
                // The fade tint (issue #169), if this cell is currently changing.
                // Linear alpha: 1.0 at FADE_TICKS, 0.0 at 1, then halved so the
                // tint stays subtle enough to read the digits through it.
                val fadeFill: String? = if (age > 0) {
                    val (r, g, b) = when (directionAt(addr)) {
                        Direction.UP -> UP_COLOR
                        Direction.DOWN -> DOWN_COLOR
                        Direction.UNCHANGED -> DEFAULT_COLOR
                    }
                    val alpha = (age.toDouble() / FADE_TICKS) * 0.5
                    "rgba($r, $g, $b, $alpha)"
                } else null

                val node: Node = if (fadeFill != null || selected) {
                    val style = if (selected) {
                        // Draw the selection as a 1px ring via *background* layers
                        // + `-fx-background-insets`, NOT `-fx-border-width`. A real
                        // border adds 2px to the cell's layout size, which would
                        // re-introduce issue #169's row-width-growth landmine (a
                        // selected cell must stay the exact width of a bare byte).
                        // Layer 0 (inset 0) = amber ring; layer 1 (inset 1) = the
                        // fade tint, or transparent when the cell isn't fading.
                        val (r, g, b) = SELECT_COLOR
                        val inner = fadeFill ?: "transparent"
                        "-fx-background-color: rgb($r, $g, $b), $inner;" +
                            "-fx-background-insets: 0, 1;" +
                            "-fx-background-radius: 2, 1;"
                    } else {
                        // Fading only: a single tinted background, exactly as #169.
                        "-fx-background-color: $fadeFill;" +
                            "-fx-background-radius: 2;"
                    }
                    StackPane().apply {
                        alignment = Pos.CENTER
                        // No padding / no border-width: a background-only StackPane
                        // sizes to its content, so a highlighted or selected cell is
                        // the same width as a bare Text node (the issue #169 invariant).
                        this.style = style
                        children.add(byteText)
                    }
                } else {
                    byteText
                }

                node.onMouseClicked = EventHandler<MouseEvent> { onByteClick(addr) }
                row.children.add(node)

                if (i != 15) {
                    row.children.add(Text(" ").apply { this.font = font })
                }
            }
            // ASCII decode column (issue #171). One Text, 16 chars, '.' for
            // non-printable bytes. The single-space separator before it keeps
            // the column visually grouped with the hex bytes without crowding
            // them — the eye still scans the row as "hex first, ASCII second".
            // Reuses [rowBytes] from the top of this function rather than
            // re-peeking, matching the once-and-reuse pattern of the pure
            // [formatRow] helper.
            row.children.add(Text(" ").apply { this.font = font })
            row.children.add(Text(formatAsciiColumn(rowBytes)).apply { this.font = font })
            return row
        }

        // Colour palette: muted greens / blues so the hex digits stay readable
        // through the highlight. (Bright pure colours compete with the digits
        // for attention and make a 16-cell row look like a barcode.)
        private val UP_COLOR = Triple(46, 204, 64)     // green
        private val DOWN_COLOR = Triple(52, 152, 219)   // blue/cyan
        private val DEFAULT_COLOR = Triple(0, 0, 0)     // sentinel; never used (UNCHECKED never reaches the renderer)

        // Selection border (issue #170): a warm gold that reads clearly as
        // "this is the edit cursor" against both the default background and the
        // muted green/blue change tints — distinct from either so a selected
        // cell that's also fading is unambiguous.
        private val SELECT_COLOR = Triple(255, 193, 7)  // amber/gold

        // Region gutter colours (issue #171). Pastels — pale enough that the
        // black hex digits of the address stay readable through the tint, and
        // muted enough that a band of 4-row RAM next to a band of 50-row CART
        // doesn't visually compete. The four values are pinned by
        // `regionColor` test (must all differ from one another).
        private val RAM_GUTTER_COLOR = Triple(220, 235, 250)  // pale blue
        private val PPU_GUTTER_COLOR = Triple(215, 240, 215)  // pale green
        private val APU_GUTTER_COLOR = Triple(255, 240, 210)  // pale amber
        private val CART_GUTTER_COLOR = Triple(230, 230, 230) // light grey
    }
}
