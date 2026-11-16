package dev.markusmayer.proberover.elf

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfSymbolsTest {

    /** Builds a minimal little-endian ELF32 with .symtab and .strtab, like a Cortex-M firmware's. */
    private fun elf32(symbols: List<Triple<String, Long, Long>>, functionNamed: String): ByteArray {
        val strtab = java.io.ByteArrayOutputStream().apply { write(0) }
        val nameOffsets = (symbols.map { it.first } + functionNamed).associateWith { n -> strtab.size().also { strtab.write(n.toByteArray()); strtab.write(0) } }
        val entries = symbols.map { (n, v, s) -> Triple(nameOffsets.getValue(n), v, s) to 0x11 } + // GLOBAL OBJECT
            (Triple(nameOffsets.getValue(functionNamed), 0x08000100L, 64L) to 0x12)                    // GLOBAL FUNC
        val symtab = ByteBuffer.allocate(16 * (entries.size + 1)).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(16) // index 0 is the null symbol
            entries.forEach { (sym, info) -> putInt(sym.first); putInt(sym.second.toInt()); putInt(sym.third.toInt()); put(info.toByte()); put(0); putShort(1) }
        }.array()
        val strBytes = strtab.toByteArray()
        val symOff = 52; val strOff = symOff + symtab.size; val shOff = strOff + strBytes.size
        return ByteBuffer.allocate(shOff + 3 * 40).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
            position(0x20); putInt(shOff)
            position(0x2E); putShort(40); putShort(3); putShort(0)
            position(symOff); put(symtab); put(strBytes)
            position(shOff + 40) // section 1: .symtab, link -> 2
            putInt(0); putInt(2); putInt(0); putInt(0); putInt(symOff); putInt(symtab.size); putInt(2); putInt(1); putInt(4); putInt(16)
            putInt(0); putInt(3); putInt(0); putInt(0); putInt(strOff); putInt(strBytes.size); putInt(0); putInt(0); putInt(1); putInt(0)
        }.array()
    }

    @Test
    fun `reads data symbols and skips functions`() {
        val elf = elf32(
            listOf(
                Triple("_ZN6blinky7COUNTER17h0123456789abcdefE", 0x20000010L, 4L),
                Triple("TEMPERATURE", 0x20000020L, 4L),
            ),
            functionNamed = "main",
        )
        val symbols = ElfSymbols.read(elf)
        assertEquals(listOf("TEMPERATURE", "_ZN6blinky7COUNTER17h0123456789abcdefE"), symbols.map { it.name })
        assertEquals(0x20000020L, symbols[0].address)
        assertEquals(4L, symbols[0].size)
    }

    @Test
    fun `rust legacy mangling to a readable path`() {
        assertEquals("blinky::COUNTER", ElfSymbols.demangledTail("_ZN6blinky7COUNTER17h0123456789abcdefE"))
        assertEquals("app::state::LEVEL", ElfSymbols.demangledTail("_ZN3app5state5LEVEL17hfedcba9876543210E"))
        assertEquals("TEMPERATURE", ElfSymbols.demangledTail("TEMPERATURE"))
    }
}
