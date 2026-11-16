package dev.proberover.runconfig

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.command.CargoCommandConfiguration

/**
 * Remembers which Cargo configuration was last started with the Debug executor. The Rust plugin's driver provider
 * isn't told which configuration it is debugging, but the platform announces every launch before the debugger starts.
 */
@Service(Service.Level.PROJECT)
class ProbeRsLaunchTracker {
    private data class Launch(val configurationName: String, val atNanos: Long)

    @Volatile private var last: Launch? = null

    fun record(configurationName: String) {
        last = Launch(configurationName, System.nanoTime())
    }

    /** The configuration of the debug launch scheduled in the last minute, if any. Each launch is handed out once. */
    fun consume(): String? {
        val launch = last ?: return null
        last = null
        return launch.configurationName.takeIf { System.nanoTime() - launch.atNanos < 60_000_000_000L }
    }
}

class ProbeRsExecutionListener(private val project: Project) : ExecutionListener {
    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return
        val configuration = env.runProfile as? CargoCommandConfiguration ?: return
        project.service<ProbeRsLaunchTracker>().record(configuration.name)
    }
}

/** The per-configuration overrides for the Cargo configuration named [name], if any were set in Settings. */
fun Project.embeddedRunSettings(name: String): EmbeddedRunSettings? =
    service<ProbeRsProjectService>().getState().perConfiguration[name]
