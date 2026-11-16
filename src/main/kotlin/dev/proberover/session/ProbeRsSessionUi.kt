package dev.proberover.session

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebugSessionListener
import dev.proberover.advice.ProbeRsErrorAdvisor
import dev.proberover.defmt.DefmtLevel
import dev.proberover.defmt.DefmtLine
import dev.proberover.defmt.DefmtLineParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Shows one probe-rs debug session's side-channel events in the IDE:
 * - an **RTT** tab per channel in the debug session, defmt levels colored and locations clickable
 * - flashing progress in the IDE's progress bar
 * - probe-rs errors as notifications with a suggested fix
 *
 * Events arrive on a background thread; all UI work is queued on the EDT in arrival order.
 */
class ProbeRsSessionUi(private val session: ProbeRsSession) : SideEventListener {
    private val project: Project = session.project
    private val chip: String = session.target.chip

    private val consoles = HashMap<Int, RttPanel>()             // EDT only
    private val partialLines = HashMap<Int, StringBuilder>()    // EDT only
    private var selectedFirstTab = false                        // EDT only
    private var xSession: XDebugSession? = null                 // EDT only
    private val progress = ConcurrentHashMap<String, FlashProgress>()

    override fun onEvent(kind: String, event: JsonObject) {
        when (kind) {
            "rtt-channel" -> edt { console(event.int("channel"), event.str("name")) }
            "rtt-data" -> edt { printRtt(event.int("channel"), event.str("name"), event.str("data").orEmpty()) }
            "progress-start" -> {
                val id = event.str("progressId") ?: return
                progress[id] = FlashProgress(project, "${event.str("title") ?: "Flashing"} ($chip)")
            }
            "progress-update" -> progress[event.str("progressId")]?.update(event.double("percentage"), event.str("message"))
            "progress-end" -> progress.remove(event.str("progressId"))?.finish()
            "message" -> onMessage(event.str("severity").orEmpty(), event.str("message").orEmpty())
            "launch-failed" -> notify(
                NotificationType.ERROR,
                "probe-rs couldn't start debugging",
                event.str("message").orEmpty(),
            )
        }
    }

    override fun onConnected(channel: LaunchChannel) {
        session.channel = channel
        edt {
            findSession()
            ProbeRsSessions.get(project).started(session)
        }
    }

    override fun onClosed() {
        progress.values.forEach(FlashProgress::finish)
        progress.clear()
        edt {
            partialLines.keys.toList().forEach { flushPartial(it) }
            ProbeRsSessions.get(project).ended(session)
        }
    }

    // RTT

    private fun printRtt(channel: Int, name: String?, data: String) {
        val console = console(channel, name)
        val pending = partialLines.getOrPut(channel, ::StringBuilder).append(data)
        var lineStart = 0
        while (true) {
            val newline = pending.indexOf('\n', lineStart)
            if (newline < 0) break
            printLine(console, channel, pending.substring(lineStart, newline + 1))
            lineStart = newline + 1
        }
        pending.delete(0, lineStart)
    }

    private fun flushPartial(channel: Int) {
        val pending = partialLines[channel] ?: return
        if (pending.isEmpty()) return
        printLine(consoles[channel], channel, pending.append('\n').toString())
        pending.setLength(0)
    }

    private fun printLine(panel: RttPanel?, channel: Int, line: String) {
        if (panel == null) {
            // No debug session to attach a tab to: fall back to the session's own console.
            xSession?.consoleView?.print("[rtt$channel] $line", ConsoleViewContentType.NORMAL_OUTPUT)
            return
        }
        panel.printLine(line)
    }

