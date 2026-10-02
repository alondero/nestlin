package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.cheat.Cheat
import com.github.alondero.nestlin.cheat.CheatCode
import com.github.alondero.nestlin.cheat.CheatFormat
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Window

/** Modal draft editor: only Apply returns changes; Cancel and closing discard them. */
class CheatsDialog(owner: Window, initial: List<Cheat>) : Dialog<List<Cheat>>() {
    private val draft = initial.toMutableList()
    private val codes = TextArea().apply {
        promptText = "Enter one code per line"
        prefRowCount = 3
        style = "-fx-font-family: monospace;"
    }
    private val format = ComboBox<CheatFormat>().apply {
        items.setAll(CheatFormat.entries)
        value = CheatFormat.AUTO
    }
    private val rows = VBox(6.0)
    private val status = Label().apply { isWrapText = true }

    init {
        initOwner(owner)
        title = "Cheats"
        headerText = "Cheats for the current game"
        isResizable = true
        val apply = ButtonType("Apply", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(apply, ButtonType.CANCEL)
        val add = Button("Add codes").apply {
            disableProperty().bind(codes.textProperty().isEmpty)
            setOnAction { addPendingCodes() }
        }
        val clear = Button("Remove all").apply {
            setOnAction {
                draft.clear()
                renderRows()
            }
        }
        val help = Label(
            "Game Genie: SXIOPO or ZEXPYGLA. Raw: 075A:09 or 94A7?03:02.\n" +
                "Select a device format for eight-digit Replay / Rocky codes.\n" +
                "Use NES/Famicom codes; GameShark codes for other consoles cannot be used here.\n" +
                "Cheats last until another game is loaded or Nestlin closes."
        ).apply { isWrapText = true }
        val list = ScrollPane(rows).apply {
            isFitToWidth = true
            prefViewportHeight = 200.0
        }
        val root = VBox(10.0, help, format, codes, HBox(8.0, add, clear), list, status).apply {
            padding = Insets(10.0)
            prefWidth = 560.0
            VBox.setVgrow(list, Priority.ALWAYS)
        }
        dialogPane.content = root
        dialogPane.lookupButton(apply).addEventFilter(ActionEvent.ACTION) { event ->
            if (!addPendingCodes()) event.consume()
        }
        setResultConverter { button -> if (button == apply) draft.toList() else null }
        renderRows()
    }

    private fun addPendingCodes(): Boolean {
        return try {
            val parsed = CheatCode.parseLines(codes.text, format.value)
            draft.addAll(parsed.map { Cheat(it) })
            codes.clear()
            status.text = ""
            renderRows()
            true
        } catch (error: IllegalArgumentException) {
            status.text = error.message
            false
        }
    }

    private fun renderRows() {
        rows.children.clear()
        draft.forEachIndexed { index, cheat ->
            val code = cheat.code
            val enabled = CheckBox(code.text).apply {
                isSelected = cheat.enabled
                setOnAction { draft[index] = draft[index].copy(enabled = isSelected) }
            }
            val decoded = "%04X:%02X".format(code.address, code.value) +
                (code.compare?.let { " (if %02X)".format(it) } ?: "")
            val details = Label("${code.format}: $decoded").apply {
                maxWidth = Double.MAX_VALUE
                HBox.setHgrow(this, Priority.ALWAYS)
            }
            val remove = Button("Remove").apply {
                setOnAction {
                    draft.removeAt(index)
                    renderRows()
                }
            }
            rows.children.add(HBox(8.0, enabled, details, remove))
        }
        if (draft.isEmpty()) rows.children.add(Label("No cheats added."))
    }
}
