package dev.markusmayer.proberover.detect

import dev.markusmayer.proberover.model.ProbeRsTarget
import dev.markusmayer.proberover.model.RttDataFormat

/** What we learned about a Cargo project from its `.cargo/config.toml` and `Cargo.toml`. */
data class DetectedProject(
    val buildTarget: String?,
    /** The runner command line, split into arguments, if one is configured. */
    val runner: List<String>?,
    /** Non-null when the runner is `probe-rs run --chip …`. */
    val probeRs: ProbeRsTarget?,
    /** The crate uses the embedded-test harness, so `cargo test` runs on the target through the probe-rs runner. */
    val usesEmbeddedTest: Boolean = false,
) {
    val isEmbeddedTarget: Boolean get() = buildTarget?.let(::isEmbeddedTriple) ?: false
}

private val EMBEDDED_TRIPLE_PREFIXES = listOf("thumbv", "riscv32", "xtensa-", "armv7r", "armebv7r", "armv8r")

fun isEmbeddedTriple(triple: String): Boolean = EMBEDDED_TRIPLE_PREFIXES.any { triple.startsWith(it) } || triple.endsWith("-none-eabi") || triple.endsWith("-none-eabihf")

/**
 * Reads the parts of `.cargo/config.toml` and `Cargo.toml` that matter to us, with a real TOML parser (Jackson's TOML
 * module, bundled with the IDE). Falls back to a small line-based reader if that library isn't available.
 */
object CargoConfigDetector {
    private val toml by lazy { com.fasterxml.jackson.dataformat.toml.TomlMapper() }

    fun detect(cargoConfigToml: String?, cargoToml: String? = null): DetectedProject {
        val parsed = try {
            parseWithToml(cargoConfigToml, cargoToml)
        } catch (e: LinkageError) {
            null // the TOML library isn't reachable: use the line reader
        }
        return parsed ?: detectLineBased(cargoConfigToml, cargoToml)
    }

    /** Null if a file isn't valid TOML (Cargo would reject it too); the line reader then does its best. */
    private fun parseWithToml(cargoConfigToml: String?, cargoToml: String?): DetectedProject? {
        val config = cargoConfigToml?.takeIf { it.isNotBlank() }?.let { runCatching { toml.readTree(it) }.getOrNull() ?: return null }
        val buildTarget = config?.path("build")?.path("target")?.let { if (it.isArray) it.path(0) else it }?.takeIf { it.isTextual }?.asText()

        // [target.<triple or cfg(...)>] runner = "…" | ["…", …]; prefer the table for the build target.
        val runners = config?.path("target")?.properties()?.mapNotNull { (key, table) ->
            val runner = table.path("runner")
            val args = when {
                runner.isTextual -> splitCommandLine(runner.asText())
                runner.isArray -> runner.mapNotNull { n -> n.takeIf { it.isTextual }?.asText() }
                else -> null
            }
            args?.let { key to it }
        }.orEmpty()
        val runner = runners.firstOrNull { it.first == buildTarget }?.second ?: runners.firstOrNull()?.second

        val usesDefmt = cargoToml?.let { text ->
            val manifest = runCatching { toml.readTree(text) }.getOrNull() ?: return@let Regex("""(?m)^\s*defmt(-rtt)?\s*=""").containsMatchIn(text)
            val tables = buildList {
                add(manifest.path("dependencies"))
                add(manifest.path("workspace").path("dependencies"))
                manifest.path("target").properties().forEach { (_, t) -> add(t.path("dependencies")) }
            }
            tables.any { it.has("defmt") || it.has("defmt-rtt") }
        } ?: false
        val usesEmbeddedTest = cargoToml?.let { text ->
            val manifest = runCatching { toml.readTree(text) }.getOrNull() ?: return@let text.contains("embedded-test")
            buildList {
                add(manifest.path("dev-dependencies")); add(manifest.path("dependencies"))
                add(manifest.path("workspace").path("dependencies"))
                manifest.path("target").properties().forEach { (_, t) -> add(t.path("dev-dependencies")); add(t.path("dependencies")) }
            }.any { it.has("embedded-test") }
        } ?: false

        val probeRs = runner?.let { parseProbeRsRun(it) }?.let { if (usesDefmt) it.copy(rttFormat = RttDataFormat.DEFMT) else it }
        return DetectedProject(buildTarget, runner, probeRs, usesEmbeddedTest)
    }

