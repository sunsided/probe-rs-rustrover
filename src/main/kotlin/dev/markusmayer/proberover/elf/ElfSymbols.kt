package dev.markusmayer.proberover.elf

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A data symbol from an ELF symbol table: a global or static variable. */
data class ElfSymbol(val name: String, val address: Long, val size: Long)

/**
 * Reads data symbols (`STT_OBJECT`) from an ELF file's `.symtab`: enough to find a firmware's global and static
 * variables by name. ELF32 and ELF64, either byte order. Rust names stay mangled unless they are `#[no_mangle]`;
 * [demangledTail] gives the readable last path segment.
 */
object ElfSymbols {
    private const val SHT_SYMTAB = 2
    private const val STT_OBJECT = 1

    fun read(file: File): List<ElfSymbol> = read(file.readBytes())

    fun read(bytes: ByteArray): List<ElfSymbol> {
        require(bytes.size > 52 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "Not an ELF file" }
        val is64 = bytes[4].toInt() == 2
        val buf = ByteBuffer.wrap(bytes).order(if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        fun addr(at: Int) = if (is64) buf.getLong(at) else buf.getInt(at).toLong() and 0xFFFFFFFFL
        fun u32(at: Int) = buf.getInt(at).toLong() and 0xFFFFFFFFL
        fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF

        val shoff = (if (is64) buf.getLong(0x28) else u32(0x20)).toInt()
        val shentsize = u16(if (is64) 0x3A else 0x2E)
        val shnum = u16(if (is64) 0x3C else 0x30)

        data class Section(val type: Int, val offset: Int, val size: Int, val link: Int, val entsize: Int)
        val sections = (0 until shnum).map { i ->
            val at = shoff + i * shentsize
            if (is64) Section(buf.getInt(at + 4), buf.getLong(at + 0x18).toInt(), buf.getLong(at + 0x20).toInt(), buf.getInt(at + 0x28), buf.getLong(at + 0x38).toInt())
            else Section(buf.getInt(at + 4), u32(at + 0x10).toInt(), u32(at + 0x14).toInt(), buf.getInt(at + 0x18), u32(at + 0x24).toInt())
        }
        val symtab = sections.firstOrNull { it.type == SHT_SYMTAB } ?: return emptyList()
        val strtab = sections.getOrNull(symtab.link) ?: return emptyList()
        fun name(offset: Long): String {
            var end = strtab.offset + offset.toInt()
            val start = end
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, start, end - start, Charsets.UTF_8)
        }

        val entsize = symtab.entsize.takeIf { it > 0 } ?: if (is64) 24 else 16
        return (0 until symtab.size / entsize).mapNotNull { i ->
            val at = symtab.offset + i * entsize
            val nameOffset = u32(at)
            val (value, size, info) = if (is64) Triple(buf.getLong(at + 8), buf.getLong(at + 16), bytes[at + 4].toInt())
            else Triple(addr(at + 4), u32(at + 8), bytes[at + 12].toInt())
            if ((info and 0xF) != STT_OBJECT || size == 0L || nameOffset == 0L) return@mapNotNull null
            ElfSymbol(name(nameOffset), value, size)
        }.sortedBy { it.name }
    }

    /**
     * The readable last segment of a Rust legacy-mangled name, e.g. `_ZN6blinky7COUNTER17h0123456789abcdefE` →
     * `blinky::COUNTER`. Other names are returned unchanged.
     */
    fun demangledTail(name: String): String {
        if (!name.startsWith("_ZN") || !name.endsWith("E")) return name
        val parts = mutableListOf<String>()
        var i = 3
        while (i < name.length - 1) {
            var j = i
            while (j < name.length && name[j].isDigit()) j++
            val len = name.substring(i, j).toIntOrNull() ?: break
            parts += name.substring(j, (j + len).coerceAtMost(name.length))
            i = j + len
        }
        val clean = parts.filterNot { it.length == 17 && it.startsWith("h") && it.drop(1).all { c -> c.isDigit() || c in 'a'..'f' } }
        return clean.joinToString("::").ifEmpty { name }
    }
}
