package dev.markusmayer.proberover.wokwi

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.markusmayer.proberover.actions.ProbeRsNotifications
import org.jdom.Element
import java.io.File

/** What `wokwi.toml` says about the simulated firmware. */
data class WokwiConfig(val elf: String?, val firmware: String?, val gdbServerPort: Int?)

object WokwiConfigReader {
    private val toml by lazy { com.fasterxml.jackson.dataformat.toml.TomlMapper() }

    /** `[wokwi]` with `elf`, `firmware` and `gdbServerPort`, as the Wokwi VS Code and IntelliJ plugins read it. */
    fun parse(text: String): WokwiConfig? {
        val wokwi = runCatching { toml.readTree(text) }.getOrNull()?.path("wokwi") ?: return null
        if (wokwi.isMissingNode) return null
        return WokwiConfig(
            elf = wokwi.path("elf").takeIf { it.isTextual }?.asText(),
            firmware = wokwi.path("firmware").takeIf { it.isTextual }?.asText(),
            gdbServerPort = wokwi.path("gdbServerPort").takeIf { it.isInt }?.asInt(),
        )
    }

    fun find(project: Project): WokwiConfig? =
        project.basePath?.let { File(it, "wokwi.toml") }?.takeIf { it.isFile }?.readText()?.let(::parse)
}

/**
 * Debugging in the Wokwi simulator, through RustRover's own Remote Debug (GDB) configuration: Wokwi exposes a GDB
 * server, not a debug probe, so probe-rs isn't involved. The configuration is created by type id and XML, so the
 * plugin doesn't depend on RustRover's internal run-configuration classes.
 */
object WokwiDebugConfiguration {
    const val REMOTE_DEBUG_TYPE_ID = "RsRemoteRunConfiguration"
    const val WOKWI_PLUGIN_URL = "https://plugins.jetbrains.com/plugin/23826-wokwi-simulator"
    const val DEFAULT_GDB_PORT = 3333

    fun remoteDebugType(): ConfigurationType? =
        ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { it.id == REMOTE_DEBUG_TYPE_ID }

    /** Creates or updates the "Wokwi" Remote Debug configuration. Null if RustRover's Remote Debug isn't available. */
    fun createOrUpdate(project: Project, config: WokwiConfig): RunnerAndConfigurationSettings? {
        val type = remoteDebugType() ?: return null
        val runManager = RunManager.getInstance(project)
        val name = "Wokwi"
        val settings = runManager.findConfigurationByTypeAndName(type, name)
            ?: runManager.createConfiguration(name, type.configurationFactories.first()).also(runManager::addConfiguration)
        val port = config.gdbServerPort ?: DEFAULT_GDB_PORT
        val elf = config.elf?.let { File(it).let { f -> if (f.isAbsolute) f else File(project.basePath ?: "", it) }.path }.orEmpty()
        val element = Element("configuration").apply {
            setAttribute("debuggerKind", "GDB")
            setAttribute("remoteCommand", "localhost:$port")
            setAttribute("symbolFile", elf)
            setAttribute("sysroot", "")
        }
        settings.configuration.readExternal(element)
        runManager.selectedConfiguration = settings
        return settings
    }
}

class CreateWokwiDebugConfigurationAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = WokwiConfigReader.find(project) ?: return
        val created = WokwiDebugConfiguration.createOrUpdate(project, config)
        val group = ProbeRsNotifications.group()
        if (created == null) {
            group.createNotification("RustRover's Remote Debug isn't available", "This IDE has no Remote Debug configuration for Rust.", NotificationType.WARNING).notify(project)
            return
        }
        val port = config.gdbServerPort ?: WokwiDebugConfiguration.DEFAULT_GDB_PORT
        group.createNotification(
            "Wokwi debug configuration ready",
            "\"${created.name}\" attaches GDB to the Wokwi simulator on port $port" +
                (if (config.elf == null) ". Set <code>elf</code> in <code>wokwi.toml</code> so breakpoints resolve." else ".") +
                "<br>1. Start the simulation in the Wokwi plugin, set to wait for the debugger." +
                "<br>2. Debug \"${created.name}\"." +
                "<br>On ESP32 (Xtensa), set Espressif's GDB (<code>xtensa-esp32-elf-gdb</code> from <code>espup</code>) as the debugger in the Rust toolchain settings.",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimple("Get the Wokwi plugin") { BrowserUtil.browse(WokwiDebugConfiguration.WOKWI_PLUGIN_URL) })
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && WokwiConfigReader.find(project) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
