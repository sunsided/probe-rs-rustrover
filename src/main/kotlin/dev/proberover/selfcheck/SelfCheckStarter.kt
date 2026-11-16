package dev.proberover.selfcheck

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.project.ProjectManager
import com.jetbrains.cidr.execution.debugger.backend.dap.DapDriverConfiguration
import dev.proberover.debugger.ProbeRsDriverConfiguration
import dev.proberover.debugger.ProbeRsDriverConfigurationProvider
import dev.proberover.model.ProbeRsTarget
import org.rust.debugger.RsDebuggerDriverConfigurationProvider
import java.io.File
import kotlin.system.exitProcess

/**
 * Spike-only: `rustrover.sh proberover-selfcheck <report-file>` starts the real product headless with this plugin
 * installed and reports whether our classes can load against the `visibility="internal"` modules we build on.
 */
class SelfCheckStarter : ApplicationStarter {
    override val isHeadless: Boolean get() = true
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        val report = StringBuilder()
        fun line(s: String) { report.appendLine(s); println("[selfcheck] $s") }
        var ok = true
        fun check(name: String, block: () -> String) {
            try { line("PASS $name: ${block()}") } catch (t: Throwable) { ok = false; line("FAIL $name: $t") }
        }

        check("Rust debugger extension point") {
            val providers = RsDebuggerDriverConfigurationProvider.EP_NAME.extensionList.map { it.javaClass.name }
            require(providers.firstOrNull() == ProbeRsDriverConfigurationProvider::class.java.name) { "order is $providers" }
            providers.toString()
        }
        check("DAP module classes") {
            "DapDriverConfiguration loaded by ${DapDriverConfiguration::class.java.classLoader}"
        }
        check("ProbeRsDriverConfiguration") {
            val project = ProjectManager.getInstance().defaultProject
            val config = ProbeRsDriverConfiguration(project, ProbeRsTarget(chip = "RP2040"), "probe-rs", false, false)
            val launch = config.getDapLaunchOptions(GeneralCommandLine("/fw/target/thumbv6m-none-eabi/debug/blinky"))
            "driver=${config.driverName} chip=${launch["chip"]} relay=${ProbeRsDriverConfiguration.relayCommandLine("probe-rs").parametersList.list.takeLast(3)}"
        }
        line(if (ok) "RESULT OK" else "RESULT FAILED")
        args.getOrNull(1)?.let { File(it).writeText(report.toString()) }
        exitProcess(if (ok) 0 else 1)
    }
}
