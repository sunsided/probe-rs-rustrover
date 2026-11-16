package dev.proberover.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.proberover.detect.CargoConfigDetector
import dev.proberover.detect.DetectedProject
import dev.proberover.model.ProbeRsTarget
import dev.proberover.runconfig.EmbeddedRunSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Project-level settings and detection. In the spike this is the only source of launch settings
 * (fallback 3 of spike item S2): the extension point doesn't tell us which run configuration is being debugged.
 */
@Service(Service.Level.PROJECT)
@State(name = "ProbeRsSettings", storages = [Storage("probe-rs.xml")])
class ProbeRsProjectService(private val project: Project) : PersistentStateComponent<ProbeRsProjectService.SettingsState> {

    class SettingsState {
        var enabled: Boolean = true
        var chipOverride: String? = null
        var probeRsPath: String? = null
        var svdFile: String? = null
        /** Overrides per Cargo run configuration, keyed by configuration name. */
        var perConfiguration: MutableMap<String, EmbeddedRunSettings> = LinkedHashMap()
    }

    private var state = SettingsState()
    override fun getState() = state
    override fun loadState(s: SettingsState) = XmlSerializerUtil.copyBean(s, state)

    fun detect(): DetectedProject? {
        val base = project.basePath?.let(Path::of) ?: return null
        val config = listOf(".cargo/config.toml", ".cargo/config").map(base::resolve).firstOrNull { it.isRegularFile() }
        val cargoToml = base.resolve("Cargo.toml").takeIf { it.isRegularFile() }
        return CargoConfigDetector.detect(config?.readText(), cargoToml?.readText())
    }

    /**
     * The target to debug with probe-rs, or null when this project should use the normal host debugger.
     * Precedence: [run] (the Cargo run configuration's probe-rs tab) > project settings > `.cargo/config.toml`.
     */
    fun resolveTarget(run: EmbeddedRunSettings? = null): ProbeRsTarget? {
        if (!state.enabled) return null
        val detected = detect() ?: return null
        val chip = run?.chip?.takeIf { it.isNotBlank() } ?: state.chipOverride?.takeIf { it.isNotBlank() }
        val base = detected.probeRs ?: chip?.takeIf { detected.isEmbeddedTarget }?.let { ProbeRsTarget(chip = it) } ?: return null
        val projectLevel = base.copy(
            chip = state.chipOverride?.takeIf { it.isNotBlank() } ?: base.chip,
            svdFile = state.svdFile?.takeIf { it.isNotBlank() } ?: base.svdFile,
        )
        return run?.applyTo(projectLevel) ?: projectLevel
    }

    fun probeRsExecutable(): String? {
        state.probeRsPath?.takeIf { it.isNotBlank() && Path.of(it).exists() }?.let { return it }
        val exe = if (isWindows()) "probe-rs.exe" else "probe-rs"
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } +
            Path.of(System.getProperty("user.home"), ".cargo", "bin").toString()
        return dirs.asSequence().map { Path.of(it, exe) }.firstOrNull { Files.isExecutable(it) }?.toString()
    }

    fun notifyProbeRsMissing() {
        NotificationGroupManager.getInstance().getNotificationGroup("probe-rs")
            .createNotification(
                "probe-rs not found",
                "Debugging this embedded target needs probe-rs. Install it with <code>cargo binstall probe-rs-tools</code> " +
                    "or the installer from probe.rs, then debug again.",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
}
