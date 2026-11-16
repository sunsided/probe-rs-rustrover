package dev.markusmayer.proberover.actions

import com.google.gson.GsonBuilder
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.markusmayer.proberover.cli.ProbeRsTools
import dev.markusmayer.proberover.debugger.ProbeRsDriverConfiguration
import dev.markusmayer.proberover.launch.LaunchConfigBuilder
import dev.markusmayer.proberover.settings.ProbeRsConfigurable
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import java.awt.datatransfer.StringSelection

/**
 * Copies what a bug report needs: versions, what the plugin detected, the launch JSON it sends to probe-rs and the
 * last session's DAP traffic. Never includes the remote server token.
 */
class CopyDiagnosticsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = Diagnostics.collect(project)
            ApplicationManager.getApplication().invokeLater {
                CopyPasteManager.getInstance().setContents(StringSelection(report))
                ProbeRsNotifications.group().createNotification(
                    "probe-rs diagnostics copied",
                    "${report.lines().size} lines on the clipboard. Check them before sharing: they include file paths.",
                    NotificationType.INFORMATION,
                ).notify(project)
            }
        }
    }

    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

object Diagnostics {
    fun collect(project: Project, traceLines: Int = 200): String = buildString {
        val service = project.service<ProbeRsProjectService>()
        val settings = service.getState()
        val pluginVersion = Diagnostics::class.java.classLoader.getResource("META-INF/plugin.xml")?.readText()
            ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1) }
        appendLine("## probe-rs for RustRover diagnostics")
        appendLine("plugin: ${pluginVersion ?: "unknown"}")
        appendLine("IDE: ${ApplicationInfo.getInstance().fullApplicationName} (${ApplicationInfo.getInstance().build})")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        val probeRs = service.probeRsExecutable()
        appendLine("probe-rs: ${probeRs ?: "not found"}")
        probeRs?.let { exe ->
            appendLine("probe-rs version: ${runCatching { ProbeRsTools.get().version(exe)?.let(ProbeRsTools::format) }.getOrNull() ?: "unknown"}")
            val probes = runCatching { ProbeRsTools.get().probes(exe) }.getOrDefault(emptyList())
            appendLine("probes: " + (probes.joinToString { it.label }.ifEmpty { "none" }))
        }
        appendLine()
        appendLine("## Project")
        appendLine("enabled: ${settings.enabled}")
        appendLine("detected: ${ProbeRsConfigurable.describe(service.detect())}")
        appendLine("overrides: chip=${settings.chipOverride} probe=${settings.probe} svd=${settings.svdFile} " +
            "skipUnchangedFlash=${settings.skipUnchangedFlash} remoteHost=${if (settings.remoteHost.isNullOrBlank()) "none" else "set"}")
        appendLine("per run configuration: ${settings.perConfiguration}")
        val target = service.resolveTarget()
        appendLine("resolved target: $target")
        target?.let {
            appendLine()
            appendLine("## Launch arguments (program path is a placeholder)")
            appendLine(GsonBuilder().setPrettyPrinting().create().toJson(LaunchConfigBuilder.launch(it, "<firmware ELF>", project.basePath)))
        }
        appendLine()
        val trace = ProbeRsDriverConfiguration.traceFile()
        appendLine("## Last session's DAP traffic (${trace.path})")
        if (trace.isFile) trace.readLines().takeLast(traceLines).forEach(::appendLine) else appendLine("(no session yet)")
    }
}