    private fun console(channel: Int, name: String?): RttPanel? {
        consoles[channel]?.let { return it }
        val xSession = findSession() ?: return null
        val ui = xSession.ui ?: return null
        val panel = RttPanel(project, session, channel)

        val content = ui.createContent(
            "dev.proberover.rtt.$channel",
            panel.component,
            if (name.isNullOrBlank()) "RTT $channel" else "RTT: $name",
            AllIcons.Debugger.Console,
            panel.console.preferredFocusableComponent,
        )
        content.isCloseable = false
        Disposer.register(content, panel) // the tab goes away with the debug session, and takes the console with it
        ui.addContent(content)
        if (!selectedFirstTab) {
            selectedFirstTab = true
            ui.selectAndFocus(content, false, false)
        }
        consoles[channel] = panel
        return panel
    }

    /**
     * The IDE debug session this probe-rs session belongs to: the one named after the debugged run configuration if
     * there is one, otherwise the current session. Also forwards its pause/resume to [ProbeRsSessionListener].
     */
    private fun findSession(): XDebugSession? {
        xSession?.takeIf { !it.isStopped }?.let { return it }
        val manager = XDebuggerManager.getInstance(project)
        val running = manager.debugSessions.filter { !it.isStopped }
        val found = running.lastOrNull { it.sessionName == session.configurationName }
            ?: manager.currentSession?.takeIf { !it.isStopped }
            ?: running.lastOrNull()
            ?: return null
        xSession = found
        session.debugSession = found
        found.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = edt { ProbeRsSessions.get(project).publisher().targetHalted(session) }
            override fun sessionResumed() = edt { ProbeRsSessions.get(project).publisher().targetResumed(session) }
        })
        return found
    }

    // Messages

    private fun onMessage(severity: String, message: String) {
        val advice = ProbeRsErrorAdvisor.advise(message)
        when {
            severity == "error" -> notify(NotificationType.ERROR, "probe-rs error", message)
            severity == "warning" && advice != null -> notify(NotificationType.WARNING, "probe-rs warning", message)
        }
    }

    private fun notify(type: NotificationType, title: String, message: String) {
        val advice = ProbeRsErrorAdvisor.advise(message)
        val content = buildString {
            if (advice != null) append("<b>").append(advice.title).append("</b><br>").append(advice.hint).append("<br><br>")
            append(StringUtil.escapeXmlEntities(message))
        }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("probe-rs")
            .createNotification(title, content, type)
        advice?.link?.let { link -> notification.addAction(NotificationAction.createSimple("Open probe setup guide") { BrowserUtil.browse(link) }) }
        notification.notify(project)
    }

    // Helpers

    private fun edt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) block() }, ModalityState.any())
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    private fun JsonObject.double(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble

    companion object {
        /** Log entries are colored by level; a location-only line (`└─ … @ src/main.rs:42`) is secondary. */
        fun contentType(line: DefmtLine?): ConsoleViewContentType =
            if (line != null && line.level == null && line.path != null) ConsoleViewContentType.SYSTEM_OUTPUT
            else contentType(line?.level)

        fun contentType(level: DefmtLevel?): ConsoleViewContentType = when (level) {
            DefmtLevel.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            DefmtLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            DefmtLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            DefmtLevel.TRACE -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
            DefmtLevel.INFO, null -> ConsoleViewContentType.NORMAL_OUTPUT
        }
    }
}

/** A background task that mirrors one probe-rs progress (flashing) in the IDE's progress bar. */
private class FlashProgress(project: Project, title: String) {
    private val updates = LinkedBlockingQueue<Pair<Double?, String?>>()
    private val done = CountDownLatch(1)

    init {
        val task = object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
                while (!done.await(100, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) drain(indicator)
                drain(indicator)
            }

            private fun drain(indicator: ProgressIndicator) {
                while (true) {
                    val (percentage, message) = updates.poll() ?: return
                    if (percentage != null) {
                        indicator.isIndeterminate = false
                        indicator.fraction = (percentage / 100.0).coerceIn(0.0, 1.0)
                    }
                    if (message != null) indicator.text2 = message
                }
            }
        }
        ApplicationManager.getApplication().invokeLater({ task.queue() }, ModalityState.any())
    }

    fun update(percentage: Double?, message: String?) {
        updates.add(percentage to message)
    }

    fun finish() = done.countDown()
}
