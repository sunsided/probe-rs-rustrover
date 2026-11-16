package dev.markusmayer.proberover.watch

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import dev.markusmayer.proberover.elf.ElfSymbol
import dev.markusmayer.proberover.elf.ElfSymbols
import dev.markusmayer.proberover.peripherals.MemoryAccess
import dev.markusmayer.proberover.session.ProbeRsSession
import dev.markusmayer.proberover.session.ProbeRsSessionListener
import dev.markusmayer.proberover.session.ProbeRsSessions
import dev.markusmayer.proberover.svd.RegisterValues
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.table.TableCellEditor

/**
 * The **Live Watch** tool window: global variables (by name, from the firmware's symbol table) or addresses, sampled
 * while the target runs and drawn as a chart. probe-rs reads memory without stopping most cores; on some, each read
 * halts the core for a moment, so sample slowly if timing matters.
 */
class LiveWatchPanel(private val project: Project) : Disposable {

    val watches = mutableListOf<Watch>()
    var symbols: List<ElfSymbol> = emptyList()
        private set
    private var symbolsFrom: String? = null

    /** Memory access override for tests; otherwise the active probe-rs session. */
    var memoryOverride: MemoryAccess? = null
    private val memory: MemoryAccess?
        get() = memoryOverride ?: ProbeRsSessions.get(project).active?.let { s ->
            object : MemoryAccess {
                override fun read(address: Long, count: Int) = s.readMemory(address, count)
                override fun write(address: Long, data: ByteArray) = s.writeMemory(address, data)
            }
        }

    private var sampling = false
    private var inFlight = 0
    var intervalMs = 250
        set(value) { field = value; timer.delay = value }
    private val timer = Timer(intervalMs) { sample() }

    private val columns: Array<ColumnInfo<Watch, *>> = arrayOf(
        object : ColumnInfo<Watch, String>("Watch") {
            override fun valueOf(item: Watch) = item.expression
        },
        object : ColumnInfo<Watch, String>("Address") {
            override fun valueOf(item: Watch) = RegisterValues.hex(item.address, 32)
        },
        object : ColumnInfo<Watch, WatchType>("Type") {
            override fun valueOf(item: Watch) = item.type
            override fun setValue(item: Watch, value: WatchType?) { item.type = value ?: item.type; item.reset() }
            override fun isCellEditable(item: Watch) = true
            override fun getEditor(item: Watch): TableCellEditor = DefaultCellEditor(ComboBox(WatchType.entries.toTypedArray()))
        },
        object : ColumnInfo<Watch, String>("Value") {
            override fun valueOf(item: Watch) = item.last?.let(item.type::format) ?: "–"
        },
        object : ColumnInfo<Watch, String>("Min … Max") {
            override fun valueOf(item: Watch) = if (item.min == null) "" else "${item.type.format(item.min!!)} … ${item.type.format(item.max!!)}"
        },
    )
    val model = ListTableModel(columns, watches)
    private val table = TableView(model)
    val chart = Chart()

