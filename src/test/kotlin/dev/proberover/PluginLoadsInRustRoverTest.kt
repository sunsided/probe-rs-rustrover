package dev.proberover

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.proberover.debugger.ProbeRsDriverConfiguration
import dev.proberover.debugger.ProbeRsDriverConfigurationProvider
import dev.proberover.model.ProbeRsTarget
import org.rust.debugger.RsDebuggerDriverConfigurationProvider

/**
 * Starts a headless RustRover 2026.3 with this plugin installed. Answers spike question #1: can a third-party plugin
 * load classes from the `visibility="internal"` modules `intellij.rustrover.debugger` and `intellij.cidr.debugger.dap`,
 * and is its provider consulted before the Rust plugin's own?
 */
class PluginLoadsInRustRoverTest : BasePlatformTestCase() {

    fun `test plugin is enabled`() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.markusmayer.proberover"))
        assertNotNull("plugin not loaded", plugin)
        assertTrue("plugin disabled", plugin!!.isEnabled)
    }

    fun `test our provider is first in the Rust debugger extension point`() {
        val providers = RsDebuggerDriverConfigurationProvider.EP_NAME.extensionList
        println("driverConfigurationProvider order: " + providers.map { it.javaClass.name })
        assertTrue(providers.first() is ProbeRsDriverConfigurationProvider)
        assertEquals("org.rust.debugger.RsDefaultDebuggerDriverConfigurationProvider", providers.last().javaClass.name)
    }

    fun `test DAP driver configuration builds launch options and the relay command`() {
        val config = ProbeRsDriverConfiguration(project, ProbeRsTarget(chip = "RP2040"), "/usr/bin/probe-rs", false, false)
        assertEquals("probe-rs", config.driverName)
        val launch = config.getDapLaunchOptions(GeneralCommandLine("/fw/target/thumbv6m-none-eabi/debug/blinky"))
        assertEquals("RP2040", launch["chip"])
        val relay = ProbeRsDriverConfiguration.relayCommandLine("/usr/bin/probe-rs")
        println("relay command: " + relay.commandLineString)
        assertTrue(relay.parametersList.list.containsAll(listOf("dev.proberover.relay.RelayMain", "--", "/usr/bin/probe-rs", "dap-server")))
    }

    fun `test host project is not treated as embedded`() {
        // The light test project has no .cargo/config.toml, so the provider must decline and leave GDB/LLDB in charge.
        assertNull(project.getService(dev.proberover.settings.ProbeRsProjectService::class.java).resolveTarget())
    }

    /**
     * Uses the TOML parser through the plugin's classloader. In test mode the plugin's classes also sit on the test
     * classpath, so this doesn't prove isolation in the production IDE; the line-reader fallback covers that case.
     */
    fun `test the TOML parser is reachable through the plugin's classloader`() {
        val loader = PluginManagerCore.getPlugin(PluginId.getId("dev.markusmayer.proberover"))!!.pluginClassLoader!!
        val detector = loader.loadClass("dev.proberover.detect.CargoConfigDetector")
        val instance = detector.getField("INSTANCE").get(null)
        val detect = detector.getMethod("detect", String::class.java, String::class.java)
        // A multi-line array only the real TOML parser understands.
        val result = detect.invoke(instance, "[target.thumbv6m-none-eabi]\nrunner = [\n  \"probe-rs\", \"run\",\n  \"--chip\", \"RP2040\",\n]\n", null)
        val probeRs = result.javaClass.getMethod("getProbeRs").invoke(result)
        assertEquals("RP2040", probeRs.javaClass.getMethod("getChip").invoke(probeRs))
        val toml = loader.loadClass("com.fasterxml.jackson.dataformat.toml.TomlMapper")
        println("TomlMapper loaded by: ${toml.classLoader}")
    }
}
