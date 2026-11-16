package dev.markusmayer.proberover.peripherals

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import dev.markusmayer.proberover.session.ProbeRsSession
import dev.markusmayer.proberover.session.ProbeRsSessionListener
import dev.markusmayer.proberover.session.ProbeRsSessions
import dev.markusmayer.proberover.settings.ProbeRsConfigurable
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import dev.markusmayer.proberover.svd.RegisterValues
import dev.markusmayer.proberover.svd.SvdDevice
import dev.markusmayer.proberover.svd.SvdField
import dev.markusmayer.proberover.svd.SvdParser
import dev.markusmayer.proberover.svd.SvdPeripheral
import dev.markusmayer.proberover.svd.SvdRegister
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Reads target memory for the Peripherals view. [ProbeRsSession] in the IDE; a fake in tests. */
interface MemoryAccess {
    fun read(address: Long, count: Int): CompletableFuture<ByteArray>
    fun write(address: Long, data: ByteArray): CompletableFuture<Unit>
}

private class SessionMemory(val session: ProbeRsSession) : MemoryAccess {
    override fun read(address: Long, count: Int) = session.readMemory(address, count)
    override fun write(address: Long, data: ByteArray) = session.writeMemory(address, data)
}

/**
 * The **Peripherals** tool window: the chip's peripherals from its SVD file, with live register and field values read
 * through probe-rs. A peripheral is read when expanded, and expanded peripherals refresh whenever the target halts
 * (or every half second with Live on). Double-click a writable register or field to change it.
 */
class PeripheralsPanel(private val project: Project) : Disposable {

    private class PeripheralNode(val peripheral: SvdPeripheral) {
        var block: ByteArray? = null
        var previous: ByteArray? = null
        var error: String? = null
    }
    private class RegisterNode(val register: SvdRegister, val peripheral: PeripheralNode)
    private class FieldNode(val field: SvdField, val register: RegisterNode)

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    /** Memory access override for tests; otherwise the active probe-rs session. */
    var memoryOverride: MemoryAccess? = null
    private val memory: MemoryAccess? get() = memoryOverride ?: ProbeRsSessions.get(project).active?.let(::SessionMemory)

    var device: SvdDevice? = null
        private set
    private var devicePath: String? = null
    private var live = false
    private val liveTimer = Timer(500) { if (live && memory != null) refreshExpanded() }

    val component = SimpleToolWindowPanel(true, true).apply {
        setContent(JBScrollPane(tree))
        val group = DefaultActionGroup(
            object : AnAction("Refresh", "Read the expanded peripherals again", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshExpanded()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = memory != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : ToggleAction("Live", "Refresh expanded peripherals every 0.5 s while the target runs", AllIcons.Actions.Resume) {
                override fun isSelected(e: AnActionEvent) = live
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    live = state
                    if (state) liveTimer.start() else liveTimer.stop()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : AnAction("Reload SVD File", null, AllIcons.Actions.Rerun) {
                override fun actionPerformed(e: AnActionEvent) { devicePath = null; loadDevice() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("ProbeRsPeripherals", group, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
    }

    init {
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val node = (event.path.lastPathComponent as DefaultMutableTreeNode).userObject
                if (node is PeripheralNode && node.block == null) read(node)
            }
            override fun treeCollapsed(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) tree.getPathForLocation(e.x, e.y)?.let { edit(it) }
            }
        })
        project.messageBus.connect(this).subscribe(ProbeRsSessionListener.TOPIC, object : ProbeRsSessionListener {
            override fun sessionStarted(session: ProbeRsSession) { loadDevice(); refreshExpanded() }
            override fun targetHalted(session: ProbeRsSession) = refreshExpanded()
            override fun sessionEnded(session: ProbeRsSession) = tree.repaint()
        })
        loadDevice()
    }

    /** Loads the SVD file of the active session or the project settings, or shows what's missing. */
    fun loadDevice() {
        val path = svdPath()
        if (path == null) {
            showEmpty()
            return
        }
        if (path == devicePath && device != null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { SvdParser.parse(File(path)) }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { setDevice(it, path) }.onFailure {
                    tree.emptyText.clear().appendText("Couldn't read ${File(path).name}: ${it.message}")
                    root.removeAllChildren(); model.reload()
                }
            }, ModalityState.any())
        }
    }