    val component = SimpleToolWindowPanel(true, true).apply {
        val list = ToolbarDecorator.createDecorator(table)
            .setAddAction { askForWatch() }
            .setRemoveAction { table.selectedObjects.forEach { watches.remove(it) }; model.items = watches; chart.repaint() }
            .createPanel()
        setContent(JBSplitter(true, 0.45f).apply { firstComponent = list; secondComponent = chart })
        val actions = DefaultActionGroup(
            object : ToggleAction("Sample", "Read the watched values while the target runs", AllIcons.Actions.Execute) {
                override fun isSelected(e: AnActionEvent) = sampling
                override fun setSelected(e: AnActionEvent, state: Boolean) = setSampling(state)
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            DefaultActionGroup("Interval", true).apply {
                templatePresentation.icon = AllIcons.Vcs.History
                listOf(50, 100, 250, 500, 1000).forEach { ms ->
                    add(object : ToggleAction("$ms ms") {
                        override fun isSelected(e: AnActionEvent) = intervalMs == ms
                        override fun setSelected(e: AnActionEvent, state: Boolean) { if (state) intervalMs = ms }
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    })
                }
            },
            object : com.intellij.openapi.actionSystem.AnAction("Clear Samples", null, AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) { watches.forEach(Watch::reset); model.fireTableDataChanged(); chart.repaint() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("ProbeRsLiveWatch", actions, true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)
    }

    init {
        table.emptyText.text = "Add a global variable or an address to watch it while the target runs"
        project.messageBus.connect(this).subscribe(ProbeRsSessionListener.TOPIC, object : ProbeRsSessionListener {
            override fun sessionStarted(session: ProbeRsSession) = loadSymbols(session.programBinary)
            // The ELF path is known once probe-rs was asked to launch it, usually after the session started.
            override fun targetHalted(session: ProbeRsSession) = loadSymbols(session.programBinary)
            override fun sessionEnded(session: ProbeRsSession) = setSampling(false)
        })
        ProbeRsSessions.get(project).active?.programBinary?.let(::loadSymbols)
    }

    fun setSampling(on: Boolean) {
        sampling = on
        if (on) timer.start() else timer.stop()
    }

    fun loadSymbols(elf: String?) {
        if (elf == null || elf == symbolsFrom) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = runCatching { ElfSymbols.read(File(elf)) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({ symbols = read; symbolsFrom = elf }, ModalityState.any())
        }
    }

    fun setSymbols(list: List<ElfSymbol>) { symbols = list }

    /** Adds a watch from user input; returns an error message, or null. */
    fun addWatch(input: String, type: WatchType? = null): String? {
        if (symbols.isEmpty()) {
            ProbeRsSessions.get(project).active?.programBinary?.let { elf ->
                symbols = runCatching { ElfSymbols.read(File(elf)) }.getOrDefault(emptyList()); symbolsFrom = elf
            }
        }
        val (address, guessed) = WatchResolver.resolve(input, symbols)
            ?: return if (symbols.isEmpty()) "Start a debug session first (symbols come from the firmware), or enter an address like 0x20000010."
            else "No variable named \"$input\" in the firmware. Use the name as in the code (e.g. COUNTER or blinky::COUNTER), or an address."
        watches += Watch(input.trim(), address, type ?: guessed)
        model.items = watches
        return null
    }

    private fun askForWatch() {
        if (symbols.isEmpty()) ProbeRsSessions.get(project).active?.programBinary?.let { elf ->
            symbols = runCatching { ElfSymbols.read(File(elf)) }.getOrDefault(emptyList()); symbolsFrom = elf
        }
        val names = symbols.map { ElfSymbols.demangledTail(it.name) }.distinct().sorted()
        val input = Messages.showEditableChooseDialog(
            "Global variable or address (0x…):", "Add Watch", null, names.toTypedArray(), names.firstOrNull() ?: "", null,
        ) ?: return
        addWatch(input)?.let { Messages.showErrorDialog(project, it, "Can't Watch \"$input\"") }
    }

    /** Reads every watch once. Skips a tick while the previous reads are still running. */
    fun sample(): CompletableFuture<Void> {
        val memory = memory ?: return CompletableFuture.completedFuture(null)
        if (inFlight > 0) return CompletableFuture.completedFuture(null)
        val reads = watches.toList().map { watch ->
            inFlight++
            memory.read(watch.address, watch.type.size).handle { bytes, _ ->
                ApplicationManager.getApplication().invokeLater({
                    inFlight--
                    bytes?.let(watch.type::decode)?.let(watch::add)
                    model.fireTableRowsUpdated(0, (model.rowCount - 1).coerceAtLeast(0))
                    chart.repaint()
                }, ModalityState.any())
            }
        }
        return CompletableFuture.allOf(*reads.toTypedArray())
    }

    /** A simple line chart of each watch's recent samples, each scaled to its own range. */
    inner class Chart : JComponent() {
        private val colors = listOf(JBColor(0x2F6FDE, 0x6A9CFF), JBColor(0xD9480F, 0xFF8A50), JBColor(0x2B8A3E, 0x69DB7C), JBColor(0x9C36B5, 0xDA77F2))

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground(); g2.fillRect(0, 0, width, height)
            val pad = JBUI.scale(8)
            val w = width - 2 * pad
            val h = height - 2 * pad - JBUI.scale(14) * watches.size.coerceAtMost(colors.size)
            if (w <= 0 || h <= 0) return
            g2.color = JBColor.border(); g2.drawRect(pad, pad, w, h)
            watches.take(colors.size).forEachIndexed { i, watch ->
                val samples = watch.samples.toList()
                g2.color = colors[i]
                if (samples.size >= 2) {
                    val lo = samples.min(); val hi = samples.max().let { if (it == lo) lo + 1 else it }
                    g2.stroke = BasicStroke(JBUI.scale(3) / 2f)
                    val step = w.toDouble() / (samples.size - 1)
                    for (k in 1 until samples.size) {
                        fun y(v: Double) = pad + h - ((v - lo) / (hi - lo) * h).toInt()
                        g2.drawLine(pad + ((k - 1) * step).toInt(), y(samples[k - 1]), pad + (k * step).toInt(), y(samples[k]))
                    }
                }
                g2.drawString("■ ${watch.expression} = ${watch.last?.let(watch.type::format) ?: "–"}" +
                    (watch.min?.let { "  (${watch.type.format(it)} … ${watch.type.format(watch.max!!)})" } ?: ""),
                    pad, pad + h + JBUI.scale(14) * (i + 1))
            }
        }
    }

    override fun dispose() = timer.stop()
}