    private fun detectLineBased(cargoConfigToml: String?, cargoToml: String?): DetectedProject {
        var table = ""
        var buildTarget: String? = null
        var runner: List<String>? = null
        var runnerIsForBuildTarget = false

        cargoConfigToml.orEmpty().lineSequence().forEach { raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.removePrefix("[").removeSuffix("]").trim()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()

            when {
                table == "build" && key == "target" -> buildTarget = parseStringOrFirstOfArray(value)
                table.startsWith("target.") && key == "runner" -> {
                    val args = parseRunner(value) ?: return@forEach
                    // Prefer a runner whose table names the build target; otherwise keep the first one we see.
                    val tableTriple = unquote(table.removePrefix("target."))
                    val matches = buildTarget != null && tableTriple == buildTarget
                    if (runner == null || (matches && !runnerIsForBuildTarget)) {
                        runner = args
                        runnerIsForBuildTarget = matches
                    }
                }
            }
        }

        val usesDefmt = cargoToml?.let { Regex("""(?m)^\s*defmt(-rtt)?\s*=""").containsMatchIn(it) } ?: false
        val probeRs = runner?.let { parseProbeRsRun(it) }?.let { if (usesDefmt) it.copy(rttFormat = RttDataFormat.DEFMT) else it }
        return DetectedProject(buildTarget, runner, probeRs)
    }

    /** Parses `probe-rs run --chip X [--probe P] [--speed N] [--protocol swd] …` into a target. */
    fun parseProbeRsRun(args: List<String>): ProbeRsTarget? {
        if (args.isEmpty()) return null
        val exe = args[0].substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe")
        if (exe != "probe-rs") return null
        val sub = args.getOrNull(1) ?: return null
        if (sub != "run" && sub != "download" && sub != "attach") return null

        var chip: String? = null
        var probe: String? = null
        var speed: Int? = null
        var protocol: String? = null
        var connectUnderReset = false
        var allowEraseAll = false
        var catchReset = false
        var catchHardfault = true

        var i = 2
        fun valueOf(flag: String): String? {
            val a = args[i]
            if (a.startsWith("$flag=")) return a.substringAfter('=')
            return args.getOrNull(++i)
        }
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--chip" || a.startsWith("--chip=") -> chip = valueOf("--chip")
                a == "--probe" || a.startsWith("--probe=") -> probe = valueOf("--probe")
                a == "--speed" || a.startsWith("--speed=") -> speed = valueOf("--speed")?.toIntOrNull()
                a == "--protocol" || a.startsWith("--protocol=") -> protocol = valueOf("--protocol")?.let(::normalizeProtocol)
                a == "--connect-under-reset" -> connectUnderReset = true
                a == "--allow-erase-all" -> allowEraseAll = true
                a == "--catch-reset" -> catchReset = true
                a == "--no-catch-hardfault" -> catchHardfault = false
                a == "--catch-hardfault" -> catchHardfault = true
            }
            i++
        }
        return ProbeRsTarget(
            chip = chip ?: return null,
            probe = probe,
            speedKhz = speed,
            wireProtocol = protocol,
            connectUnderReset = connectUnderReset,
            allowEraseAll = allowEraseAll,
            catchReset = catchReset,
            catchHardfault = catchHardfault,
        )
    }

    private fun normalizeProtocol(p: String) = when (p.lowercase()) {
        "swd" -> "Swd"
        "jtag" -> "Jtag"
        else -> p
    }

    private fun parseRunner(value: String): List<String>? = when {
        value.startsWith("[") -> parseStringArray(value)
        else -> parseString(value)?.let(::splitCommandLine)
    }

    private fun parseStringOrFirstOfArray(value: String): String? =
        if (value.startsWith("[")) parseStringArray(value)?.firstOrNull() else parseString(value)

    private fun parseString(value: String): String? {
        if (value.length < 2) return null
        val q = value[0]
        if ((q != '"' && q != '\'') || value.last() != q) return null
        return value.substring(1, value.length - 1)
    }

    private fun parseStringArray(value: String): List<String>? {
        if (!value.endsWith("]")) return null
        return Regex(""""([^"]*)"|'([^']*)'""").findAll(value).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'")

    private fun stripComment(line: String): String {
        var inQuote: Char? = null
        line.forEachIndexed { i, c ->
            when {
                inQuote != null && c == inQuote -> inQuote = null
                inQuote == null && (c == '"' || c == '\'') -> inQuote = c
                inQuote == null && c == '#' -> return line.substring(0, i)
            }
        }
        return line
    }
}

/** Splits a command line the way a POSIX shell would for simple cases: whitespace, single and double quotes. */
fun splitCommandLine(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var inToken = false
    for (c in s) {
        when {
            quote != null && c == quote -> quote = null
            quote != null -> cur.append(c)
            c == '"' || c == '\'' -> { quote = c; inToken = true }
            c.isWhitespace() -> if (inToken) { out += cur.toString(); cur.clear(); inToken = false }
            else -> { cur.append(c); inToken = true }
        }
    }
    if (inToken) out += cur.toString()
    return out
}
