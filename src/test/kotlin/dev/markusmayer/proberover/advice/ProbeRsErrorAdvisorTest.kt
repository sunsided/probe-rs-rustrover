package dev.markusmayer.proberover.advice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages are the `#[error(...)]` texts from probe-rs, sometimes wrapped the way the DAP server reports them. */
class ProbeRsErrorAdvisorTest {

    @Test
    fun `probe problems`() {
        assertEquals("No debug probe found", ProbeRsErrorAdvisor.advise("No connected probes were found.")?.title)
        assertEquals("Several debug probes are connected", ProbeRsErrorAdvisor.advise("2 probes were found: ST-Link V2, J-Link")?.title)
        assertEquals("The debug probe is busy", ProbeRsErrorAdvisor.advise("Failed to open probe: Probe is already in use")?.title)
    }

    @Test
    fun `usb permissions depend on the OS`() {
        val msg = "An operation could not be performed because it lacked the permission to do so: open"
        val linux = ProbeRsErrorAdvisor.advise(msg, osName = "Linux")!!
        assertTrue(linux.hint.contains("udev"))
        assertNotNull(linux.link)
        assertTrue(ProbeRsErrorAdvisor.advise(msg, osName = "Windows 11")!!.hint.contains("WinUSB"))
    }

    @Test
    fun `chip and connection problems`() {
        assertEquals("Unknown chip name", ProbeRsErrorAdvisor.advise("The chip 'RP2041' was not found in the database.")?.title)
        assertTrue(ProbeRsErrorAdvisor.advise("Connecting to the chip was unsuccessful: core 0 locked up")!!.hint.contains("--connect-under-reset"))
        assertTrue(ProbeRsErrorAdvisor.advise("Failed to erase the whole chip.")!!.hint.contains("--allow-erase-all"))
    }

    @Test
    fun `unknown errors get no advice`() {
        assertNull(ProbeRsErrorAdvisor.advise("Something entirely different happened"))
    }
}
