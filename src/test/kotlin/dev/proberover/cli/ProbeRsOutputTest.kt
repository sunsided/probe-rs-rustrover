package dev.proberover.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample outputs follow the print statements in probe-rs's cmd/list.rs, cmd/chip.rs and the probe Display impl. */
class ProbeRsOutputTest {

    @Test
    fun `probe list`() {
        val probes = ProbeRsOutput.probes(
            """
            The following debug probes were found:
            [0]: Debug Probe (CMSIS-DAP) -- 2e8a:000c:E6614103E7728F24 (CMSIS-DAP)
            [1]: STLink V3 -- 0483:374e-1:003500293133510B37333632 (ST-LINK)
            [2]: J-Link -- 1366:0105: (J-Link) (inaccessible)
            """.trimIndent(),
        )
        assertEquals(3, probes.size)
        assertEquals("Debug Probe (CMSIS-DAP)", probes[0].name)
        assertEquals("2e8a:000c:E6614103E7728F24", probes[0].selector)
        assertEquals("0483:374e:003500293133510B37333632", probes[1].selector)
        assertEquals("ST-LINK", probes[1].driver)
        assertEquals("1366:0105", probes[2].selector)
        assertNull(probes[2].serial)
        assertFalse(probes[2].accessible)
        assertTrue(probes[2].label.endsWith("no permission"))
        assertEquals(emptyList<ProbeInfo>(), ProbeRsOutput.probes("No debug probes were found."))
    }

    @Test
    fun `chip list`() {
        val chips = ProbeRsOutput.chips(
            """
            Available chips:
            RP2040
                Variants:
                    RP2040
                    RP2040_SELFDEBUG
            nRF52840
                Variants:
                    nRF52840_xxAA
            """.trimIndent(),
        )
        assertEquals(listOf("RP2040", "RP2040_SELFDEBUG", "nRF52840_xxAA"), chips)
    }

    @Test
    fun `version and minimum`() {
        assertEquals(Triple(0, 29, 1), ProbeRsOutput.version("probe-rs 0.29.1 (git commit: v0.29.1-12-gabc)"))
        assertNull(ProbeRsOutput.version("command not found"))
        assertTrue(ProbeRsTools.isTooOld(Triple(0, 23, 9)))
        assertFalse(ProbeRsTools.isTooOld(Triple(0, 24, 0)))
        assertFalse(ProbeRsTools.isTooOld(Triple(1, 0, 0)))
    }

    @Test
    fun `chip info`() {
        val info = ProbeRsOutput.chipInfo(
            """
            RP2040
            Cores (2):
                - core0 (Armv6m)
                - core1 (Armv6m)
            NVM: 0x10000000..0x11000000 (16 MiB)
            RAM: 0x20000000..0x20042000 (264 KiB)
            RAM: 0x51000000..0x51001000 (4 KiB)
            """.trimIndent(),
        )
        assertEquals(listOf("core0", "core1"), info.cores)
        assertEquals(listOf(0x20000000L until 0x20042000L, 0x51000000L until 0x51001000L), info.ram)
    }
}
