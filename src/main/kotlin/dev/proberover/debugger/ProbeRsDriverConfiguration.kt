package dev.proberover.debugger

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.ArchitectureType
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.dap.DapCommunicationMethod
import com.jetbrains.cidr.execution.debugger.backend.dap.DapDriverConfiguration
import com.jetbrains.cidr.execution.debugger.backend.dap.DapRequestType
import dev.proberover.launch.LaunchConfigBuilder
import dev.proberover.model.ProbeRsTarget
import dev.proberover.relay.RelayMain
import dev.proberover.session.LaunchChannel
import dev.proberover.session.ProbeRsSession
import dev.proberover.session.ProbeRsSessionUi
import java.io.File
import java.nio.file.Path

/**
 * Runs the IDE's DAP debugger against `probe-rs dap-server`, through [RelayMain].
 *
 * All use of internal native-debugger API is in this file and in [ProbeRsDriverConfigurationProvider].
 */
class ProbeRsDriverConfiguration(
    project: Project,
    private val target: ProbeRsTarget,
    private val probeRsExecutable: String,
    isElevated: Boolean,
    emulateTerminal: Boolean,
    /** The Cargo run configuration being debugged, if known. */
    private val configurationName: String? = null,
    /** Extra arguments for `probe-rs dap-server`, e.g. `--host` for a remote probe server. */
    private val serverArguments: List<String> = emptyList(),
    /** Attach to the running firmware (DAP `attach`: no flashing, no reset) instead of launching. */
    attach: Boolean = false,
) : DapDriverConfiguration(
    project,
    "probe-rs",
    isElevated,
    emulateTerminal,
    if (attach) DapRequestType.ATTACH else DapRequestType.LAUNCH,
    /* useSingleThreadStepping = */ true,
    /* takeRequestTypeFromDapOptions = */ false,
) {
    override fun getDriverName(): String = "probe-rs"

    override fun getDapLaunchOptions(commandLine: GeneralCommandLine): Map<String, Any> =
        LaunchConfigBuilder.launch(target, commandLine.exePath, commandLine.workDirectory?.path ?: project.basePath)

    override fun getDapAttachOptions(pid: Int): Map<String, Any> =
        LaunchConfigBuilder.attach(target, null, project.basePath)

    override fun getDapAttachOptions(commandLine: GeneralCommandLine): Map<String, Any> =
        LaunchConfigBuilder.attach(target, commandLine.exePath, commandLine.workDirectory?.path ?: project.basePath)

    override fun getCommunicationMethod(): DapCommunicationMethod = DapCommunicationMethod.Stdio

    override fun createDriverCommandLine(driver: DebuggerDriver, architectureType: ArchitectureType): GeneralCommandLine {
        // One side channel per debug session: RTT tabs, flashing progress and error notifications.
        val session = ProbeRsSession(project, target, configurationName)
        val channel = LaunchChannel(ProbeRsSessionUi(session))
        return relayCommandLine(listOf(probeRsExecutable, "dap-server") + serverArguments, channel.port to channel.token)
            .withWorkDirectory(project.basePath)
    }

    companion object {
        /** `java -cp <plugin>:<gson>:<kotlin-stdlib> dev.proberover.relay.RelayMain -- probe-rs dap-server` */
        fun relayCommandLine(probeRsExecutable: String): GeneralCommandLine =
            relayCommandLine(listOf(probeRsExecutable, "dap-server"))

        /** The relay wrapped around any DAP server command; tests use it with a fake server. */
        fun relayCommandLine(serverCommand: List<String>, sideChannel: Pair<Int, String>? = null): GeneralCommandLine {
            val javaHome = Path.of(System.getProperty("java.home"))
            val java = javaHome.resolve("bin").resolve(if (File.separatorChar == '\\') "java.exe" else "java")
            val classpath = listOf(RelayMain::class.java, JsonParser::class.java, KotlinVersion::class.java)
                .map { PathManager.getJarPathForClass(it) ?: error("Can't locate the jar for ${it.name}") }
                .distinct()
                .joinToString(File.pathSeparator)
            return GeneralCommandLine(
                listOf(java.toString(), "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", classpath, RelayMain::class.java.name) +
                    (sideChannel?.let { (port, token) -> listOf("--side-channel", port.toString(), token) } ?: emptyList()) +
                    "--" + serverCommand,
            )
        }
    }
}
