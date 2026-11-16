package dev.markusmayer.proberover.watch

import dev.markusmayer.proberover.elf.ElfSymbol
import dev.markusmayer.proberover.elf.ElfSymbols
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How watched memory is interpreted (little-endian). */
enum class WatchType(val size: Int, val label: String) {
    U8(1, "u8"), I8(1, "i8"), U16(2, "u16"), I16(2, "i16"), U32(4, "u32"), I32(4, "i32"), F32(4, "f32"),
    U64(8, "u64"), I64(8, "i64"), F64(8, "f64"), BOOL(1, "bool");

    override fun toString() = label

    fun decode(bytes: ByteArray): Double? {
        if (bytes.size < size) return null
        val b = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        return when (this) {
            U8 -> (b.get().toInt() and 0xFF).toDouble()
            I8 -> b.get().toDouble()
            U16 -> (b.short.toInt() and 0xFFFF).toDouble()
            I16 -> b.short.toDouble()
            U32 -> (b.int.toLong() and 0xFFFFFFFFL).toDouble()
            I32 -> b.int.toDouble()
            F32 -> b.float.toDouble()
            U64 -> java.lang.Long.toUnsignedString(b.long).toDouble()
            I64 -> b.long.toDouble()
            F64 -> b.double
            BOOL -> if (b.get().toInt() != 0) 1.0 else 0.0
        }
    }

    fun format(value: Double): String = when (this) {
        F32, F64 -> if (value.isFinite()) java.math.BigDecimal(value).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString() else value.toString()
        BOOL -> if (value != 0.0) "true" else "false"
        else -> value.toLong().toString()
    }

    companion object {
        /** A guess from a symbol's size, for when the user hasn't chosen a type. */
        fun forSize(size: Long): WatchType = when (size) {
            1L -> U8; 2L -> U16; 8L -> U64; else -> U32
        }
    }
}

/** One watched value and its recent samples. */
class Watch(var expression: String, var address: Long, var type: WatchType) {
    val samples = ArrayDeque<Double>()
    var last: Double? = null
    var min: Double? = null
    var max: Double? = null

    fun add(value: Double, capacity: Int = 300) {
        samples.addLast(value)
        while (samples.size > capacity) samples.removeFirst()
        last = value
        min = min?.coerceAtMost(value) ?: value
        max = max?.coerceAtLeast(value) ?: value
    }

    fun reset() { samples.clear(); last = null; min = null; max = null }
}

object WatchResolver {
    /**
     * Resolves what the user typed: an address (`0x20000010`), a symbol name as in the ELF, or the readable Rust path
     * of one (`blinky::COUNTER`, or just `COUNTER` if that's unique).
     */
    fun resolve(input: String, symbols: List<ElfSymbol>): Pair<Long, WatchType>? {
        val text = input.trim()
        if (text.startsWith("0x", ignoreCase = true)) {
            return text.substring(2).toLongOrNull(16)?.let { it to WatchType.U32 }
        }
        val symbol = symbols.firstOrNull { it.name == text }
            ?: symbols.firstOrNull { ElfSymbols.demangledTail(it.name) == text }
            ?: symbols.filter { ElfSymbols.demangledTail(it.name).substringAfterLast("::") == text }.singleOrNull()
            ?: return null
        return symbol.address to WatchType.forSize(symbol.size)
    }
}
