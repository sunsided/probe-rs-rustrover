package dev.proberover.detect

import dev.proberover.model.ProbeRsTarget
import dev.proberover.model.RttDataFormat

/** What we learned about a Cargo project from its `.cargo/config.toml` and `Cargo.toml`. */
data class DetectedProject(
    val buildTarget: String?,
    /** The runner command line, split into arguments, if one is configured. */
    val runner: List<String>?,
    /** Non-null when the runner is `probe-rs run --chip …`. */
    val probeRs: ProbeRsTarget?,
) {
    val isEmbeddedTarget: Boolean get() = buildTarget?.let(::isEmbeddedTriple) ?: false
}

private val EMBEDDED_TRIPLE_PREFIXES = listOf("thumbv", "riscv32", "xtensa-", "armv7r", "armebv7r", "armv8r")

fun isEmbeddedTriple(triple: String): Boolean = EMBEDDED_TRIPLE_PREFIXES.any { triple.startsWith(it) } || triple.endsWith("-none-eabi") || triple.endsWith("-none-eabihf")

/**
 * Reads the parts of `.cargo/config.toml` that matter to us. This is a deliberately small line-based reader for the
 * spike: tables, `key = "string"` and `key = ["array", "of", "strings"]` on one line. Phase 1 should use a real TOML
 * parser (the bundled TOML plugin, or tomlj).
 */
object CargoConfigDetector {

    fun detect(cargoConfigToml: String?, cargoToml: String? = null): DetectedProject {
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
