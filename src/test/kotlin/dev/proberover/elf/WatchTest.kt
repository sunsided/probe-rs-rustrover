package dev.proberover.elf

import dev.proberover.watch.Watch
import dev.proberover.watch.WatchResolver
import dev.proberover.watch.WatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTest {
    private val symbols = listOf(
        ElfSymbol("_ZN6blinky7COUNTER17h0123456789abcdefE", 0x20000010, 4),
        ElfSymbol("_ZN6blinky4MODE17h00112233445566aaE", 0x20000014, 1),
        ElfSymbol("TEMPERATURE", 0x20000020, 4),
    )

    @Test
    fun `decode every type little-endian`() {
        assertEquals(255.0, WatchType.U8.decode(byteArrayOf(-1))!!, 0.0)
        assertEquals(-1.0, WatchType.I8.decode(byteArrayOf(-1))!!, 0.0)
        assertEquals(0x1234.toDouble(), WatchType.U16.decode(byteArrayOf(0x34, 0x12))!!, 0.0)
        assertEquals(4294967295.0, WatchType.U32.decode(byteArrayOf(-1, -1, -1, -1))!!, 0.0)
        assertEquals(-2.0, WatchType.I32.decode(byteArrayOf(-2, -1, -1, -1))!!, 0.0)
        assertEquals(1.5, WatchType.F32.decode(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).array())!!, 0.0)
        assertEquals(1.0, WatchType.BOOL.decode(byteArrayOf(7))!!, 0.0)
        assertNull("too few bytes", WatchType.U32.decode(byteArrayOf(1, 2)))
        assertEquals("true", WatchType.BOOL.format(1.0))
        assertEquals("21.5", WatchType.F32.format(21.5))
    }

    @Test
    fun `resolve addresses, mangled names, Rust paths and unique short names`() {
        assertEquals(0x20000040L to WatchType.U32, WatchResolver.resolve("0x20000040", symbols))
        assertEquals(0x20000020L to WatchType.U32, WatchResolver.resolve("TEMPERATURE", symbols))
        assertEquals(0x20000010L to WatchType.U32, WatchResolver.resolve("blinky::COUNTER", symbols))
        assertEquals(0x20000014L to WatchType.U8, WatchResolver.resolve("MODE", symbols))
        assertNull(WatchResolver.resolve("NOPE", symbols))
    }

    @Test
    fun `samples keep min, max and a bounded history`() {
        val w = Watch("x", 0, WatchType.U32)
        (1..400).forEach { w.add(it.toDouble()) }
        assertEquals(300, w.samples.size)
        assertEquals(1.0, w.min!!, 0.0)
        assertEquals(400.0, w.max!!, 0.0)
        assertEquals(400.0, w.last!!, 0.0)
    }
}
