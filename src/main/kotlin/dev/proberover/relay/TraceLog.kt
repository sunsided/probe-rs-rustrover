package dev.proberover.relay

import java.io.File

/**
 * The last DAP messages of a session, for bug reports: kept in memory and written to [file] every second and at the
 * end. Long messages are shortened.
 */
class TraceLog(private val file: File, private val capacity: Int = 400, private val maxLength: Int = 1000) {
    private val lines = ArrayDeque<String>()
    @Volatile private var dirty = false

    init {
        file.parentFile?.mkdirs()
        Thread({
            while (true) {
                Thread.sleep(1000)
                if (dirty) flush()
            }
        }, "probe-rs relay trace").apply { isDaemon = true; start() }
    }

    fun add(direction: String, message: String) {
        val text = if (message.length > maxLength) message.take(maxLength) + "… (${message.length} chars)" else message
        synchronized(lines) {
            lines.addLast("${System.currentTimeMillis()} $direction $text")
            while (lines.size > capacity) lines.removeFirst()
        }
        dirty = true
    }

    fun flush() {
        val snapshot = synchronized(lines) { dirty = false; lines.toList() }
        runCatching { file.writeText(snapshot.joinToString("\n", postfix = "\n")) }
    }
}
