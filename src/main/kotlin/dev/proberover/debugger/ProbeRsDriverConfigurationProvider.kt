package dev.proberover.debugger

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.RsToolchainHost
import org.rust.debugger.RsDebuggerDriverConfigurationProvider

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
        val target = settings.resolveTarget() ?: return null
        val probeRs = settings.probeRsExecutable() ?: run {
            settings.notifyProbeRsMissing()
            return null
        }
        return ProbeRsDriverConfiguration(project, target, probeRs, isElevated, emulateTerminal)
    }
}
