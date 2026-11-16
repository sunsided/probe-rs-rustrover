package dev.proberover.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.proberover.cli.ProbeRsTools
import dev.proberover.session.ProbeRsSession
import dev.proberover.session.ProbeRsSessions
import dev.proberover.settings.ProbeRsProjectService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves a core dump (registers and the chip's RAM) of the halted target, using probe-rs's `dump` command. probe-rs
 * splits the command on spaces, so the file goes to `target/probe-rs/` or, if that path has spaces, the temp folder.
 */
class SaveCoreDumpAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = ProbeRsSessions.get(project).active ?: return
        ApplicationManager.getApplication().executeOnPooledThread { save(project, session) }
    }

    private fun save(project: Project, session: ProbeRsSession) {
        val probeRs = project.service<ProbeRsProjectService>().probeRsExecutable()
        val ram = probeRs?.let { runCatching { ProbeRsTools.get().chipInfo(it, session.target.chip) }.getOrNull() }?.ram.orEmpty()
        val file = dumpFile(project)
        file.parentFile.mkdirs()
        val ranges = ram.joinToString(" ") { "0x${it.first.toString(16)} 0x${(it.last - it.first + 1).toString(16)}" }
        val expression = listOf("dump", ranges, file.path).filter { it.isNotBlank() }.joinToString(" ")
        val channel = session.channel ?: return
        channel.request("evaluate") { addProperty("expression", expression) }.whenComplete { _, error ->
            val group = ProbeRsNotifications.group()
            if (error != null) {
                group.createNotification("Couldn't save a core dump", ((error.cause ?: error).message ?: "") +
                    "<br>The target has to be halted (pause it first).", NotificationType.ERROR).notify(project)
            } else {
                val what = if (ram.isEmpty()) "registers only (probe-rs didn't report the chip's RAM)" else "registers and ${ram.size} RAM region(s)"
                group.createNotification("Core dump saved", "${file.name}: $what.", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
                    .notify(project)
            }
        }
    }

    private fun dumpFile(project: Project): File {
        val name = "coredump-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val preferred = project.basePath?.let { File(it, "target/probe-rs/$name") }
        return preferred?.takeIf { ' ' !in it.path } ?: File(System.getProperty("java.io.tmpdir"), "probe-rs-$name")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null && ProbeRsSessions.get(project).active != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
