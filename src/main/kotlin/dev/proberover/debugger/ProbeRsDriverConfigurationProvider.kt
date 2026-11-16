package dev.proberover.debugger

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import dev.proberover.actions.ProbeRsNotifications
import dev.proberover.cli.ProbeRsTools
import dev.proberover.runconfig.ProbeRsLaunchTracker
import dev.proberover.runconfig.embeddedRunSettings
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.RsToolchainHost
import org.rust.debugger.RsDebuggerDriverConfigurationProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Registered with `order="first"` on `org.rust.debugger.driverConfigurationProvider`. Returns null for anything that
 * isn't a probe-rs embedded project, so the Rust plugin falls through to its own GDB/LLDB provider.
 */
class ProbeRsDriverConfigurationProvider : RsDebuggerDriverConfigurationProvider {
    override fun getDebuggerDriverConfiguration(
        project: Project,
        host: RsToolchainHost,
        isElevated: Boolean,
        emulateTerminal: Boolean,
    ): DebuggerDriverConfiguration? {
        val settings = project.service<ProbeRsProjectService>()
        val tracker = project.service<ProbeRsLaunchTracker>()
        val configurationName = tracker.consume()
        val attach = tracker.consumeAttach()
        val runSettings = configurationName?.let(project::embeddedRunSettings)
        val target = settings.resolveTarget(runSettings) ?: return null
        val probeRs = settings.probeRsExecutable() ?: run {
            ProbeRsNotifications.probeRsMissing(project)
            return null
        }
        checkVersionOnce(project, probeRs)
        return ProbeRsDriverConfiguration(
            project, target, probeRs, isElevated, emulateTerminal,
            configurationName = configurationName,
            serverArguments = settings.serverArguments(),
            attach = attach,
        )
    }

    private fun checkVersionOnce(project: Project, probeRs: String) {
        if (warned.putIfAbsent(probeRs, true) != null) return
        val version = runCatching { ProbeRsTools.get().version(probeRs) }.getOrNull() ?: return
        if (ProbeRsTools.isTooOld(version)) {
            ProbeRsNotifications.probeRsTooOld(project, ProbeRsTools.format(version), ProbeRsTools.format(ProbeRsTools.MINIMUM_VERSION))
        }
    }

    private companion object {
        val warned = ConcurrentHashMap<String, Boolean>()
    }
}
