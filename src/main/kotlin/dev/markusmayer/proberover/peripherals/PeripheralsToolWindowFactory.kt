package dev.markusmayer.proberover.peripherals

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import com.intellij.openapi.components.service

/** The "Peripherals" tool window. Only offered in projects that look like embedded Rust. */
class PeripheralsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PeripheralsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(AllIcons.Nodes.DataTables)
        toolWindow.stripeTitle = "Peripherals"
    }

    override suspend fun isApplicableAsync(project: Project): Boolean {
        val detected = runCatching { project.service<ProbeRsProjectService>().detect() }.getOrNull() ?: return false
        return detected.probeRs != null || detected.isEmbeddedTarget
    }
}