    fun setDevice(device: SvdDevice, path: String?) {
        this.device = device
        devicePath = path
        root.removeAllChildren()
        device.peripherals.forEach { p ->
            val pNode = PeripheralNode(p)
            val pTree = DefaultMutableTreeNode(pNode)
            p.registers.forEach { r ->
                val rNode = RegisterNode(r, pNode)
                pTree.add(DefaultMutableTreeNode(rNode).apply { r.fields.forEach { add(DefaultMutableTreeNode(FieldNode(it, rNode))) } })
            }
            root.add(pTree)
        }
        model.reload()
        tree.emptyText.text = "The SVD file has no peripherals"
    }

    private fun showEmpty() {
        device = null; devicePath = null
        root.removeAllChildren(); model.reload()
        val found = project.service<ProbeRsProjectService>().findSvdFiles()
        val text = tree.emptyText.clear()
        text.appendLine("No SVD file for this chip.")
        if (found.isNotEmpty()) {
            text.appendLine("Found in the project:")
            found.take(5).forEach { file ->
                text.appendLine(file.name, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    project.service<ProbeRsProjectService>().getState().svdFile = file.path
                    loadDevice()
                }
            }
        }
        text.appendLine("Choose one in Settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ProbeRsConfigurable::class.java)
            loadDevice()
        }
    }

    private fun svdPath(): String? {
        val configured = ProbeRsSessions.get(project).active?.target?.svdFile
            ?: project.service<ProbeRsProjectService>().getState().svdFile
            ?: project.service<ProbeRsProjectService>().findSvdFiles().singleOrNull()?.path
            ?: return null
        val file = File(configured).let { if (it.isAbsolute) it else File(project.basePath ?: "", configured) }
        return file.takeIf { it.isFile }?.path
    }

