package dev.markusmayer.proberover.svd

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

enum class Access(val readable: Boolean, val writable: Boolean) {
    READ_ONLY(true, false), WRITE_ONLY(false, true), READ_WRITE(true, true), WRITE_ONCE(false, true), READ_WRITE_ONCE(true, true);

    companion object {
        fun parse(s: String?): Access? = when (s?.trim()) {
            "read-only" -> READ_ONLY
            "write-only" -> WRITE_ONLY
            "read-write" -> READ_WRITE
            "writeOnce" -> WRITE_ONCE
            "read-writeOnce" -> READ_WRITE_ONCE
            else -> null
        }
    }
}

data class SvdField(
    val name: String,
    val description: String?,
    val bitOffset: Int,
    val bitWidth: Int,
    val access: Access,
    /** Enumerated values: raw value → name (and description). */
    val values: Map<Long, Pair<String, String?>> = emptyMap(),
) {
    val mask: Long get() = if (bitWidth >= 64) -1L else ((1L shl bitWidth) - 1) shl bitOffset
    val bitRange: String get() = if (bitWidth == 1) "[$bitOffset]" else "[${bitOffset + bitWidth - 1}:$bitOffset]"

    fun extract(registerValue: Long): Long = (registerValue and mask) ushr bitOffset

    /** The register value with this field set to [fieldValue], other bits unchanged. */
    fun insert(registerValue: Long, fieldValue: Long): Long =
        (registerValue and mask.inv()) or ((fieldValue shl bitOffset) and mask)

    fun describe(fieldValue: Long): String? = values[fieldValue]?.first
}

data class SvdRegister(
    val name: String,
    val description: String?,
    /** Absolute address. */
    val address: Long,
    val sizeBits: Int,
    val access: Access,
    val resetValue: Long?,
    val fields: List<SvdField>,
) {
    val sizeBytes: Int get() = (sizeBits + 7) / 8
}

data class SvdPeripheral(
    val name: String,
    val description: String?,
    val groupName: String?,
    val baseAddress: Long,
    val registers: List<SvdRegister>,
) {
    /** The smallest address range covering all registers, for reading the whole block at once. */
    val span: LongRange? get() = registers.takeIf { it.isNotEmpty() }?.let { r -> r.minOf { it.address }..r.maxOf { it.address + it.sizeBytes - 1 } }
}

data class SvdDevice(val name: String, val peripherals: List<SvdPeripheral>)

/**
 * Reads CMSIS-SVD files: peripherals (including `derivedFrom`), registers in clusters (nested), `dim` arrays of
 * registers, clusters and peripherals, register property inheritance (size, access, reset value) and enumerated values.
 */
object SvdParser {

    fun parse(file: File): SvdDevice = file.inputStream().use(::parse)

    fun parse(input: InputStream): SvdDevice {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // SVD files are plain XML; refuse DTDs and external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        val deviceDefaults = Defaults().inherit(root)
        val peripheralElements = root.child("peripherals")?.children("peripheral").orEmpty()
        val byName = peripheralElements.associateBy { it.text("name") }

        val peripherals = peripheralElements.flatMap { element ->
            val base = element.getAttribute("derivedFrom").takeIf { it.isNotEmpty() }?.let { byName[it] }
            expandDim(element) { name, offset ->
                val baseAddress = parseNumber(element.text("baseAddress") ?: base?.text("baseAddress") ?: "0") + offset
                val defaults = deviceDefaults.inherit(base).inherit(element)
                val registersElement = element.child("registers") ?: base?.child("registers")
                SvdPeripheral(
                    name = name,
                    description = (element.text("description") ?: base?.text("description"))?.clean(),
                    groupName = element.text("groupName") ?: base?.text("groupName"),
                    baseAddress = baseAddress,
                    registers = registersElement?.let { registers(it, baseAddress, defaults, "") }.orEmpty()
                        .sortedBy { it.address },
                )
            }
        }.sortedBy { it.name }
        return SvdDevice(root.text("name") ?: "device", peripherals)
    }

