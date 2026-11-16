package dev.markusmayer.proberover.relay

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Answers the IDE's repeated stack requests while the target is halted.
 *
 * On every stop RustRover asks for `stackTrace` twice (1 frame, then 100) and `scopes` twice for the same frame, and
 * probe-rs unwinds the whole stack for each over a slow probe. The first `stackTrace` is fetched with
 * [FETCH_LEVELS] frames instead and trimmed for the IDE; later requests for those frames, and repeated `scopes`, are
 * answered from here. Anything that may run the core or change memory or registers drops the cache, and responses to
 * requests sent before that are not cached. Thread-safe: the relay's pumps and its command thread all call in.
 */
class StackCache {
    private var generation = 0
    private var threadId: Int? = null
    private var frames: JsonArray? = null
    private var totalFrames: Int? = null
    private val scopes = mutableMapOf<Int, JsonObject>()

    private data class Pending(val generation: Int, val command: String, val threadId: Int?, val levels: Int, val frameId: Int?)
    private val pending = mutableMapOf<Int, Pending>()

    /** A request from the IDE: the body to answer it with, or null to forward it (possibly after rewriting [request]). */
    @Synchronized
    fun onRequest(request: JsonObject): JsonObject? {
        val command = request.get("command")?.asString ?: return null
        val seq = request.get("seq")?.asInt ?: return null
        val arguments = request.getAsJsonObject("arguments") ?: JsonObject()
        when (command) {
            "stackTrace" -> {
                val thread = arguments.get("threadId")?.asInt
                val start = arguments.get("startFrame")?.asInt ?: 0
                val levels = arguments.get("levels")?.asInt ?: 0
                cachedFrames(thread, start, levels)?.let { return it }
                if (start == 0 && levels in 1 until FETCH_LEVELS) arguments.addProperty("levels", FETCH_LEVELS)
                if (start == 0) pending[seq] = Pending(generation, command, thread, levels, null)
            }
            "scopes" -> {
                val frameId = arguments.get("frameId")?.asInt ?: return null
                scopes[frameId]?.let { return it }
                pending[seq] = Pending(generation, command, null, 0, frameId)
            }
            in READ_ONLY -> {}
            else -> invalidate()
        }
        return null
    }

    /** A response from probe-rs: the response to forward instead, or null to forward it unchanged. */
    @Synchronized
    fun onResponse(response: JsonObject): JsonObject? {
        val p = pending.remove(response.get("request_seq")?.asInt ?: return null) ?: return null
        val body = response.getAsJsonObject("body")
        if (response.get("success")?.asBoolean != true || body == null) return null
        val current = p.generation == generation
        return when (p.command) {
            "stackTrace" -> {
                val all = body.getAsJsonArray("stackFrames") ?: return null
                val total = body.get("totalFrames")?.asInt
                if (current) {
                    threadId = p.threadId; frames = all.deepCopy(); totalFrames = total
                }
                if (p.levels == 0 || all.size() <= p.levels) null
                else response.deepCopy().also { it.add("body", frameBody(all, 0, p.levels, total ?: all.size())) }
            }
            else -> {
                if (current && p.frameId != null) scopes[p.frameId] = body.deepCopy()
                null
            }
        }
    }

    /** An event from probe-rs. */
    @Synchronized
    fun onEvent(event: String) {
        if (event in RUN_STATE_EVENTS) invalidate()
    }

    /** Something outside the IDE's requests changed the target, e.g. a memory write from a tool window. */
    @Synchronized
    fun invalidate() {
        generation++
        threadId = null; frames = null; totalFrames = null
        scopes.clear()
    }

    private fun cachedFrames(thread: Int?, start: Int, levels: Int): JsonObject? {
        val all = frames ?: return null
        if (thread != threadId) return null
        // probe-rs's totalFrames can exceed what it returned (e.g. its 500-frame unwinding limit): then only the
        // frames we hold are known.
        val complete = totalFrames == null || all.size() >= totalFrames!!
        val end = if (levels == 0) Int.MAX_VALUE else start + levels
        if (!complete && end > all.size()) return null
        return frameBody(all, start, levels, totalFrames ?: all.size())
    }

    private fun frameBody(all: JsonArray, start: Int, levels: Int, total: Int) = JsonObject().apply {
        val end = if (levels == 0) all.size() else minOf(all.size(), start + levels)
        add("stackFrames", JsonArray().apply { for (i in start until end) add(all[i].deepCopy()) })
        addProperty("totalFrames", total)
    }

    companion object {
        /** Frames fetched for the IDE's first, usually one-frame, request. */
        const val FETCH_LEVELS = 100

        /** Requests that neither run the core nor change memory or registers. */
        private val READ_ONLY = setOf(
            "threads", "variables", "source", "loadedSources", "modules", "readMemory", "disassemble",
            "exceptionInfo", "completions", "setBreakpoints", "setInstructionBreakpoints", "setFunctionBreakpoints",
            "setExceptionBreakpoints", "setDataBreakpoints", "breakpointLocations", "configurationDone",
        )
        private val RUN_STATE_EVENTS = setOf("stopped", "continued", "exited", "terminated", "invalidated")
    }
}
