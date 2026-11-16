package dev.markusmayer.proberover.svd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinRegistersTest {
    private fun field(name: String) = SvdField(name, null, 0, 32, Access.READ_WRITE)
    private fun register(name: String, address: Long) = SvdRegister(name, null, address, 32, Access.READ_WRITE, 0, listOf(field(name.trimEnd('0', '1'))))

    // ATSAMD21G18A: PORT at 0x41004400, DIRSET of group 0 at +0x08, OUT of group 1 at +0x80 + 0x10.
    private val port = SvdPeripheral("PORT", "Port Module", "PORT", 0x41004400, listOf(
        register("DIRSET0", 0x41004408), register("OUT1", 0x41004490),
    ))
    private val index = SvdFieldIndex(SvdDevice("ATSAMD21G18A", listOf(port)))

    @Test
    fun `pins of the port group, from the register's address`() {
        assertEquals("PA20" to null, index.meaning(0x41004408, 0, 32, 1L shl 20))
        assertEquals("PB3 PB8" to null, index.meaning(0x41004490, 0, 32, (1L shl 3) or (1L shl 8)))
        assertEquals("none" to null, index.meaning(0x41004408, 0, 32, 0))
        assertEquals("PA0 PA1 PA2 PA3 PA4 PA5 PA6 PA7 +24 more" to null, index.meaning(0x41004408, 0, 32, 0xFFFFFFFFL))
    }

    @Test
    fun `RP2040 - GPIO and QSPI pins of SIO, from rp-rs's rp2040_svd`() {
        // SIO and two of its registers as rp2040.svd defines them (descriptions left out).
        val svd = """<device><name>RP2040</name><peripherals><peripheral><name>SIO</name><baseAddress>0xd0000000</baseAddress><registers>
            <register> <name>GPIO_OUT</name> <addressOffset>0x0010</addressOffset> <resetValue>0x00000000</resetValue> <fields> <field> <name>GPIO_OUT</name> <bitRange>[29:0]</bitRange> <access>read-write</access> </field> </fields> </register>
            <register> <name>GPIO_HI_OUT</name> <addressOffset>0x0030</addressOffset> <resetValue>0x00000000</resetValue> <fields> <field> <name>GPIO_HI_OUT</name> <bitRange>[5:0]</bitRange> <access>read-write</access> </field> </fields> </register>
            </registers></peripheral></peripherals></device>"""
        val rp = SvdFieldIndex(SvdParser.parse(svd.byteInputStream()))
        // probe-rs reports these fields as 0xd0000010:0..30 and 0xd0000030:0..6.
        assertEquals("GPIO25" to null, rp.meaning(0xd0000010, 0, 30, 1L shl 25))
        assertEquals("QSPI_SS QSPI_SD0" to null, rp.meaning(0xd0000030, 0, 6, 0b110))
    }

    @Test
    fun `only PORT's pin registers`() {
        val other = SvdPeripheral("TC3", null, null, 0x42002C00, listOf(SvdRegister("COUNT", null, 0x42002C10, 32, Access.READ_WRITE, 0, listOf(field("OUT")))))
        assertNull(SvdFieldIndex(SvdDevice("x", listOf(other))).meaning(0x42002C10, 0, 32, 1))
    }
}
