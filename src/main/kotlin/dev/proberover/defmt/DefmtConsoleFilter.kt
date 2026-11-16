package dev.proberover.defmt

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.proberover.settings.ProbeRsProjectService
import java.io.File

/**
 * Colors defmt log levels and turns `src/main.rs:42` locations into links, in any console: the RTT tab of a debug
 * session, and plain **Run** output from `probe-rs run`.
 */
class DefmtConsoleFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val parsed = DefmtLineParser.parse(line) ?: return null
        val start = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()

        parsed.levelRange?.let { range ->
            val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(levelKey(parsed.level!!))
            items += Filter.ResultItem(start + range.first, start + range.last + 1, null, attributes)
        }
        val path = parsed.path
        val lineNumber = parsed.lineNumber
        val range = parsed.locationRange
        if (path != null && lineNumber != null && range != null) {
            resolve(path)?.let { file ->
                items += Filter.ResultItem(
                    start + range.first,
                    start + range.last + 1,
                    OpenFileHyperlinkInfo(project, file, (lineNumber - 1).coerceAtLeast(0)),
                )
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(path: String): VirtualFile? {
        val normalized = path.replace('\\', '/')
        if (File(path).isAbsolute) return LocalFileSystem.getInstance().findFileByPath(normalized)
        return project.guessProjectDir()?.findFileByRelativePath(normalized)
    }

    companion object {
        fun levelKey(level: DefmtLevel): TextAttributesKey = when (level) {
            DefmtLevel.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT_KEY
            DefmtLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT_KEY
            DefmtLevel.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT_KEY
            DefmtLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT_KEY
            DefmtLevel.TRACE -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT_KEY
        }
    }
}

/** Adds [DefmtConsoleFilter] to consoles of projects that look like embedded Rust, and nowhere else. */
class DefmtConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> {
        val detected = runCatching { project.service<ProbeRsProjectService>().detect() }.getOrNull()
        val embedded = detected != null && (detected.probeRs != null || detected.isEmbeddedTarget)
        return if (embedded) arrayOf(DefmtConsoleFilter(project)) else Filter.EMPTY_ARRAY
    }
}
