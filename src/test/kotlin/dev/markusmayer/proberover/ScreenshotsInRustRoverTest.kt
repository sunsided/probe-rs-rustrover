package dev.markusmayer.proberover

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.markusmayer.proberover.defmt.DefmtConsoleFilter
import dev.markusmayer.proberover.defmt.DefmtLineParser
import dev.markusmayer.proberover.runconfig.EmbeddedRunSettings
import dev.markusmayer.proberover.runconfig.RttMode
import dev.markusmayer.proberover.session.ProbeRsSessionUi
import dev.markusmayer.proberover.settings.ProbeRsConfigurable
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import org.rust.cargo.runconfig.command.CargoCommandConfigurationType
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/** Paints the plugin's UI headlessly into build/screenshots, to review layout without a board or a display. */
class ScreenshotsInRustRoverTest : BasePlatformTestCase() {

    private val out = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }

    private fun shoot(component: JComponent, width: Int, height: Int, name: String) {
        component.setSize(width, height)
        layoutRecursively(component)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        component.printAll(g)
        g.dispose()
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    private fun layoutRecursively(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layoutRecursively)
    }

    fun `test settings page`() {
        val type = ConfigurationTypeUtil.findConfigurationType(CargoCommandConfigurationType::class.java)
        val runManager = RunManager.getInstance(project)
        listOf("Run blinky", "Run button").forEach { runManager.addConfiguration(runManager.createConfiguration(it, type.factory)) }
        val settings = project.getService(ProbeRsProjectService::class.java).getState()
        settings.svdFile = "svd/rp2040.svd"
        settings.perConfiguration["Run blinky"] = EmbeddedRunSettings(chip = "RP2040", rtt = RttMode.DEFMT)
        settings.perConfiguration["Run button"] = EmbeddedRunSettings(probe = "2e8a:000c", flash = false)
        try {
            val configurable = ProbeRsConfigurable(project)
            val component = configurable.createComponent()!! as JComponent
            configurable.reset()
            shoot(component, 820, 760, "settings")
            configurable.disposeUIResources()
        } finally {
            settings.svdFile = null
            settings.perConfiguration.clear()
            runManager.allSettings.toList().forEach(runManager::removeConfiguration)
        }
    }

    fun `test RTT console`() {
        myFixture.addFileToProject("src/main.rs", "fn main() {}\n")
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
            addFilter(DefmtConsoleFilter(project))
        }.console as ConsoleViewImpl
        Disposer.register(testRootDisposable, console)
        val component = console.component as JComponent

        listOf(
            "0.000000 INFO  Embassy initialized, starting tasks\n",
            "└─ blinky::____embassy_main_task::{async_fn#0} @ src/main.rs:24\n",
            "0.000488 DEBUG LED on (pin 25)\n",
            "└─ blinky::blink::{async_fn#0} @ src/main.rs:41\n",
            "0.250732 TRACE timer tick 1\n",
            "0.500977 WARN  Button bounced, ignoring\n",
            "└─ blinky::button::{async_fn#0} @ src/main.rs:57\n",
            "1.001221 ERROR I2C NACK from 0x3c (display)\n",
            "└─ blinky::display::{async_fn#0} @ src/main.rs:73\n",
        ).forEach { console.print(it, ProbeRsSessionUi.contentType(DefmtLineParser.parse(it))) }
        console.flushDeferredText()
        console.waitAllRequests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        fun links() = console.editor!!.markupModel.allHighlighters.filter { com.intellij.execution.impl.EditorHyperlinkSupport.getHyperlinkInfo(it) != null }
        val deadline = System.currentTimeMillis() + 10_000
        while (links().size < 4 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        val links = links()
        println("hyperlinks in RTT console: ${links.size}")
        assertEquals("every src/main.rs:N location is a link", 4, links.size)
        console.editor?.let { assertTrue(it.document.text.contains("I2C NACK")) }
        shoot(component, 820, 260, "rtt-console")
    }
}
