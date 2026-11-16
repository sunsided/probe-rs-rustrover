package dev.proberover.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.proberover.runconfig.ProbeRsLaunchTracker
import dev.proberover.settings.ProbeRsConfigurable
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import java.awt.datatransfer.StringSelection
import java.io.File

/** Notifications with the actions that fix them. */
object ProbeRsNotifications {
    const val SETUP_GUIDE = "https://probe.rs/docs/getting-started/probe-setup/"
    const val INSTALL_GUIDE = "https://probe.rs/docs/getting-started/installation/"
    const val UDEV_COMMAND =
        "curl -fsSL https://probe.rs/files/69-probe-rs.rules | sudo tee /etc/udev/rules.d/69-probe-rs.rules >/dev/null " +
            "&& sudo udevadm control --reload && sudo udevadm trigger"

    fun group() = NotificationGroupManager.getInstance().getNotificationGroup("probe-rs")

    fun probeRsMissing(project: Project) = group()
        .createNotification(
            "probe-rs isn't installed",
            "Debugging this embedded target needs probe-rs. Install it, or set its location in the probe-rs settings.",
            NotificationType.WARNING,
        )
        .addAction(NotificationAction.createSimpleExpiring("Install probe-rs") { InstallProbeRs.run(project) })
        .addAction(NotificationAction.createSimple("Settings") { openSettings(project) })
        .addAction(NotificationAction.createSimple("Installation guide") { BrowserUtil.browse(INSTALL_GUIDE) })
        .notify(project)

    fun probeRsTooOld(project: Project, found: String, minimum: String) = group()
        .createNotification(
            "probe-rs $found is older than this plugin expects",
            "Update to $minimum or later if debugging doesn't start or RTT stays empty.",
            NotificationType.WARNING,
        )
        .addAction(NotificationAction.createSimpleExpiring("Update probe-rs") { InstallProbeRs.run(project) })
        .notify(project)

    fun addUdevActions(notification: Notification): Notification {
        if (!SystemInfo.isLinux) return notification
        return notification.addAction(NotificationAction.createSimple("Copy udev setup command") { copyUdevCommand(notification) })
    }

    fun copyUdevCommand(notification: Notification? = null) {
        CopyPasteManager.getInstance().setContents(StringSelection(UDEV_COMMAND))
        group().createNotification(
            "udev command copied",
            "Paste it into a terminal: it installs probe-rs's udev rules and reloads them. Then reconnect the probe.<br><code>$UDEV_COMMAND</code>",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimple("Probe setup guide") { BrowserUtil.browse(SETUP_GUIDE) })
            .notify(null)
        notification?.expire()
    }

    fun openSettings(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, ProbeRsConfigurable::class.java)
}

/** Installs or updates probe-rs with cargo, in a Run tool window. */
object InstallProbeRs {
    fun command(): GeneralCommandLine {
        val cargo = findOnPath("cargo") ?: File(System.getProperty("user.home"), ".cargo/bin/cargo").path
        val binstall = findOnPath("cargo-binstall") ?: File(System.getProperty("user.home"), ".cargo/bin/cargo-binstall").takeIf { it.canExecute() }?.path
        return if (binstall != null) GeneralCommandLine(cargo, "binstall", "-y", "probe-rs-tools")
        else GeneralCommandLine(cargo, "install", "probe-rs-tools", "--locked")
    }

    fun run(project: Project) {
        val commandLine = command()
        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                val ok = event.exitCode == 0 && project.service<ProbeRsProjectService>().probeRsExecutable() != null
                ProbeRsNotifications.group().createNotification(
                    if (ok) "probe-rs installed" else "Installing probe-rs failed",
                    if (ok) "Click Debug again to start debugging." else "See the Run window for details, or follow the installation guide.",
                    if (ok) NotificationType.INFORMATION else NotificationType.ERROR,
                ).also { if (!ok) it.addAction(NotificationAction.createSimple("Installation guide") { BrowserUtil.browse(ProbeRsNotifications.INSTALL_GUIDE) }) }
                    .notify(project)
            }
        })
        RunContentExecutor(project, handler).withTitle("Install probe-rs").withActivateToolWindow(true).run()
    }

    private fun findOnPath(name: String): String? {
        val exe = if (SystemInfo.isWindows) "$name.exe" else name
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.canExecute() }?.path
    }
}

class InstallProbeRsAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        InstallProbeRs.run(e.project ?: return)
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class CopyUdevRulesCommandAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) = ProbeRsNotifications.copyUdevCommand()
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = SystemInfo.isLinux }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * Debugs the selected Cargo run configuration by attaching to the firmware that's already running: no flashing,
 * no reset (DAP `attach`).
 */
class AttachToRunningTargetAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RunManager.getInstance(project).selectedConfiguration ?: return
        project.service<ProbeRsLaunchTracker>().requestAttach()
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = project?.let { RunManager.getInstance(it).selectedConfiguration?.configuration }
        e.presentation.isEnabledAndVisible = project != null &&
            runCatching { project.service<ProbeRsProjectService>().resolveTarget() }.getOrNull() != null
        e.presentation.isEnabled = selected is CargoCommandConfiguration
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
