package dev.markusmayer.proberover.svd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvdCatalogTest {
    private val cmsis = listOf("ATSAMD21G18A.svd", "ATSAMD21G18AU.svd", "ATSAMD21G.svd", "STM32F411.svd", "STM32F41.svd",
        "nrf52840.svd", "rp2040.svd", "ATS.svd")

    @Test
    fun `longest SVD name the chip name starts with`() {
        assertEquals("ATSAMD21G18AU.svd", SvdCatalog.bestMatch("ATSAMD21G18AU", cmsis))
        assertEquals("ATSAMD21G18A.svd", SvdCatalog.bestMatch("ATSAMD21G18A", cmsis))
        assertEquals("STM32F411.svd", SvdCatalog.bestMatch("STM32F411RETx", cmsis))
        assertEquals("nrf52840.svd", SvdCatalog.bestMatch("nRF52840_xxAA", cmsis))
        assertEquals("rp2040.svd", SvdCatalog.bestMatch("RP2040", cmsis))
        assertNull("too short to trust", SvdCatalog.bestMatch("ATS", listOf("ATS.svd")))
        assertNull(SvdCatalog.bestMatch("ESP32C3", cmsis))
    }

    @Test
    fun `all matches, most specific first`() {
        assertEquals(listOf("ATSAMD21G18AU.svd", "ATSAMD21G18A.svd", "ATSAMD21G.svd"), SvdCatalog.matches("ATSAMD21G18AU", cmsis))
    }

    private fun cpu(vararg tags: String) = "<device><cpu>\n      <name>CM0+</name>\n" +
        tags.joinToString("") { "      <$it>x</$it>\n" } + "    </cpu><peripherals/></device>"

    @Test
    fun `missing fpuPresent is added for cores without an FPU, like atsamd-rs's SVDs need`() {
        val atsamd = cpu("revision", "endian", "mpuPresent", "vtorPresent", "nvicPrioBits", "vendorSystickConfig")
        val fixed = SvdCatalog.forProbeRs(atsamd)
        assertNull(fixed.problem)
        assertEquals(true, fixed.xml.contains("<fpuPresent>false</fpuPresent>"))

        val m4 = atsamd.replace("CM0+", "CM4")
        val notFixed = SvdCatalog.forProbeRs(m4)
        assertEquals("an M4 may have an FPU: not guessed", m4, notFixed.xml)
        assertEquals("its <cpu> has no <fpuPresent>", notFixed.problem)

        val complete = cpu("revision", "endian", "mpuPresent", "fpuPresent", "nvicPrioBits", "vendorSystickConfig")
        assertEquals(SvdCatalog.Fixed(complete, null), SvdCatalog.forProbeRs(complete))
        assertEquals("no <cpu> at all is fine", SvdCatalog.Fixed("<device/>", null), SvdCatalog.forProbeRs("<device/>"))
    }

    @Test
    fun `atsamd-rs is asked first for SAM chips, rp-rs for RP2040, cmsis-svd-data for everything`() {
        fun sourcesFor(chip: String) = SvdCatalog.sources.filter { it.appliesTo(chip) }.map { it.name }
        assertEquals(listOf("atsamd-rs/atsamd", "cmsis-svd/cmsis-svd-data"), sourcesFor("ATSAMD21G18AU"))
        assertEquals(listOf("rp-rs/rp2040-pac", "cmsis-svd/cmsis-svd-data"), sourcesFor("RP2040"))
        assertEquals(listOf("cmsis-svd/cmsis-svd-data"), sourcesFor("nRF52840_xxAA"))
        val rp = SvdCatalog.sources.first { it.name == "rp-rs/rp2040-pac" }.parse(null)
        assertEquals("https://raw.githubusercontent.com/rp-rs/rp2040-pac/main/svd/rp2040.svd", rp["rp2040.svd"])
    }

    @Test
    fun `GitHub listings become download URLs`() {
        val contents = """[{"name":"ATSAMD21G18A.svd","download_url":"https://raw.githubusercontent.com/atsamd-rs/atsamd/master/svd/ATSAMD21G18A.svd"},
            {"name":"devices","download_url":null}]"""
        assertEquals(mapOf("ATSAMD21G18A.svd" to "https://raw.githubusercontent.com/atsamd-rs/atsamd/master/svd/ATSAMD21G18A.svd"),
            SvdCatalog.parseContentsListing(contents))

        val tree = """{"tree":[{"path":"data/Atmel/ATSAMD21G18AU.svd"},{"path":"data/Atmel"},{"path":"README.md"},
            {"path":"data/Nordic/nrf52840.svd"}],"truncated":false}"""
        assertEquals(
            mapOf(
                "ATSAMD21G18AU.svd" to "https://raw.githubusercontent.com/cmsis-svd/cmsis-svd-data/main/data/Atmel/ATSAMD21G18AU.svd",
                "nrf52840.svd" to "https://raw.githubusercontent.com/cmsis-svd/cmsis-svd-data/main/data/Nordic/nrf52840.svd",
            ),
            SvdCatalog.parseCmsisTree(tree),
        )
    }
}
