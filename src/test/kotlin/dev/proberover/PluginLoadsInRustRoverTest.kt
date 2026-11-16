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
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.proberover"))
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
}
