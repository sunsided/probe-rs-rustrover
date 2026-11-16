package dev.markusmayer.proberover.session

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.markusmayer.proberover.svd.SvdFieldIndex
import dev.markusmayer.proberover.svd.SvdParser
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The debug session's **probe-rs** tab: the scopes RustRover doesn't show (CPU registers, the SVD file's peripherals,
 * statics), for the stack frame RustRover shows. Children load when a node is expanded. While the target runs the
 * last values stay, grayed out; after each halt the rows are updated in place, open nodes stay open and changed values
 * are highlighted.
 */
class ScopesPanel(private val session: ProbeRsSession) : Disposable {
    /** One row from probe-rs: a scope or a variable. [reference] > 0 means it has children. */
    data class Entry(val name: String, val value: String, val type: String?, val reference: Int, val isScope: Boolean = false)

    /** What a tree node holds. [changed]: the value differs from the one before the last halt. */
    private class Item(val entry: Entry, val row: ScopeRows.Row, val changed: Boolean) {
        override fun toString() = entry.name // for speed search
    }

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }
    val component: JComponent = JPanel(BorderLayout()).apply { add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER) }

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    /** Name paths of expanded nodes. */
    private val expanded = mutableSetOf<List<String>>()
    private var generation = 0
    /** The target runs: the rows show the values from the last halt. */
    private var stale = false

    init {
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.emptyText.text = "Shown when the target halts"
        com.intellij.ui.PopupHandler.installPopupMenu(tree, com.intellij.openapi.actionSystem.DefaultActionGroup(AddToLiveWatch()), "ProbeRsScopesPopup")
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                expanded += namePath(node)
                if (node.hasPlaceholder()) load(node)
            }
            override fun treeWillCollapse(event: TreeExpansionEvent) {
                val prefix = namePath(event.path.lastPathComponent as DefaultMutableTreeNode)
                expanded.removeAll { it.size >= prefix.size && it.subList(0, prefix.size) == prefix }
            }
        })
    }

    /** Decodes field values with the SVD file's enumerated values, which probe-rs leaves out. Loaded on the first halt. */
    @Volatile private var decoder: ScopeRows.Decoder? = null
    @Volatile private var svdIndex: SvdFieldIndex? = null
    private var decoderLoaded = false

    /** The target halted: update to the frame RustRover shows, once it has asked probe-rs for its scopes. */
    fun halted() {
        val svd = session.target.svdFile
        if (decoderLoaded || svd == null) return refresh(attempt = 0)
        decoderLoaded = true
        ApplicationManager.getApplication().executeOnPooledThread {
            svdIndex = runCatching { SvdFieldIndex(SvdParser.parse(java.io.File(svd))) }.getOrNull()
            decoder = svdIndex?.let { index ->
                ScopeRows.Decoder(index::meaning)
            }
            edt { refresh(attempt = 0) }
        }
    }

    fun resumed() {
        generation++
        alarm.cancelAllRequests()
        stale = true
        tree.repaint()
    }

    private fun refresh(attempt: Int) {
        val gen = ++generation
        session.scopes().whenComplete { body, error ->
            edt {
                if (gen != generation) return@edt
                if (error != null || body == null) {
                    // RustRover asks for the frame's scopes shortly after the halt; until then the relay has no frame.
                    if (attempt < 10) alarm.addRequest({ refresh(attempt + 1) }, 300)
                    else if (root.childCount == 0) tree.emptyText.text = (error?.cause ?: error)?.message ?: "No scopes"
                    return@edt
                }
                stale = false
                update(root, topLevel(body))
                tree.emptyText.text = "probe-rs has no scopes for this frame"
            }
        }
    }

    /** Loads the children of a node expanded for the first time. */
    private fun load(node: DefaultMutableTreeNode) {
        val entry = item(node)?.entry ?: return
        val gen = generation
        session.variables(entry.reference).thenCompose { body ->
            // A peripheral group holding just the peripheral of the same name (PM → PM, PORT → PORT): show its registers directly.
            val only = children(body).singleOrNull()
            if (entry.type == "Peripheral Group" && only != null && only.type == "Peripheral" && only.name == entry.name && only.reference > 0) {
                session.variables(only.reference)
            } else java.util.concurrent.CompletableFuture.completedFuture(body)
        }.whenComplete { body, error ->
            edt {
                if (gen != generation) return@edt
                val children = if (error != null || body == null) {
                    listOf(Entry("couldn't read", (error?.cause ?: error)?.message.orEmpty(), null, 0))
                } else children(body)
                update(node, children)
            }
        }
    }

    /**
     * Makes [parent]'s children match [entries], reusing nodes by name so that the tree keeps its expansion and
     * selection. Expanded children are reloaded; collapsed ones forget their children until opened again.
     */
    private fun update(parent: DefaultMutableTreeNode, entries: List<Entry>) {
        val old = (0 until parent.childCount).map { parent.getChildAt(it) as DefaultMutableTreeNode }
            .filter { item(it) != null }.associateBy { item(it)!!.entry.name }
        val sameShape = old.keys.toList() == entries.map { it.name } && parent.childCount == entries.size
        val fromSvd = parent !== root && namePath(parent).firstOrNull() == "SVD Peripherals"
        val nodes = entries.map { e ->
            val row = ScopeRows.row(e.name, e.value, e.type, e.isScope, decoder, fromSvd)
            val previous = old[e.name]?.let(::item)
            val changed = previous != null && (previous.row.value != row.value || previous.row.notRead != row.notRead)
            (old[e.name] ?: DefaultMutableTreeNode()).apply { userObject = Item(e, row, changed) }
        }
        fun reference(node: DefaultMutableTreeNode) = item(node)!!.entry.reference
        if (!sameShape) {
            // Different rows: rebuild this level, then re-open what was open (which loads it).
            parent.removeAllChildren()
            nodes.forEach { node ->
                node.removeAllChildren()
                if (reference(node) > 0) node.add(placeholder())
                parent.add(node)
            }
            model.nodeStructureChanged(parent)
            nodes.filter { reference(it) > 0 && namePath(it) in expanded }.forEach { tree.expandPath(TreePath(it.path)) }
            return
        }
        // Same rows: update them in place, so nothing collapses or flickers.
        for (node in nodes) {
            when {
                reference(node) <= 0 -> if (node.childCount > 0) { node.removeAllChildren(); model.nodeStructureChanged(node) }
                namePath(node) in expanded -> load(node)
                !node.hasPlaceholder() -> { node.removeAllChildren(); node.add(placeholder()); model.nodeStructureChanged(node) }
            }
            model.nodeChanged(node)
        }
    }

    /** Right-click → Add to Live Watch, for statics and SVD registers. */
    private inner class AddToLiveWatch : com.intellij.openapi.actionSystem.AnAction("Add to Live Watch", "Sample it while the target runs", AllIcons.Debugger.Watch) {
        private fun target(): LiveWatchTarget? {
            val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
            val path = node.path.drop(1).mapNotNull { item(it)?.row }
            return liveWatchTarget(path) { address -> svdIndex?.register(address)?.sizeBytes }
        }
        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) { e.presentation.isEnabled = target() != null }
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            val t = target() ?: return
            dev.markusmayer.proberover.watch.LiveWatchToolWindowFactory.addWatch(session.project, t.input, t.type, t.label)
        }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private fun placeholder() = DefaultMutableTreeNode(LOADING)
    private fun DefaultMutableTreeNode.hasPlaceholder() = childCount == 1 && (getChildAt(0) as DefaultMutableTreeNode).userObject === LOADING
    private fun item(node: Any?): Item? = (node as? DefaultMutableTreeNode)?.userObject as? Item
    private fun namePath(node: DefaultMutableTreeNode): List<String> = node.path.drop(1).map { item(it)?.entry?.name.orEmpty() }

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    override fun dispose() = Unit

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            if ((value as? DefaultMutableTreeNode)?.userObject === LOADING) {
                append("loading…", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                return
            }
            val item = item(value) ?: return
            val r = item.row
            icon = icon(r.kind)
            val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
            append(r.name, if (stale) SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            when {
                r.notRead -> append("  not read (side effects)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                r.value != null && r.value.isNotEmpty() -> append(
                    "  " + r.value,
                    when {
                        stale -> gray
                        item.changed -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, FileStatus.MODIFIED.color)
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    },
                )
            }
            r.raw?.let { append("  $it", gray) }
            r.description?.let { append("   $it", gray) }
            r.location?.let { append("   $it", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES) }
            toolTipText = listOfNotNull(r.fieldDescription, r.description, r.fullValue ?: r.value, r.location, item.entry.value.takeIf { r.kind == ScopeRows.Kind.FIELD }?.let { "bits: ${it.substringBefore(" @")}" })
                .joinToString("<br>").takeIf { it.isNotEmpty() }?.let { "<html>$it</html>" }
            ipad = JBUI.insetsRight(8)
        }

        private fun icon(kind: ScopeRows.Kind): Icon = when (kind) {
            ScopeRows.Kind.SCOPE -> AllIcons.Nodes.Folder
            ScopeRows.Kind.GROUP -> AllIcons.Nodes.ModuleGroup
            ScopeRows.Kind.PERIPHERAL -> AllIcons.Nodes.Module
            ScopeRows.Kind.REGISTER -> AllIcons.Debugger.Db_primitive
            ScopeRows.Kind.FIELD -> AllIcons.Nodes.Field
            ScopeRows.Kind.NAMESPACE -> AllIcons.Nodes.Package
            ScopeRows.Kind.VALUE -> AllIcons.Debugger.Value
        }
    }

    /** What Live Watch is given: a name from the firmware or an address, with a type and a label for addresses. */
    data class LiveWatchTarget(val input: String, val type: dev.markusmayer.proberover.watch.WatchType?, val label: String?)

    companion object {
        private val LOADING = Any()

        /**
         * What a row can be watched as, from its rows from the top scope down: a static by its Rust path
         * (`blinky_basic::COUNTER`, as the firmware's symbols name it), an SVD register by its address, sized from the
         * SVD ([registerBytes]). CPU registers aren't in memory; fields are parts of a register.
         */
        fun liveWatchTarget(path: List<ScopeRows.Row>, registerBytes: (Long) -> Int?): LiveWatchTarget? {
            val scope = path.firstOrNull()?.name ?: return null
            val row = path.last()
            val inner = path.drop(1)
            return when {
                scope == "Statics" && row.kind == ScopeRows.Kind.VALUE && inner.dropLast(1).all { it.kind == ScopeRows.Kind.NAMESPACE } ->
                    LiveWatchTarget(inner.joinToString("::") { it.name }, null, null)
                scope == "SVD Peripherals" && row.kind == ScopeRows.Kind.REGISTER && row.location != null -> {
                    val address = row.location.substringBefore(' ')
                    val bytes = address.removePrefix("0x").toLongOrNull(16)?.let(registerBytes)
                    LiveWatchTarget(address, bytes?.let { dev.markusmayer.proberover.watch.WatchType.forSize(it.toLong()) },
                        inner.map { it.name }.distinct().joinToString("."))
                }
                else -> null
            }
        }

        /** probe-rs's scope names → what the tab shows, in this order. The locals are RustRover's own view. */
        private val SCOPES = linkedMapOf("Registers" to "CPU Registers", "Peripherals" to "SVD Peripherals", "Static" to "Statics")

        /** The top-level rows for a `scopes` body. */
        fun topLevel(body: JsonObject): List<Entry> {
            val scopes = body.getAsJsonArray("scopes")?.map { it.asJsonObject }.orEmpty()
            return SCOPES.mapNotNull { (name, label) ->
                val scope = scopes.firstOrNull { it.get("name")?.asString == name } ?: return@mapNotNull null
                Entry(label, "", null, scope.get("variablesReference")?.asInt ?: 0, isScope = true)
            }
        }

        /** The rows for a `variables` body, without probe-rs's optimized-out noise. */
        fun children(body: JsonObject): List<Entry> =
            body.getAsJsonArray("variables")?.map { it.asJsonObject }.orEmpty().map { v ->
                Entry(
                    v.get("name")?.asString.orEmpty(),
                    v.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    v.get("type")?.takeIf { it.isJsonPrimitive }?.asString,
                    v.get("variablesReference")?.asInt ?: 0,
                )
            }.filterNot { ScopeRows.isNoise(it.name, it.value) }
    }
}
