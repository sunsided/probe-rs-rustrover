package dev.proberover.defmt

/** defmt log levels, as defmt prints them. */
enum class DefmtLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** What a console line tells us: an optional log level and an optional source location, with their offsets. */
data class DefmtLine(
    val level: DefmtLevel?,
    val levelRange: IntRange?,
    val path: String?,
    val lineNumber: Int?,
    val locationRange: IntRange?,
)

/**
 * Recognizes the lines probe-rs prints for defmt logs, in both its formats:
 *
 * - one line: `0.000123 [INFO ] Hello from the board (main.rs:42)` and similar
 * - full: `0.000123 INFO  Hello from the board` followed by `└─ blinky::__embassy_main @ src/main.rs:42`
 *
 * The parser is deliberately lenient (any timestamp, optional brackets, an optional `[channel] ` prefix from the relay)
 * but only treats a level as a level when it is the first word after the timestamp, so ordinary text such as
 * "INFO" in a sentence is left alone.
 */
object DefmtLineParser {
    private val LEVEL = Regex(
        """^(?:\[[^\]\s]+]\s+)?(?:\[?\s*[\d.:]+\s*]?\s+)?\[?\s*(TRACE|DEBUG|INFO|WARN|ERROR)\s*]?(?=\s|$)""",
    )
    private val LOCATION = Regex("""((?:[A-Za-z]:)?[^\s:()@'"]*[^\s:()@'"/\\]\.rs):(\d+)(?::\d+)?""")

    fun parse(line: String): DefmtLine? {
        val text = line.trimEnd('\n', '\r')
        val level = LEVEL.find(text)
        val location = LOCATION.findAll(text).lastOrNull()
        if (level == null && location == null) return null
        val levelGroup = level?.groups?.get(1)
        return DefmtLine(
            level = levelGroup?.value?.let(DefmtLevel::valueOf),
            levelRange = levelGroup?.range,
            path = location?.groupValues?.get(1),
            lineNumber = location?.groupValues?.get(2)?.toIntOrNull(),
            locationRange = location?.groups?.get(0)?.range,
        )
    }
}
