package dev.proberover

import com.google.gson.JsonObject
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.proberover.defmt.DefmtConsoleFilter
import dev.proberover.session.ProbeRsSessionUi
import dev.proberover.settings.ProbeRsConfigurable
import dev.proberover.settings.ProbeRsProjectService

/** Headless RustRover tests for the UI pieces that don't need a debug session. */
class UiInRustRoverTest : BasePlatformTestCase() {

    fun `test defmt filter links locations to project files and colors levels`() {
        val main = myFixture.addFileToProject("src/main.rs", "fn main() {\n    let x = 1;\n    defmt::info!(\"hi\");\n}\n").virtualFile
        val filter = DefmtConsoleFilter(project)

        val location = "└─ blinky::main @ src/main.rs:3\n"
        val result = filter.applyFilter(location, 100 + location.length)!!
        val link = result.resultItems.single()
        val info = link.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(main, info.virtualFile)
        assertEquals("src/main.rs:3", (" ".repeat(100) + location).substring(link.highlightStartOffset, link.highlightEndOffset))

        val level = filter.applyFilter("0.000123 WARN  low battery\n", 27)!!.resultItems.single()
        assertNull(level.hyperlinkInfo)
        assertNotNull(level.highlightAttributes)
        assertEquals(9, level.highlightStartOffset)
        assertEquals(13, level.highlightEndOffset)
    }

    fun `test unresolvable locations are not links`() {
        val result = DefmtConsoleFilter(project).applyFilter("└─ x @ src/does_not_exist.rs:1\n", 31)
        assertNull(result)
    }

    fun `test settings page applies and resets`() {
        val settings = project.getService(ProbeRsProjectService::class.java).getState()
        val configurable = ProbeRsConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)
        settings.chipOverride = "nRF52840_xxAA"
        configurable.reset()
        configurable.apply()
        assertEquals("nRF52840_xxAA", settings.chipOverride)
        configurable.disposeUIResources()
        settings.chipOverride = null
    }

    fun `test session UI tolerates events without a debug session`() {
        val ui = ProbeRsSessionUi(dev.proberover.session.ProbeRsSession(project, dev.proberover.model.ProbeRsTarget(chip = "RP2040"), "Run blinky"))
        fun ev(kind: String, fill: JsonObject.() -> Unit) = JsonObject().apply { addProperty("kind", kind); fill() }
        ui.onEvent("rtt-channel", ev("rtt-channel") { addProperty("channel", 0); addProperty("name", "defmt") })
        ui.onEvent("rtt-data", ev("rtt-data") { addProperty("channel", 0); addProperty("data", "0.1 INFO hi\npartial") })
        ui.onEvent("progress-start", ev("progress-start") { addProperty("progressId", "1"); addProperty("title", "Flashing device") })
        ui.onEvent("progress-update", ev("progress-update") { addProperty("progressId", "1"); addProperty("percentage", 50.0) })
        ui.onEvent("progress-end", ev("progress-end") { addProperty("progressId", "1") })
        ui.onEvent("launch-failed", ev("launch-failed") { addProperty("message", "No connected probes were found.") })
        ui.onClosed()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
