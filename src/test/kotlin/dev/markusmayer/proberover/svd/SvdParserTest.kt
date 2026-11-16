package dev.markusmayer.proberover.svd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SvdParserTest {
    private val device = SvdParser.parse(javaClass.getResourceAsStream("/test-device.svd")!!)
    private fun peripheral(name: String) = device.peripherals.single { it.name == name }
    private fun register(p: String, r: String) = peripheral(p).registers.single { it.name == r }

    @Test
    fun `device, peripherals and derivedFrom`() {
        assertEquals("TESTCHIP", device.name)
        assertEquals(listOf("GPIOA", "GPIOB"), device.peripherals.map { it.name })
        assertEquals("General purpose I/O port A", peripheral("GPIOA").description)
        val b = peripheral("GPIOB")
        assertEquals(0x40020400L, b.baseAddress)
        assertEquals(peripheral("GPIOA").registers.map { it.name }, b.registers.map { it.name })
        assertEquals(0x40020400L + 0x10, register("GPIOB", "IDR").address)
        assertEquals("GPIO", b.groupName)
    }

    @Test
    fun `register arrays, clusters and inherited properties`() {
        assertEquals(
            listOf("MODER", "IDR", "AFRL", "AFRH", "CH0.CTRL", "CH1.CTRL"),
            peripheral("GPIOA").registers.map { it.name },
        )
        assertEquals(0x40020024L, register("GPIOA", "AFRH").address)
        assertEquals(0x40020050L, register("GPIOA", "CH1.CTRL").address)
        assertEquals(16, register("GPIOA", "CH1.CTRL").sizeBits)
        assertEquals(Access.READ_ONLY, register("GPIOA", "IDR").access)
        assertFalse(register("GPIOA", "IDR").fields.single().access.writable)
        assertEquals(0xA8000000L, register("GPIOA", "MODER").resetValue)
        assertEquals(0L, register("GPIOA", "IDR").resetValue)
        assertEquals(0x40020000L..0x40020051L, peripheral("GPIOA").span)
    }

    @Test
    fun `fields, field arrays, bit positions and enumerated values`() {
        val moder = register("GPIOA", "MODER")
        assertEquals(listOf("MODE15", "MODER1", "MODER0"), moder.fields.map { it.name })
        val mode15 = moder.fields.first()
        assertEquals(30, mode15.bitOffset)
        assertEquals(2, mode15.bitWidth)
        assertEquals("[31:30]", mode15.bitRange)
        assertEquals(2L, moder.fields[1].bitOffset.toLong())

        val value = 0xA8000000L
        assertEquals(2L, mode15.extract(value))
        assertEquals("Alternate", mode15.describe(2))
        assertEquals("Analog", mode15.describe(3))
        assertEquals(0x68000000L, mode15.insert(value, 1))   // only bits 31:30 change
        assertEquals(0xA8000000L, mode15.insert(value, 2))
        assertEquals(0xE8000000L, mode15.insert(value, 7))   // too-large values are masked to the field
        assertEquals("[0]", register("GPIOA", "IDR").fields.single().bitRange)
    }

    @Test
    fun `numbers in every SVD notation`() {
        assertEquals(255L, SvdParser.parseNumber("0xFF"))
        assertEquals(255L, SvdParser.parseNumber("0XfF"))
        assertEquals(5L, SvdParser.parseNumber("#101"))
        assertEquals(5L, SvdParser.parseNumber("0b101"))
        assertEquals(42L, SvdParser.parseNumber(" 42 "))
        assertEquals(0xFFFFFFFFL, SvdParser.parseNumber("0xFFFFFFFF"))
    }
}
