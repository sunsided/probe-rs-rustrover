package dev.proberover.svd

/** Decoding and encoding register values in target memory (little-endian, as on Arm Cortex-M and RISC-V MCUs). */
object RegisterValues {

    /** The value of [register] inside [block], which starts at [blockAddress]; null if the block doesn't cover it. */
    fun read(block: ByteArray, blockAddress: Long, register: SvdRegister): Long? {
        val start = register.address - blockAddress
        if (start < 0 || start + register.sizeBytes > block.size) return null
        var value = 0L
        for (i in register.sizeBytes - 1 downTo 0) value = (value shl 8) or (block[(start + i).toInt()].toLong() and 0xFF)
        return value
    }

    fun encode(value: Long, sizeBytes: Int): ByteArray = ByteArray(sizeBytes) { i -> (value ushr (8 * i)).toByte() }

    /**
     * Parses what the user typed for a register or field: `0x1F`, `0b101`, `#101`, decimal, or (for fields) one of its
     * enumerated value names. Returns null if it isn't a number that fits [bits].
     */
    fun parseInput(text: String, bits: Int, field: SvdField? = null): Long? {
        val t = text.trim()
        field?.values?.entries?.firstOrNull { it.value.first.equals(t, ignoreCase = true) }?.let { return it.key }
        val value = runCatching { SvdParser.parseNumber(t) }.getOrNull() ?: return null
        if (value < 0) return null
        if (bits < 64 && value ushr bits != 0L) return null
        return value
    }

    fun hex(value: Long, bits: Int): String = "0x" + value.toString(16).uppercase().padStart((bits + 3) / 4, '0')
}
