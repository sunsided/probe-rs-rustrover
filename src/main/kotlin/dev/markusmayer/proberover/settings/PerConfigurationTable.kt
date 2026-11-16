package dev.markusmayer.proberover.settings

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import dev.markusmayer.proberover.runconfig.EmbeddedRunSettings
import dev.markusmayer.proberover.runconfig.RttMode
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/** Editable table of probe-rs overrides per Cargo run configuration (Settings | … | probe-rs). */
class PerConfigurationTable(private val project: Project) {

    class Row(var name: String, val settings: EmbeddedRunSettings)

    private val columns: Array<ColumnInfo<Row, *>> = arrayOf(
        object : TextColumn("Run configuration") {
            override fun get(row: Row) = row.name
            override fun set(row: Row, value: String) { row.name = value.trim() }
            override fun getEditor(item: Row): TableCellEditor =
                DefaultCellEditor(ComboBox(cargoConfigurationNames().toTypedArray()).apply { isEditable = true })
        },
        object : TextColumn("Chip") {
            override fun get(row: Row) = row.settings.chip.orEmpty()
            override fun set(row: Row, value: String) { row.settings.chip = value.trim().ifEmpty { null } }
        },
        object : TextColumn("Probe") {
            override fun get(row: Row) = row.settings.probe.orEmpty()
            override fun set(row: Row, value: String) { row.settings.probe = value.trim().ifEmpty { null } }
        },
        object : TextColumn("SVD file") {
            override fun get(row: Row) = row.settings.svdFile.orEmpty()
            override fun set(row: Row, value: String) { row.settings.svdFile = value.trim().ifEmpty { null } }
        },
        object : ColumnInfo<Row, Int>("Core") {
            override fun valueOf(item: Row) = item.settings.coreIndex
            override fun setValue(item: Row, value: Int?) { item.settings.coreIndex = (value ?: 0).coerceAtLeast(0) }
            override fun isCellEditable(item: Row) = true
            override fun getColumnClass(): Class<*> = java.lang.Integer::class.java
        },
        object : ColumnInfo<Row, Boolean>("Flash") {
            override fun valueOf(item: Row) = item.settings.flash
            override fun setValue(item: Row, value: Boolean) { item.settings.flash = value }
            override fun isCellEditable(item: Row) = true
            override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        },
        object : ColumnInfo<Row, RttMode>("RTT") {
            override fun valueOf(item: Row) = item.settings.rtt
            override fun setValue(item: Row, value: RttMode?) { item.settings.rtt = value ?: RttMode.AUTO }
            override fun isCellEditable(item: Row) = true
            override fun getEditor(item: Row): TableCellEditor =
                DefaultCellEditor(ComboBox(RttMode.entries.toTypedArray()).apply { renderer = RttModeRenderer() })
            override fun getRenderer(item: Row): TableCellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) = setText((value as? RttMode)?.let(::label).orEmpty())
            }
        },
    )

    val model = ListTableModel(columns, mutableListOf<Row>())
    private val table = TableView(model).apply { setShowGrid(false) }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            val used = model.items.map { it.name }.toSet()
            val name = cargoConfigurationNames().firstOrNull { it !in used }.orEmpty()
            model.addRow(Row(name, EmbeddedRunSettings()))
            table.editCellAt(model.rowCount - 1, if (name.isEmpty()) 0 else 1)
        }
        .createPanel()

    fun reset(stored: Map<String, EmbeddedRunSettings>) {
        model.items = stored.map { (name, settings) -> Row(name, settings.copy()) }.toMutableList()
    }

    /** The table's content, without rows that have no configuration name or only default values. */
    fun current(): MutableMap<String, EmbeddedRunSettings> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return model.items
            .filter { it.name.isNotBlank() && !it.settings.isDefault }
            .associateTo(LinkedHashMap()) { it.name to it.settings.copy() }
    }

    private class RttModeRenderer : SimpleListCellRenderer<RttMode>() {
        override fun customize(list: JList<out RttMode>, value: RttMode?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = value?.let(::label).orEmpty()
        }
    }

    companion object {
        fun label(mode: RttMode): String = when (mode) {
            RttMode.AUTO -> "Detect"
            RttMode.DEFMT -> "defmt"
            RttMode.STRING -> "Plain text"
            RttMode.DISABLED -> "Off"
        }
    }

    private fun cargoConfigurationNames(): List<String> =
        RunManager.getInstance(project).allConfigurationsList.filterIsInstance<CargoCommandConfiguration>().map { it.name }

    private abstract class TextColumn(name: String) : ColumnInfo<Row, String>(name) {
        abstract fun get(row: Row): String
        abstract fun set(row: Row, value: String)
        override fun valueOf(item: Row) = get(item)
        override fun setValue(item: Row, value: String?) = set(item, value.orEmpty())
        override fun isCellEditable(item: Row) = true
    }
}
