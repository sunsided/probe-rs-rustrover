package dev.markusmayer.proberover.watch

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.markusmayer.proberover.settings.ProbeRsProjectService

class LiveWatchToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LiveWatchPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        content.putUserData(PANEL, panel)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(AllIcons.Debugger.Watch)
        toolWindow.stripeTitle = "Live Watch"
    }

    companion object {
        private val PANEL = com.intellij.openapi.util.Key.create<LiveWatchPanel>("probe-rs.liveWatchPanel")

        /**
         * Opens Live Watch and adds a watch, as from its + button: [input] is a name from the firmware or an address.
         * Reports a failure as a notification.
         */
        fun addWatch(project: Project, input: String, type: WatchType? = null, label: String? = null) {
            val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Live Watch") ?: return
            window.activate({
                val panel = window.contentManager.contents.firstNotNullOfOrNull { it.getUserData(PANEL) } ?: return@activate
                panel.addWatch(input, type, label)?.let { error ->
                    dev.markusmayer.proberover.actions.ProbeRsNotifications.group()
                        .createNotification("Can't watch ${label ?: input}", error, com.intellij.notification.NotificationType.WARNING)
                        .notify(project)
                }
            }, true)
        }
    }

    override suspend fun isApplicableAsync(project: Project): Boolean {
        val detected = runCatching { project.service<ProbeRsProjectService>().detect() }.getOrNull() ?: return false
        return detected.probeRs != null || detected.isEmbeddedTarget
    }
}
