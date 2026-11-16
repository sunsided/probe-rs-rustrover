package dev.markusmayer.proberover.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import dev.markusmayer.proberover.actions.ProbeRsNotifications
import dev.markusmayer.proberover.settings.ProbeRsProjectService

/**
 * Says once per project what the plugin will do with it: debug with probe-rs (and which chip), or that a chip is
 * needed. Never repeats; everything it offers is also in Settings.
 */
class ProbeRsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.service<ProbeRsProjectService>()
        val state = service.getState()
        if (state.introShown) return
        val detected = service.detect() ?: return
        if (detected.probeRs == null && !detected.isEmbeddedTarget) return
        state.introShown = true

        val probeRsFound = service.probeRsExecutable() != null
        val (title, content) = when {
            detected.probeRs != null -> "Embedded Rust project: debugging uses probe-rs" to
                "Debug runs on chip <b>${detected.probeRs.chip}</b> (from the runner in <code>.cargo/config.toml</code>): " +
                "it flashes the firmware, shows RTT/defmt logs in their own tab and the chip's registers in Peripherals." +
                if (probeRsFound) "" else "<br>probe-rs isn't installed yet."
            else -> "Embedded Rust project without a probe-rs runner" to
                "To debug ${detected.buildTarget} with probe-rs, set the chip in the probe-rs settings, " +
                "or use <code>runner = \"probe-rs run --chip …\"</code> in <code>.cargo/config.toml</code>."
        }
        val notification = ProbeRsNotifications.group().createNotification(title, content, NotificationType.INFORMATION)
        if (!probeRsFound) notification.addAction(NotificationAction.createSimpleExpiring("Install probe-rs") { dev.markusmayer.proberover.actions.InstallProbeRs.run(project) })
        notification
            .addAction(NotificationAction.createSimple("Settings") { ProbeRsNotifications.openSettings(project) })
            .addAction(NotificationAction.createSimple("Show Peripherals") {
                ToolWindowManager.getInstance(project).getToolWindow("Peripherals")?.show()
            })
            .addAction(NotificationAction.createSimpleExpiring("Turn off for this project") {
                state.enabled = false
            })
            .notify(project)
    }
}