    private fun registers(container: Element, base: Long, defaults: Defaults, prefix: String): List<SvdRegister> {
        val out = mutableListOf<SvdRegister>()
        for (child in container.elementChildren()) {
            when (child.tagName) {
                "register" -> expandDim(child) { name, offset ->
                    val d = defaults.inherit(child)
                    SvdRegister(
                        name = prefix + name,
                        description = child.text("description")?.clean(),
                        address = base + parseNumber(child.text("addressOffset") ?: "0") + offset,
                        sizeBits = d.size,
                        access = d.access,
                        resetValue = d.resetValue,
                        fields = child.child("fields")?.children("field").orEmpty().flatMap { field(it, d.access) }
                            .sortedByDescending { it.bitOffset },
                    )
                }.let(out::addAll)
                "cluster" -> expandDim(child) { name, offset ->
                    registers(child, base + parseNumber(child.text("addressOffset") ?: "0") + offset, defaults.inherit(child), "$prefix$name.")
                }.forEach(out::addAll)
            }
        }
        return out
    }

    private fun field(element: Element, registerAccess: Access): List<SvdField> = expandDim(element) { name, dimOffset ->
        // For field arrays, dimIncrement is in bits.
        val (bitOffset, width) = bitPosition(element)
        val offset = bitOffset + dimOffset.toInt()
        val values = element.children("enumeratedValues").flatMap { it.children("enumeratedValue") }
            .mapNotNull { v ->
                val value = v.text("value") ?: return@mapNotNull null
                if (value.contains('x', ignoreCase = true) && value.startsWith("#")) return@mapNotNull null // "don't care" bits
                runCatching { parseNumber(value) }.getOrNull()?.let { it to ((v.text("name") ?: "?") to v.text("description")?.clean()) }
            }.toMap()
        SvdField(name, element.text("description")?.clean(), offset, width, Access.parse(element.text("access")) ?: registerAccess, values)
    }

    private fun bitPosition(e: Element): Pair<Int, Int> {
        e.text("bitOffset")?.let { return parseNumber(it).toInt() to (e.text("bitWidth")?.let(::parseNumber)?.toInt() ?: 1) }
        e.text("lsb")?.let { lsb -> val l = parseNumber(lsb).toInt(); return l to (parseNumber(e.text("msb")!!).toInt() - l + 1) }
        e.text("bitRange")?.let { range ->
            val (msb, lsb) = range.trim('[', ']').split(':').map { it.trim().toInt() }
            return lsb to (msb - lsb + 1)
        }
        return 0 to 1
    }

    /** Expands `dim`/`dimIncrement`/`dimIndex` into one item per element; `%s` in the name takes the index. */
    private fun <T> expandDim(e: Element, build: (name: String, offset: Long) -> T): List<T> {
        val name = e.text("name") ?: "?"
        val dim = e.text("dim")?.let(::parseNumber)?.toInt() ?: return listOf(build(name.replace("[%s]", "").replace("%s", ""), 0))
        val increment = e.text("dimIncrement")?.let(::parseNumber) ?: 0
        val indices = e.text("dimIndex")?.let(::dimIndices) ?: (0 until dim).map(Int::toString)
        return indices.take(dim).mapIndexed { i, index ->
            val n = if (name.contains("[%s]")) name.replace("[%s]", index) else name.replace("%s", index)
            build(n, i * increment)
        }
    }

    private fun dimIndices(s: String): List<String> {
        val range = Regex("""^\s*(\d+)\s*-\s*(\d+)\s*$""").find(s)
        if (range != null) return (range.groupValues[1].toInt()..range.groupValues[2].toInt()).map(Int::toString)
        return s.split(',').map(String::trim)
    }

    fun parseNumber(s: String): Long {
        val t = s.trim().replace("_", "")
        return when {
            t.startsWith("0x", true) -> java.lang.Long.parseUnsignedLong(t.substring(2), 16)
            t.startsWith("#") -> t.substring(1).toLong(2)
            t.startsWith("0b", true) -> t.substring(2).toLong(2)
            else -> t.toLong()
        }
    }

    private data class Defaults(val size: Int = 32, val access: Access = Access.READ_WRITE, val resetValue: Long? = null) {
        fun inherit(e: Element?): Defaults = if (e == null) this else Defaults(
            size = e.text("size")?.let(::parseNumber)?.toInt() ?: size,
            access = Access.parse(e.text("access")) ?: access,
            resetValue = e.text("resetValue")?.let(::parseNumber) ?: resetValue,
        )
    }

    private fun String.clean() = replace(Regex("\\s+"), " ").trim()

    private fun Element.elementChildren(): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

