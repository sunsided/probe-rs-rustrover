package dev.proberover.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A debug probe as `probe-rs list` reports it. */
data class ProbeInfo(
    val name: String,
    val vendorId: String,
    val productId: String,
    val serial: String?,
    val driver: String,
    val accessible: Boolean,
) {
    /** The `--probe` selector probe-rs accepts: `VID:PID[:SERIAL]`. */
    val selector: String get() = "$vendorId:$productId" + (serial?.let { ":$it" } ?: "")
    val label: String get() = "$name ($selector)" + if (accessible) "" else " – no permission"
}

/** What `probe-rs chip info <chip>` reports: cores and RAM regions. */
data class ChipInfo(val cores: List<String>, val ram: List<LongRange>)

/** Parsers for probe-rs's command-line output (formats from probe-rs's `cmd/list.rs`, `cmd/chip.rs`, `main.rs`). */
object ProbeRsOutput {
    private val PROBE = Regex("""^\[\d+]:\s+(.*?)\s+--\s+([0-9a-fA-F]{4}):([0-9a-fA-F]{4})(?:-\d+)?:(\S*)\s+\((.+?)\)(\s+\(inaccessible\))?\s*$""")
    private val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun probes(output: String): List<ProbeInfo> = output.lineSequence().mapNotNull { line ->
        val m = PROBE.find(line.trim()) ?: return@mapNotNull null
        val (name, vid, pid, serial, driver, inaccessible) = m.destructured
        ProbeInfo(name, vid.lowercase(), pid.lowercase(), serial.ifEmpty { null }, driver, inaccessible.isEmpty())
    }.toList()

    /** Chip names from `probe-rs chip list`: the variants, indented below each family. */
    fun chips(output: String): List<String> = output.lineSequence()
        .filter { it.startsWith("        ") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private val RAM = Regex("""^RAM:\s+0x([0-9a-fA-F]+)\.\.0x([0-9a-fA-F]+)""")
    private val CORE = Regex("""^\s+-\s+(\S+)\s+\(""")

    /** From `probe-rs chip info`: `    - core0 (Armv6m)` and `RAM: 0x20000000..0x20042000 (264.0 KiB)` (end exclusive). */
    fun chipInfo(output: String): ChipInfo {
        val lines = output.lines()
        return ChipInfo(
            cores = lines.mapNotNull { CORE.find(it)?.groupValues?.get(1) },
            ram = lines.mapNotNull { line ->
                RAM.find(line.trim())?.let { m -> m.groupValues[1].toLong(16) until m.groupValues[2].toLong(16) }
            },
        )
    }

    fun version(output: String): Triple<Int, Int, Int>? =
        VERSION.find(output)?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
}

/** Runs probe-rs commands. Call from a background thread. */
@Service(Service.Level.APP)
class ProbeRsTools {
    private data class ChipCache(val stamp: Long, val chips: List<String>)
    private val chipCache = ConcurrentHashMap<String, ChipCache>()
    private val versionCache = ConcurrentHashMap<String, Pair<Long, Triple<Int, Int, Int>?>>()

    fun chips(probeRs: String): List<String> {
        val stamp = File(probeRs).lastModified()
        chipCache[probeRs]?.takeIf { it.stamp == stamp }?.let { return it.chips }
        val chips = run(probeRs, "chip", "list")?.let(ProbeRsOutput::chips).orEmpty()
        if (chips.isNotEmpty()) chipCache[probeRs] = ChipCache(stamp, chips)
        return chips
    }

    fun probes(probeRs: String): List<ProbeInfo> = run(probeRs, "list")?.let(ProbeRsOutput::probes).orEmpty()

    fun chipInfo(probeRs: String, chip: String): ChipInfo? = run(probeRs, "chip", "info", chip)?.let(ProbeRsOutput::chipInfo)

    fun version(probeRs: String): Triple<Int, Int, Int>? {
        val stamp = File(probeRs).lastModified()
        versionCache[probeRs]?.takeIf { it.first == stamp }?.let { return it.second }
        return run(probeRs, "--version")?.let(ProbeRsOutput::version).also { versionCache[probeRs] = stamp to it }
    }

    private fun run(exe: String, vararg args: String): String? {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Don't run probe-rs on the EDT" }
        val output = runCatching { CapturingProcessHandler(GeneralCommandLine(exe, *args)).runProcess(30_000) }.getOrNull() ?: return null
        return output.stdout.takeIf { output.exitCode == 0 || it.isNotBlank() }
    }

    companion object {
        /**
         * The oldest probe-rs this plugin is meant for. A best guess: the DAP server options it uses (stdio by default,
         * `rttChannelFormats`, progress events) are all in probe-rs-tools 0.24 and later.
         */
        val MINIMUM_VERSION = Triple(0, 24, 0)

        fun get(): ProbeRsTools = ApplicationManager.getApplication().service()

        fun isTooOld(version: Triple<Int, Int, Int>): Boolean =
            compareValuesBy(version, MINIMUM_VERSION, { it.first }, { it.second }, { it.third }) < 0

        fun format(version: Triple<Int, Int, Int>) = "${version.first}.${version.second}.${version.third}"
    }
}
