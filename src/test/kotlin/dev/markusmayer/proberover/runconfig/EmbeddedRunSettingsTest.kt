package dev.markusmayer.proberover.runconfig

import com.intellij.util.xmlb.XmlSerializer
import dev.markusmayer.proberover.model.ProbeRsTarget
import dev.markusmayer.proberover.model.RttDataFormat
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRunSettingsTest {

    @Test
    fun `per-configuration settings survive IntelliJ's XML serialization`() {
        val state = ProbeRsProjectService.SettingsState().apply {
            chipOverride = "RP2040"
            perConfiguration["Run blinky"] = EmbeddedRunSettings(chip = "RP2350", flash = false, rtt = RttMode.STRING)
            perConfiguration["Run button"] = EmbeddedRunSettings(svdFile = "rp2040.svd", connectUnderReset = true)
        }
        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, ProbeRsProjectService.SettingsState::class.java)
        assertEquals("RP2040", restored.chipOverride)
        assertEquals(state.perConfiguration, restored.perConfiguration)
    }

    @Test
    fun `run configuration settings override the project target`() {
        val project = ProbeRsTarget(chip = "RP2040", probe = "2e8a:000c", rttFormat = RttDataFormat.DEFMT, connectUnderReset = true)

        assertEquals(project, EmbeddedRunSettings().applyTo(project))

        val overridden = EmbeddedRunSettings(chip = "RP2350", flash = false, rtt = RttMode.STRING).applyTo(project)
        assertEquals("RP2350", overridden.chip)
        assertEquals("2e8a:000c", overridden.probe)
        assertFalse(overridden.flash)
        assertEquals(RttDataFormat.STRING, overridden.rttFormat)
        assertTrue("connect-under-reset from the runner stays on", overridden.connectUnderReset)

        assertFalse(EmbeddedRunSettings(rtt = RttMode.DISABLED).applyTo(project).rttEnabled)
    }
}
