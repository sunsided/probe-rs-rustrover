package dev.markusmayer.proberover.session

/**
 * Turns probe-rs's scope and variable texts into tidy rows for the probe-rs tab. probe-rs packs several things into
 * `value` and `type`: `0000 @ 0x41004436:0..4` (value, address, bits), `Register cannot be read without side effects
 * @ 0x42004400`, a peripheral's description as its value and `Peripheral Group` as its type, a register's description
 * as its type.
 */
object ScopeRows {
    enum class Kind { SCOPE, GROUP, PERIPHERAL, REGISTER, FIELD, NAMESPACE, VALUE }

    data class Row(
        val name: String,
        val kind: Kind,
        /** The value to show; null when there is none (scopes, peripherals, namespaces). */
        val value: String?,
        /** probe-rs didn't read it because reading has side effects (e.g. clears flags). */
        val notRead: Boolean = false,
        val description: String? = null,
        /** `0x41004436` or `0x41004436 [0..4]`. */
        val location: String? = null,
        /** The full, unshortened value for the tooltip. */
        val fullValue: String? = null,
        /** The number behind [value] when [value] is an enumerated value's name, e.g. `0x6` for `G`. */
        val raw: String? = null,
        /** The field's own description when [description] holds the enumerated value's. */
        val fieldDescription: String? = null,
    )

    /** What an SVD field's value means: the enumerated value's name and description, if the SVD has one. */
    fun interface Decoder {
        fun decode(registerAddress: Long, bitOffset: Int, bitWidth: Int, value: Long): Pair<String, String?>?
    }

    private val AT = Regex("""^(.*?)\s*@\s*(0x[0-9A-Fa-f]+)(?::(\d+)\.\.(\d+))?\s*$""", RegexOption.DOT_MATCHES_ALL)
    private const val NOT_READ = "cannot be read without side effects"
    private const val MAX_VALUE = 120

    /** probe-rs entries not worth a row: optimized-out vtables of trait objects. */
    fun isNoise(name: String, value: String): Boolean = name.endsWith("::{vtable}") && value.contains("optimized out")

    fun row(name: String, value: String, type: String?, isScope: Boolean = false, decoder: Decoder? = null): Row {
        if (isScope) return Row(name, Kind.SCOPE, null)
        when (type) {
            "Peripheral Group" -> return Row(name, Kind.GROUP, null, description = value.ifBlank { null })
            "Peripheral" -> return Row(name, Kind.PERIPHERAL, null, description = value.ifBlank { null })
            "namespace" -> return Row(name, Kind.NAMESPACE, null)
        }
        val at = AT.matchEntire(value)
        val bits = at?.groups?.get(3)?.let { "[${it.value}..${at.groupValues[4]}]" }
        val location = at?.let { listOfNotNull(it.groupValues[2], bits).joinToString(" ") }
        val raw = at?.groupValues?.get(1) ?: value
        // Registers and fields come from the SVD file: their "type" is a description, and they have an address.
        val svd = at != null && type != null && !type.first().isLowerCase() && !type.startsWith("[") && type != "Platform Register"
        val kind = when {
            svd && bits != null -> Kind.FIELD
            svd -> Kind.REGISTER
            type == "Platform Register" -> Kind.REGISTER
            else -> Kind.VALUE
        }
        val description = if (svd) type else null
        if (raw.contains(NOT_READ)) return Row(name, kind, null, notRead = true, description = description, location = location)

        if (kind == Kind.FIELD && decoder != null && raw.isNotEmpty() && raw.all { it == '0' || it == '1' }) {
            val from = at!!.groupValues[3].toInt()
            val meaning = decoder.decode(at.groupValues[2].removePrefix("0x").toLong(16), from, at.groupValues[4].toInt() - from, raw.toLong(2))
            if (meaning != null) {
                return Row(name, kind, meaning.first, description = meaning.second ?: description, location = location,
                    raw = fieldValue(raw), fieldDescription = description)
            }
        }
        val shown = when {
            kind == Kind.FIELD -> fieldValue(raw)
            else -> raw
        }
        val oneLine = shown.replace(Regex("""\s*\n\s*"""), " ")
        val short = if (oneLine.length > MAX_VALUE) oneLine.take(MAX_VALUE - 1) + "…" else oneLine
        val typeLabel = type?.takeIf { kind == Kind.VALUE && it != "<unknown>" }
        return Row(
            name, kind, short,
            description = description ?: typeLabel,
            location = location ?: if (kind == Kind.VALUE) null else null,
            fullValue = shown.takeIf { it != short },
        )
    }

    /** probe-rs shows fields as bits (`0000`, `1`); hex reads better for wide fields, the bits stay in the tooltip. */
    fun fieldValue(bits: String): String {
        if (bits.isEmpty() || bits.any { it != '0' && it != '1' }) return bits
        val n = bits.toLong(2)
        return if (bits.length == 1) n.toString() else "0x" + n.toString(16).uppercase()
    }
}