    fun refreshExpanded() {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if (tree.isExpanded(TreePath(child.path))) read(child.userObject as PeripheralNode)
        }
    }

    /** Values changed: let the tree recompute row widths, or longer values get cut off. */
    private fun changed(node: PeripheralNode) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if (child.userObject !== node) continue
            child.depthFirstEnumeration().asSequence().forEach { model.nodeChanged(it as DefaultMutableTreeNode) }
        }
    }

    private fun read(node: PeripheralNode) {
        val memory = memory ?: return
        val span = node.peripheral.span ?: return
        val length = (span.last - span.first + 1).coerceAtMost(MAX_BLOCK).toInt()
        memory.read(span.first, length).whenComplete { data, error ->
            ApplicationManager.getApplication().invokeLater({
                node.previous = node.block
                node.block = data
                node.error = error?.let { (it.cause ?: it).message }
                changed(node)
            }, ModalityState.any())
        }
    }

    private fun edit(path: TreePath) {
        val node = (path.lastPathComponent as DefaultMutableTreeNode).userObject
        val (register, field) = when (node) {
            is RegisterNode -> node to null
            is FieldNode -> node.register to node.field
            else -> return
        }
        val reg = register.register
        val writable = field?.access?.writable ?: reg.access.writable
        val memory = memory
        if (!writable || memory == null) return
        val current = register.peripheral.block?.let { RegisterValues.read(it, register.peripheral.peripheral.span!!.first, reg) }
        val bits = field?.bitWidth ?: reg.sizeBits
        val shown = current?.let { field?.extract(it) ?: it }?.let { RegisterValues.hex(it, bits) }.orEmpty()
        val hint = field?.values?.values?.joinToString { it.first }?.takeIf { it.isNotEmpty() }?.let { "\nNames: $it" }.orEmpty()
        val input = Messages.showInputDialog(
            project,
            "New value for ${field?.name ?: reg.name} ($bits bits: 0x…, 0b…, decimal)$hint",
            "Write ${register.peripheral.peripheral.name}.${reg.name}",
            null, shown, null,
        ) ?: return
        val value = RegisterValues.parseInput(input, bits, field) ?: run {
            Messages.showErrorDialog(project, "\"$input\" isn't a value that fits in $bits bits.", "Invalid Value")
            return
        }
        // Read-modify-write for fields, so other bits keep their current value.
        val fresh = if (field == null) CompletableFuture.completedFuture(0L)
        else memory.read(reg.address, reg.sizeBytes).thenApply { RegisterValues.read(it, reg.address, reg) ?: error("Couldn't read ${reg.name}") }
        fresh.thenCompose { old ->
            val newValue = field?.insert(old, value) ?: value
            memory.write(reg.address, RegisterValues.encode(newValue, reg.sizeBytes))
        }.whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater({
                if (error != null) Messages.showErrorDialog(project, (error.cause ?: error).message, "Couldn't Write ${reg.name}")
                read(register.peripheral)
            }, ModalityState.any())
        }
    }

    /** The values shown for a register, for tests: null if not read yet. */
    fun registerValue(peripheral: String, register: String): Long? {
        for (i in 0 until root.childCount) {
            val node = (root.getChildAt(i) as DefaultMutableTreeNode).userObject as PeripheralNode
            if (node.peripheral.name != peripheral) continue
            val reg = node.peripheral.registers.firstOrNull { it.name == register } ?: return null
            return node.block?.let { RegisterValues.read(it, node.peripheral.span!!.first, reg) }
        }
        return null
    }

    fun expand(peripheral: String) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if ((child.userObject as PeripheralNode).peripheral.name == peripheral) tree.expandPath(TreePath(child.path))
        }
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is PeripheralNode -> {
                    icon = AllIcons.Nodes.Module
                    append(node.peripheral.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + RegisterValues.hex(node.peripheral.baseAddress, 32), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    node.peripheral.description?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    node.error?.let { append("  $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
                }
                is RegisterNode -> {
                    icon = AllIcons.Nodes.Field
                    val p = node.peripheral
                    val now = p.block?.let { RegisterValues.read(it, p.peripheral.span!!.first, node.register) }
                    val before = p.previous?.let { RegisterValues.read(it, p.peripheral.span!!.first, node.register) }
                    append(node.register.name)
                    append("  " + RegisterValues.hex(node.register.address, 32), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ")
                    append(now?.let { RegisterValues.hex(it, node.register.sizeBits) } ?: "–", valueAttributes(now != null && before != null && now != before))
                    if (!node.register.access.writable) append("  read-only", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    node.register.description?.let { toolTipText = it }
                }
                is FieldNode -> {
                    icon = AllIcons.Nodes.Parameter
                    val p = node.register.peripheral
                    val reg = node.register.register
                    val now = p.block?.let { RegisterValues.read(it, p.peripheral.span!!.first, reg) }?.let(node.field::extract)
                    val before = p.previous?.let { RegisterValues.read(it, p.peripheral.span!!.first, reg) }?.let(node.field::extract)
                    append(node.field.name)
                    append(" " + node.field.bitRange, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ")
                    val text = now?.let { v -> (if (node.field.bitWidth == 1) v.toString() else RegisterValues.hex(v, node.field.bitWidth)) + (node.field.describe(v)?.let { " ($it)" } ?: "") }
                    append(text ?: "–", valueAttributes(now != null && before != null && now != before))
                    if (!node.field.access.writable) append("  read-only", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    node.field.description?.let { toolTipText = it }
                }
            }
        }

        private fun valueAttributes(changed: Boolean) =
            if (changed) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.namedColor("Plugins.tagForeground", JBColor(0xC7222D, 0xFF6B68)))
            else SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    override fun dispose() {
        liveTimer.stop()
    }

    companion object {
        /** Largest block read at once; peripherals spanning more are read partially. */
        const val MAX_BLOCK = 4096L
    }
}
