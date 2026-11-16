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
