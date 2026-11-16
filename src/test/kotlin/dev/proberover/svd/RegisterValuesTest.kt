package dev.proberover.svd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegisterValuesTest {
    private val reg32 = SvdRegister("R", null, 0x1004, 32, Access.READ_WRITE, null, emptyList())
    private val reg16 = SvdRegister("S", null, 0x1008, 16, Access.READ_WRITE, null, emptyList())

    @Test
    fun `little-endian decode inside a block`() {
        val block = byteArrayOf(0, 0, 0, 0, 0x78, 0x56, 0x34, 0x12, 0xCD.toByte(), 0xAB.toByte())
        assertEquals(0x12345678L, RegisterValues.read(block, 0x1000, reg32))
        assertEquals(0xABCDL, RegisterValues.read(block, 0x1000, reg16))
        assertNull("block too short", RegisterValues.read(block.copyOf(6), 0x1000, reg32))
        assertNull("register before block", RegisterValues.read(block, 0x1006, reg32))
    }

    @Test
    fun `encode round trip`() {
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), RegisterValues.encode(0x12345678, 4))
        assertEquals(0xFFFFFFFFL, RegisterValues.read(RegisterValues.encode(0xFFFFFFFFL, 4), 0x1004, reg32))
    }

    @Test
    fun `user input in every notation, range checked, enum names for fields`() {
        assertEquals(0x1FL, RegisterValues.parseInput("0x1f", 8))
        assertEquals(5L, RegisterValues.parseInput("0b101", 3))
        assertEquals(10L, RegisterValues.parseInput("10", 4))
        assertNull(RegisterValues.parseInput("16", 4))
        assertNull(RegisterValues.parseInput("banana", 32))
        val mode = SvdField("MODE", null, 0, 2, Access.READ_WRITE, mapOf(2L to ("Alternate" to null)))
        assertEquals(2L, RegisterValues.parseInput("alternate", 2, mode))
        assertEquals("0x0A", RegisterValues.hex(10, 8))
        assertEquals("0xA8000000", RegisterValues.hex(0xA8000000L, 32))
    }
}
