package dev.proberover.runconfig

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.command.CargoCommandConfiguration

/** Keeps per-configuration probe-rs overrides attached to their Cargo run configuration when it's renamed or deleted. */
class OverridesFollowRenames(private val project: Project) : RunManagerListener {

    override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings, existingId: String?) {
        if (existingId == null || settings.configuration !is CargoCommandConfiguration) return
        val oldName = previousName(settings.uniqueID, settings.name, existingId) ?: return
        if (oldName == settings.name) return
        val overrides = project.service<ProbeRsProjectService>().getState().perConfiguration
        val moved = overrides.remove(oldName) ?: return
        overrides[settings.name] = moved
    }

    override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) {
        if (settings.configuration !is CargoCommandConfiguration) return
        project.service<ProbeRsProjectService>().getState().perConfiguration.remove(settings.name)
    }

    companion object {
        /** A unique ID is a type-specific prefix followed by the name; the old ID has the same prefix. */
        fun previousName(newId: String, newName: String, oldId: String): String? {
            if (!newId.endsWith(newName)) return null
            val prefix = newId.removeSuffix(newName)
            return oldId.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }
    }
}
