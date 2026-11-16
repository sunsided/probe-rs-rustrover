package dev.markusmayer.proberover.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.markusmayer.proberover.detect.CargoConfigDetector
import dev.markusmayer.proberover.detect.DetectedProject
import dev.markusmayer.proberover.model.ProbeRsTarget
import dev.markusmayer.proberover.runconfig.EmbeddedRunSettings
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
        /** `--probe` selector (`VID:PID[:SERIAL]`) when several probes are connected. */
        var probe: String? = null
        var skipUnchangedFlash: Boolean = true
        /** A remote probe-rs server (`probe-rs serve`) instead of a locally connected probe; its token is in the password safe. */
        var remoteHost: String? = null
        /** The one-time "embedded project detected" notification was shown. */
        var introShown: Boolean = false
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
            probe = state.probe?.takeIf { it.isNotBlank() } ?: base.probe,
            skipUnchangedFlash = state.skipUnchangedFlash,
        )
        return run?.applyTo(projectLevel) ?: projectLevel
    }

    /** Extra `probe-rs dap-server` arguments: `--host`/`--token` for a remote probe-rs server. */
    fun serverArguments(): List<String> {
        val host = state.remoteHost?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf("--host", host) + (remoteToken?.let { listOf("--token", it) } ?: emptyList())
    }

    private val tokenAttributes get() = CredentialAttributes(generateServiceName("probe-rs", "remote token ${project.locationHash}"))

    /** The remote probe-rs server's token, kept in the IDE's password safe rather than in the project files. */
    var remoteToken: String?
        get() = PasswordSafe.instance.getPassword(tokenAttributes)?.takeIf { it.isNotEmpty() }
        set(value) = PasswordSafe.instance.setPassword(tokenAttributes, value?.takeIf { it.isNotEmpty() })

    /** `.svd` files in the project (a few levels deep, skipping build output), for picking the chip's SVD file. */
    fun findSvdFiles(maxDepth: Int = 4): List<File> {
        val base = project.basePath?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        val skip = setOf("target", ".git", ".idea", "node_modules", ".gradle")
        return base.walkTopDown()
            .onEnter { it == base || (it.name !in skip && it.toPath().nameCount - base.toPath().nameCount <= maxDepth) }
            .filter { it.isFile && it.extension.equals("svd", ignoreCase = true) }
            .take(50)
            .sortedBy { it.path }
            .toList()
    }

    fun probeRsExecutable(): String? {
        state.probeRsPath?.takeIf { it.isNotBlank() && Path.of(it).exists() }?.let { return it }
        val exe = if (isWindows()) "probe-rs.exe" else "probe-rs"
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } +
            Path.of(System.getProperty("user.home"), ".cargo", "bin").toString()
        return dirs.asSequence().map { Path.of(it, exe) }.firstOrNull { Files.isExecutable(it) }?.toString()
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
}
