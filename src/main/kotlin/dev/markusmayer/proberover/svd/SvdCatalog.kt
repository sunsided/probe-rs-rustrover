package dev.markusmayer.proberover.svd

import com.google.gson.JsonParser

/** An SVD file someone publishes for a chip. */
data class SvdCandidate(val source: String, val name: String, val url: String)

/**
 * Where to find a chip's SVD file, tried in order: the Rust PAC projects first (their SVDs are the ones the PAC
 * crates were generated from, so register names match the Rust types), then the
 * [cmsis-svd-data](https://github.com/cmsis-svd/cmsis-svd-data) collection of vendor SVDs.
 */
object SvdCatalog {
    /** A place that lists SVD files: [listUrl] is fetched and [parse]d into file name → download URL. */
    class Source(
        val name: String,
        val listUrl: String?,
        val appliesTo: (chip: String) -> Boolean,
        val parse: (listing: String?) -> Map<String, String>,
    )

    val sources = listOf(
        Source(
            "atsamd-rs/atsamd",
            "https://api.github.com/repos/atsamd-rs/atsamd/contents/svd",
            { it.startsWith("ATSAM", ignoreCase = true) },
            ::parseContentsListing,
        ),
        Source(
            "rp-rs/rp2040-pac",
            null,
            { it.startsWith("RP2040", ignoreCase = true) },
            { mapOf("rp2040.svd" to "https://raw.githubusercontent.com/rp-rs/rp2040-pac/main/svd/rp2040.svd") },
        ),
        Source(
            "cmsis-svd/cmsis-svd-data",
            "https://api.github.com/repos/cmsis-svd/cmsis-svd-data/git/trees/main?recursive=1",
            { true },
            ::parseCmsisTree,
        ),
    )

    /** GitHub's contents API for a directory: `[{"name": "X.svd", "download_url": "…"}, …]`. */
    fun parseContentsListing(json: String?): Map<String, String> =
        JsonParser.parseString(json ?: return emptyMap()).asJsonArray.mapNotNull { e ->
            val o = e.asJsonObject
            val name = o.get("name")?.asString ?: return@mapNotNull null
            val url = o.get("download_url")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            (name to url).takeIf { name.endsWith(".svd", ignoreCase = true) }
        }.toMap()

    /** GitHub's recursive tree of cmsis-svd-data: `data/<Vendor>/<Chip>.svd` entries become raw download URLs. */
    fun parseCmsisTree(json: String?): Map<String, String> =
        JsonParser.parseString(json ?: return emptyMap()).asJsonObject.getAsJsonArray("tree").mapNotNull { e ->
            val path = e.asJsonObject.get("path")?.asString ?: return@mapNotNull null
            if (!path.startsWith("data/") || !path.endsWith(".svd", ignoreCase = true)) return@mapNotNull null
            path.substringAfterLast('/') to "https://raw.githubusercontent.com/cmsis-svd/cmsis-svd-data/main/$path"
        }.toMap()

    /**
     * The SVD file for [chip]: the longest file name the chip name starts with, ignoring case, so that
     * `ATSAMD21G18AU` finds `ATSAMD21G18A.svd`, `STM32F411RETx` finds `STM32F411.svd` and `nRF52840_xxAA` finds
     * `nrf52840.svd`. Names shorter than 4 characters never match.
     */
    fun bestMatch(chip: String, files: Collection<String>): String? = matches(chip, files).firstOrNull()

    /** All file names [chip] starts with, longest (most specific) first. */
    fun matches(chip: String, files: Collection<String>): List<String> = files
        .filter { file ->
            val stem = file.removeSuffix(".svd").removeSuffix(".SVD")
            stem.length >= 4 && chip.startsWith(stem, ignoreCase = true)
        }
        .sortedByDescending { it.length }

    private val CPU = Regex("""<cpu>(.*?)</cpu>""", RegexOption.DOT_MATCHES_ALL)
    /** `<cpu>` children that probe-rs's SVD parser (svd-parser 0.14) requires. */
    private val REQUIRED_CPU_TAGS = listOf("name", "revision", "endian", "mpuPresent", "fpuPresent", "nvicPrioBits", "vendorSystickConfig")
    /** Cores that never have an FPU, so a missing `<fpuPresent>` can only mean false. */
    private val NO_FPU_CORES = setOf("CM0", "CM0+", "CM0PLUS", "CM1", "CM3", "CM23", "SC000", "SC300")

    /** What [forProbeRs] did: the SVD text to save, and why probe-rs still won't read it, if it won't. */
    data class Fixed(val xml: String, val problem: String?)

    /**
     * Makes an SVD readable by probe-rs where that's safe. Some PAC SVDs (e.g. atsamd-rs's) leave out `<fpuPresent>`,
     * which probe-rs's strict parser rejects; for cores without an FPU it's added as `false`. Other missing `<cpu>` tags
     * are reported, not guessed.
     */
    fun forProbeRs(xml: String): Fixed {
        val cpu = CPU.find(xml) ?: return Fixed(xml, null) // <cpu> is optional
        val body = cpu.groupValues[1]
        fun has(tag: String) = Regex("<$tag>").containsMatchIn(body)
        var fixed = xml
        if (!has("fpuPresent")) {
            val core = Regex("""<name>\s*([^<]+?)\s*</name>""").find(body)?.groupValues?.get(1)?.uppercase()
            if (core in NO_FPU_CORES) {
                fixed = xml.replaceRange(cpu.range, cpu.value.replace("</cpu>", "  <fpuPresent>false</fpuPresent>\n    </cpu>"))
            }
        }
        val missing = REQUIRED_CPU_TAGS.filterNot { Regex("<$it>").containsMatchIn(CPU.find(fixed)!!.groupValues[1]) }
        return Fixed(fixed, missing.takeIf { it.isNotEmpty() }?.let { "its <cpu> has no ${it.joinToString { t -> "<$t>" }}" })
    }
}
