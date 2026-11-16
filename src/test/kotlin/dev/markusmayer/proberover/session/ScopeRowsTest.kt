package dev.markusmayer.proberover.session

import dev.markusmayer.proberover.session.ScopeRows.Kind
import dev.markusmayer.proberover.session.ScopeRows.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** probe-rs 0.32's texts, from a real session on an ATSAMD21G18A. */
class ScopeRowsTest {
    @Test
    fun `peripheral groups and peripherals - the description moves out of the value`() {
        assertEquals(Row("AC", Kind.GROUP, null, description = "Analog Comparators"), ScopeRows.row("AC", "Analog Comparators", "Peripheral Group"))
        assertEquals(Row("PORT", Kind.PERIPHERAL, null, description = "Port Module"), ScopeRows.row("PORT", "Port Module", "Peripheral"))
    }

    @Test
    fun `registers - value, description, address`() {
        assertEquals(
            Row("EVCTRL", Kind.REGISTER, "0x0000", description = "Event Control", location = "0x42004402"),
            ScopeRows.row("EVCTRL", "0x0000 @ 0x42004402", "Event Control"),
        )
        assertEquals(
            Row("CTRLA", Kind.REGISTER, null, notRead = true, description = "Control A", location = "0x42004400"),
            ScopeRows.row("CTRLA", "Register cannot be read without side effects @ 0x42004400", "Control A"),
        )
        assertEquals(Row("R1/a2/r2", Kind.REGISTER, "0x000003e8"), ScopeRows.row("R1/a2/r2", "0x000003e8", "Platform Register"))
    }

    @Test
    fun `fields - value in hex or as a single bit, bit range with the address`() {
        assertEquals(
            Row("PMUXO", Kind.FIELD, "0xA", description = "Peripheral Multiplexing Odd", location = "0x41004436 [4..8]"),
            ScopeRows.row("PMUXO", "1010 @ 0x41004436:4..8", "Peripheral Multiplexing Odd"),
        )
        assertEquals("1", ScopeRows.row("INEN", "1 @ 0x41004458:1..2", "Input Enable").value)
        val notRead = ScopeRows.row("DRVSTR", "Field cannot be read without side effects @ 0x41004458", "Output Driver Strength Selection")
        assertTrue(notRead.notRead)
    }

    @Test
    fun `fields with enumerated values in the SVD - the value's name and meaning`() {
        val index = dev.markusmayer.proberover.svd.SvdFieldIndex(
            dev.markusmayer.proberover.svd.SvdDevice("ATSAMD21G18A", listOf(dev.markusmayer.proberover.svd.SvdPeripheral(
                "PORT", "Port Module", null, 0x41004400,
                listOf(dev.markusmayer.proberover.svd.SvdRegister("PMUX6", null, 0x41004436, 8, dev.markusmayer.proberover.svd.Access.READ_WRITE, 0,
                    listOf(dev.markusmayer.proberover.svd.SvdField("PMUXE", "Peripheral Multiplexing Even", 0, 4, dev.markusmayer.proberover.svd.Access.READ_WRITE,
                        mapOf(6L to ("G" to "Peripheral function G selected")))))),
            ))),
        )
        val decoder = ScopeRows.Decoder { address, offset, width, value -> index.field(address, offset, width)?.values?.get(value) }
        assertEquals(
            Row("PMUXE", Kind.FIELD, "G", description = "Peripheral function G selected", location = "0x41004436 [0..4]",
                raw = "0x6", fieldDescription = "Peripheral Multiplexing Even"),
            ScopeRows.row("PMUXE", "0110 @ 0x41004436:0..4", "Peripheral Multiplexing Even", decoder = decoder),
        )
        assertEquals("no enumerated value for it: the number", "0xF",
            ScopeRows.row("PMUXE", "1111 @ 0x41004436:0..4", "Peripheral Multiplexing Even", decoder = decoder).value)
        assertEquals("another field at the same address", "0x6",
            ScopeRows.row("PMUXO", "0110 @ 0x41004436:4..8", "Peripheral Multiplexing Odd", decoder = decoder).value)
    }

    @Test
    fun `statics - one line, shortened, with the Rust type`() {
        val long = "[Vector; 28] = [\n\tVector {\n\t\t_handler: *raw unsafe extern \"C\" fn() = *raw unsafe extern \"C\" fn() @ 0x00000040,\n" +
            "\t\t_reserved: u32 = 4841\n\t},\n".repeat(10) + "]"
        val row = ScopeRows.row("__INTERRUPTS", long, "[Vector; 28]")
        assertEquals(Kind.VALUE, row.kind)
        assertFalse(row.value!!.contains("\n"))
        assertEquals(120, row.value!!.length)
        assertEquals("[Vector; 28]", row.description)
        assertEquals(Row("DEVICE_PERIPHERALS", Kind.VALUE, "true", description = "bool"), ScopeRows.row("DEVICE_PERIPHERALS", "true", "bool"))
        assertEquals(Kind.NAMESPACE, ScopeRows.row("blinky_basic", "", "namespace").kind)
        assertTrue(ScopeRows.isNoise("<&u32 as core::fmt::Debug>::{vtable}", "< The value of this variable may have been optimized out of the debug info, by the compiler. >"))
    }
}
