package dev.proberover

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.cidr.execution.debugger.backend.dap.DapRequestType
import dev.proberover.cli.ProbeRsTools
import dev.proberover.debugger.ProbeRsDriverConfiguration
import dev.proberover.model.ProbeRsTarget
import dev.proberover.settings.ProbeRsProjectService
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** probe-rs command-line integration, attach mode and remote-server arguments, headless. */
class CliInRustRoverTest : BasePlatformTestCase() {

    /** A stand-in `probe-rs` that prints what the real one prints for `list`, `chip list` and `--version`. */
    private fun fakeProbeRs(): String {
        val dir = Files.createTempDirectory("fake-probe-rs").toFile().apply { deleteOnExit() }
        val script = File(dir, "probe-rs")
        script.writeText(
            """
            #!/bin/sh
            case "${'$'}1 ${'$'}2" in
              "list "*) printf 'The following debug probes were found:\n[0]: Debug Probe (CMSIS-DAP) -- 2e8a:000c:E661 (CMSIS-DAP)\n' ;;
              "chip list") printf 'Available chips:\nRP2040\n    Variants:\n        RP2040\n        RP2040_SELFDEBUG\n' ;;
              "--version "*) echo "probe-rs 0.23.0 (git commit: v0.23.0)" ;;
              *) exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )
        script.setExecutable(true)
        return script.path
    }

    private fun <T> offEdt(block: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T>(block).get(30, TimeUnit.SECONDS)

    fun `test probe-rs commands are run and parsed`() {
        val probeRs = fakeProbeRs()
        val tools = ProbeRsTools.get()
        assertEquals(listOf("RP2040", "RP2040_SELFDEBUG"), offEdt { tools.chips(probeRs) })
        assertEquals("2e8a:000c:E661", offEdt { tools.probes(probeRs) }.single().selector)
        val version = offEdt { tools.version(probeRs) }!!
        assertTrue("0.23 is below the minimum", ProbeRsTools.isTooOld(version))
    }

    fun `test attach uses a DAP attach request without flashing`() {
        val target = ProbeRsTarget(chip = "RP2040")
        val launch = ProbeRsDriverConfiguration(project, target, "probe-rs", false, false)
        val attach = ProbeRsDriverConfiguration(project, target, "probe-rs", false, false, attach = true)
        assertEquals(DapRequestType.LAUNCH, launch.startRequestType)
        assertEquals(DapRequestType.ATTACH, attach.startRequestType)
        @Suppress("UNCHECKED_CAST")
        val flashing = attach.getDapAttachOptions(com.intellij.execution.configurations.GeneralCommandLine("/fw/blinky"))["flashingConfig"] as Map<String, Any>
        assertEquals(false, flashing["flashingEnabled"])
    }

    fun `test remote server arguments use the token from the password safe`() {
        val service = project.getService(ProbeRsProjectService::class.java)
        assertEquals(emptyList<String>(), service.serverArguments())
        service.getState().remoteHost = "ws://pi.local:3000"
        service.remoteToken = "s3cret"
        try {
            assertEquals(listOf("--host", "ws://pi.local:3000", "--token", "s3cret"), service.serverArguments())
        } finally {
            service.getState().remoteHost = null
            service.remoteToken = null
        }
        assertNull(service.remoteToken)
    }
}
