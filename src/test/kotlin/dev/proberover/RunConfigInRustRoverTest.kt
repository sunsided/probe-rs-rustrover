package dev.proberover

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.proberover.runconfig.EmbeddedRunSettings
import dev.proberover.runconfig.ProbeRsExecutionListener
import dev.proberover.runconfig.ProbeRsLaunchTracker
import dev.proberover.runconfig.RttMode
import dev.proberover.runconfig.embeddedRunSettings
import dev.proberover.settings.ProbeRsConfigurable
import dev.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.command.CargoCommandConfigurationType

/** Headless RustRover tests for per-run-configuration settings. */
class RunConfigInRustRoverTest : BasePlatformTestCase() {

    private object StubRunner : ProgramRunner<RunnerSettings> {
        override fun getRunnerId() = "stub"
        override fun canRun(executorId: String, profile: RunProfile) = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private fun cargoConfiguration(name: String): RunnerAndConfigurationSettings {
        val type = ConfigurationTypeUtil.findConfigurationType(CargoCommandConfigurationType::class.java)
        return RunManager.getInstance(project).createConfiguration(name, type.factory).also {
            RunManager.getInstance(project).addConfiguration(it)
        }
    }

    private val settings get() = project.getService(ProbeRsProjectService::class.java).getState()

    override fun tearDown() {
        try {
            settings.perConfiguration.clear()
            RunManager.getInstance(project).allSettings.toList().forEach(RunManager.getInstance(project)::removeConfiguration)
        } finally {
            super.tearDown()
        }
    }

    fun `test debug launches of Cargo configurations are tracked once, run launches are ignored`() {
        val blinky = cargoConfiguration("Run blinky")
        settings.perConfiguration["Run blinky"] = EmbeddedRunSettings(chip = "esp32c3")
        val listener = ProbeRsExecutionListener(project)
        val tracker = project.getService(ProbeRsLaunchTracker::class.java)
        tracker.consume()

        listener.processStartScheduled(DefaultRunExecutor.EXECUTOR_ID, ExecutionEnvironment(DefaultRunExecutor.getRunExecutorInstance(), StubRunner, blinky, project))
        assertNull(tracker.consume())

        listener.processStartScheduled(DefaultDebugExecutor.EXECUTOR_ID, ExecutionEnvironment(DefaultDebugExecutor.getDebugExecutorInstance(), StubRunner, blinky, project))
        val name = tracker.consume()
        assertEquals("Run blinky", name)
        assertEquals("esp32c3", project.embeddedRunSettings(name!!)?.chip)
        assertNull(tracker.consume())
    }

    fun `test settings table edits per-configuration overrides`() {
        cargoConfiguration("Run blinky")
        settings.perConfiguration["Run blinky"] = EmbeddedRunSettings(chip = "RP2040")

        val configurable = ProbeRsConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)
        val model = configurable.perConfigurationTable.model
        assertEquals(1, model.rowCount)
        assertEquals("Run blinky", model.getValueAt(0, 0))
        assertEquals("RP2040", model.getValueAt(0, 1))

        model.setValueAt(false, 0, 5)            // Flash off
        model.setValueAt(RttMode.DEFMT, 0, 6)    // RTT as defmt
        model.addRow(dev.proberover.settings.PerConfigurationTable.Row("Run button", EmbeddedRunSettings(probe = "1366:1051")))
        model.addRow(dev.proberover.settings.PerConfigurationTable.Row("Run empty", EmbeddedRunSettings())) // defaults only: dropped
        assertTrue(configurable.isModified)
        configurable.apply()

        assertEquals(
            mapOf(
                "Run blinky" to EmbeddedRunSettings(chip = "RP2040", flash = false, rtt = RttMode.DEFMT),
                "Run button" to EmbeddedRunSettings(probe = "1366:1051"),
            ),
            settings.perConfiguration,
        )
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }
}
