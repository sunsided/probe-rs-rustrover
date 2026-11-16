package dev.proberover.session

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.proberover.defmt.DefmtConsoleFilter
import dev.proberover.defmt.DefmtLevel
import dev.proberover.defmt.DefmtLineParser
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * One RTT channel's tab: the log with defmt colors and links, a minimum-level filter, the usual console actions, and
 * an input line that sends text to the target's RTT down channel with the same number.
 */
class RttPanel(
    private val project: Project,
    private val session: ProbeRsSession?,
    val channel: Int,
) : Disposable {

    val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
        addFilter(DefmtConsoleFilter(project))
    }.console.also { Disposer.register(this, it) }

    /** Lines below this level are not shown. Applies to lines printed after the change. */
    @Volatile var minimumLevel: DefmtLevel = DefmtLevel.TRACE

    val input = JBTextField().apply {
        emptyText.text = "Send a line to the target on RTT channel $channel (Enter)"
        addActionListener {
            val text = this.text
            if (text.isEmpty()) return@addActionListener
            this.text = ""
            send(text)
        }
    }

    val component: SimpleToolWindowPanel = SimpleToolWindowPanel(false, true).apply {
        val center = JPanel(BorderLayout()).apply {
            add(console.component, BorderLayout.CENTER)
            add(input.apply { border = JBUI.Borders.empty(2, 4) }, BorderLayout.SOUTH)
        }
        setContent(center)
        val actions = DefaultActionGroup().apply {
            addAll(*console.createConsoleActions())
            addSeparator()
            add(levelFilterGroup())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ProbeRsRtt", actions, false)
        toolbar.targetComponent = console.component
        setToolbar(toolbar.component)
    }

    /** Prints one complete line, unless it's below [minimumLevel]. Location lines follow their log line. */
    fun printLine(line: String) {
        val parsed = DefmtLineParser.parse(line)
        val level = parsed?.level
        if (level != null) lastLevel = level
        val effective = level ?: lastLevel.takeIf { parsed?.path != null }
        if (effective != null && effective < minimumLevel) return
        console.print(line, ProbeRsSessionUi.contentType(parsed))
    }

    @Volatile private var lastLevel: DefmtLevel? = null

    private fun send(text: String) {
        val session = session
        if (session == null) {
            console.print("Not connected to a probe-rs session.\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        console.print("> $text\n", ConsoleViewContentType.USER_INPUT)
        session.rttWrite(channel, text).whenComplete { _, error ->
            if (error != null) {
                ApplicationManager.getApplication().invokeLater {
                    console.print("Couldn't send: ${error.cause?.message ?: error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }
    }

    private fun levelFilterGroup() = DefaultActionGroup("Minimum Log Level", true).apply {
        templatePresentation.icon = AllIcons.General.Filter
        DefmtLevel.entries.forEach { level ->
            add(object : ToggleAction(level.name.lowercase().replaceFirstChar(Char::uppercase)) {
                override fun isSelected(e: AnActionEvent) = minimumLevel == level
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) minimumLevel = level
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
    }

    override fun dispose() = Unit
}