    private fun Element.children(tag: String) = elementChildren().filter { it.tagName == tag }
    private fun Element.child(tag: String) = elementChildren().firstOrNull { it.tagName == tag }
    private fun Element.text(tag: String) = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Finds an SVD field by where it lives: register address, bit offset and width. probe-rs reports fields that way
 * (`0x41004436:0..4`) but drops their enumerated values; its names for array and cluster registers differ from ours,
 * the addresses don't.
 */
class SvdFieldIndex(device: SvdDevice) {
    data class Located(val peripheral: SvdPeripheral, val register: SvdRegister, val field: SvdField)

    private val fields: Map<Triple<Long, Int, Int>, Located> = buildMap {
        for (p in device.peripherals) for (r in p.registers) for (f in r.fields) putIfAbsent(Triple(r.address, f.bitOffset, f.bitWidth), Located(p, r, f))
    }

    fun locate(registerAddress: Long, bitOffset: Int, bitWidth: Int): Located? = fields[Triple(registerAddress, bitOffset, bitWidth)]

    private val registers: Map<Long, SvdRegister> = buildMap {
        for (p in device.peripherals) for (r in p.registers) putIfAbsent(r.address, r)
    }

    fun register(address: Long): SvdRegister? = registers[address]

    fun field(registerAddress: Long, bitOffset: Int, bitWidth: Int): SvdField? = locate(registerAddress, bitOffset, bitWidth)?.field

    /** What a field's value means: its enumerated value from the SVD, or for SAM port registers, the pins. */
    fun meaning(registerAddress: Long, bitOffset: Int, bitWidth: Int, value: Long): Pair<String, String?>? {
        val located = locate(registerAddress, bitOffset, bitWidth) ?: return null
        located.field.values[value]?.let { return it }
        return PinRegisters.pins(located, value)?.let { it to null }
    }
}

/**
 * Registers holding one bit per pin, where the SVD has no names for the bits; the pins as text for the probe-rs tab.
 * - Microchip SAM (D/E/L/C): PORT's `DIR`, `OUT`, `IN` and their SET/CLR/TGL variants. Pins are P<group><bit>, the
 *   groups 0x80 apart in PORT (group 0 = PA, 1 = PB, …).
 * - RP2040: SIO's `GPIO_IN`, `GPIO_OUT`, `GPIO_OE` and their SET/CLR/XOR variants (GPIO0…29), and the `GPIO_HI_`
 *   ones for the QSPI pins.
 */
object PinRegisters {
    private val SAM_FIELDS = setOf("DIR", "DIRCLR", "DIRSET", "DIRTGL", "OUT", "OUTCLR", "OUTSET", "OUTTGL", "IN")
    private const val SAM_GROUP_SIZE = 0x80L
    private val RP2040_FIELD = Regex("""GPIO_(HI_)?(IN|OUT|OE)(_SET|_CLR|_XOR)?""")
    /** GPIO_HI_* bits, RP2040 datasheet 2.3.1.7. */
    private val RP2040_QSPI = listOf("QSPI_SCLK", "QSPI_SS", "QSPI_SD0", "QSPI_SD1", "QSPI_SD2", "QSPI_SD3")

    fun pins(located: SvdFieldIndex.Located, value: Long): String? {
        val (peripheral, register, field) = located
        if (field.bitOffset != 0) return null
        val names: (Int) -> String = when {
            peripheral.name == "PORT" && field.name in SAM_FIELDS && field.bitWidth == 32 -> {
                val group = ((register.address - peripheral.baseAddress) / SAM_GROUP_SIZE).toInt().takeIf { it in 0..25 } ?: return null
                { bit -> "P${'A' + group}$bit" }
            }
            peripheral.name == "SIO" && RP2040_FIELD.matches(field.name) ->
                if (field.name.startsWith("GPIO_HI_")) { bit -> RP2040_QSPI.getOrElse(bit) { "QSPI$it" } } else { bit -> "GPIO$bit" }
            else -> return null
        }
        val pins = (0 until field.bitWidth).filter { (value ushr it) and 1L == 1L }.map(names)
        return when {
            pins.isEmpty() -> "none"
            pins.size > 8 -> pins.take(8).joinToString(" ") + " +${pins.size - 8} more"
            else -> pins.joinToString(" ")
        }
    }
}
