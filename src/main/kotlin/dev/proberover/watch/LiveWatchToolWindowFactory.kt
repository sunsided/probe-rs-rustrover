package dev.proberover.watch

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.proberover.settings.ProbeRsProjectService

class LiveWatchToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LiveWatchPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(AllIcons.Debugger.Watch)
        toolWindow.stripeTitle = "Live Watch"
    }

    override suspend fun isApplicableAsync(project: Project): Boolean {
        val detected = runCatching { project.service<ProbeRsProjectService>().detect() }.getOrNull() ?: return false
        return detected.probeRs != null || detected.isEmbeddedTarget
    }
}
