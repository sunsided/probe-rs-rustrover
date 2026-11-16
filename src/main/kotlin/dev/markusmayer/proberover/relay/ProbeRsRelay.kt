package dev.markusmayer.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sits between the IDE's DAP client and `probe-rs dap-server`.
 *
 * Everything standard passes through untouched. probe-rs's own extensions, which the IDE's DAP client drops, are
 * handled here:
 * - `probe-rs-rtt-channel-config`: answered with the `rttWindowOpened` request probe-rs waits for before it streams,
 *   and announced on the side channel so the IDE can open a console tab.
 * - `probe-rs-rtt-data`: sent to the side channel. Without one, turned into a standard `output` event so the text
 *   still shows in the debug console.
 * - `probe-rs-show-message`: copied to the side channel and shown as an `output` event.
 * - other `probe-rs-*` events: dropped.
 *
 * The relay claims progress support in `initialize` and sends `progress*` events only to the side channel. Failed
 * `launch`/`attach` responses pass through and are also copied there, because the IDE shows them without advice.
 * Repeated stack requests while halted are answered by [StackCache].
 * Responses to the requests the relay injects itself are swallowed.
 */
class ProbeRsRelay(
    clientIn: InputStream,
    clientOut: OutputStream,
    serverIn: InputStream,
    serverOut: OutputStream,
    private val side: SideChannel = NoSideChannel,
    private val log: (String) -> Unit = {},
    /** Records traffic: `ide→`, `→probe-rs`, `probe-rs→`, `→ide`. */
    private val trace: (direction: String, message: String) -> Unit = { _, _ -> },
) {
    private val fromClient = DapReader(clientIn)
    private val toClient = DapWriter(clientOut)
    private val fromServer = DapReader(serverIn)
    private val toServer = DapWriter(serverOut)

    private val ownSeq = AtomicInteger(OWN_SEQ_BASE)
    private val rttChannels = mutableMapOf<Int, String>()
    /** Requests sent for IDE commands: DAP seq → command id. */
    private val pendingCommands = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val stackCache = StackCache()
    private val scopeTracker = ScopeTracker()
    /** IDE commands that arrived before `launch`/`attach` succeeded; probe-rs rejects anything before that handshake. */
    private val waiting = mutableListOf<JsonObject>()
    private var targetReady = false

    init {
        side.onCommand(::handleCommand)
    }

    /**
     * Turns an IDE command from the side channel into a DAP request to probe-rs. The response is returned to the IDE
     * as a `reply` event. Visible for tests.
     */
    fun handleCommand(command: JsonObject) {
        synchronized(waiting) {
            if (!targetReady) { waiting += command; return }
        }
        val id = command.get("id")?.asLong ?: return
        val (dapCommand, arguments) = when (command.str("op")) {
            "readMemory" -> "readMemory" to JsonObject().apply {
                addProperty("memoryReference", command.str("address"))
                addProperty("count", command.get("count")?.asInt ?: 0)
            }
            "writeMemory" -> "writeMemory" to JsonObject().apply {
                addProperty("memoryReference", command.str("address"))
                addProperty("data", command.str("data"))
            }
            "evaluate" -> "evaluate" to JsonObject().apply {
                addProperty("expression", command.str("expression"))
                addProperty("context", command.str("context") ?: "repl")
            }
            // The scopes of the frame RustRover shows, and a scope's or variable's children: for the probe-rs tab.
            "scopes" -> "scopes" to JsonObject().apply {
                addProperty("frameId", scopeTracker.frameId ?: return fail(id, "No stack frame yet: the target is running"))
            }
            "variables" -> "variables" to JsonObject().apply {
                addProperty("variablesReference", command.get("reference")?.asInt ?: 0)
                command.get("start")?.let { add("start", it) }
                command.get("count")?.let { add("count", it) }
            }
            "rtt-write" -> "evaluate" to JsonObject().apply {
                // probe-rs's REPL: `rtt write <channel> <data>`
                addProperty("expression", "rtt write ${command.get("channel")?.asInt ?: 0} ${command.str("data").orEmpty()}")
                addProperty("context", "repl")
            }
            else -> return fail(id, "Unknown command ${command.str("op")}")
        }
        if (dapCommand !in READ_ONLY_COMMANDS) stackCache.invalidate()
        val seq = ownSeq.getAndIncrement()
        pendingCommands[seq] = id
        val request = JsonObject().apply {
            addProperty("seq", seq)
            addProperty("type", "request")
            addProperty("command", dapCommand)
            add("arguments", arguments)
        }.toString()
        trace("relay→probe-rs", request)
        toServer.write(request)
    }

    /** Forwards IDE → probe-rs until the IDE closes its side. */
    fun pumpClientToServer() {
        while (true) {
            val msg = fromClient.read() ?: break
            trace("ide→probe-rs", msg)
            handleClientMessage(msg)?.let { toServer.write(it) }
        }
    }

    /**
     * Returns the IDE message to forward to probe-rs, or null when the relay answered it from [StackCache]. The IDE's
     * `initialize` doesn't claim progress support, so probe-rs would report flashing only as console text; the relay
     * claims it and consumes the events itself. Visible for tests.
     */
    fun handleClientMessage(raw: String): String? {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
        if (obj.str("type") != "request") return raw
        if (obj.str("command") == "initialize") {
            val arguments = obj.getAsJsonObject("arguments") ?: JsonObject().also { obj.add("arguments", it) }
            arguments.addProperty("supportsProgressReporting", true)
            return obj.toString().also { trace("→probe-rs", it) }
        }
        val before = obj.toString()
        if (obj.str("command") == "scopes") scopeTracker.onScopesRequest(obj)
        if (obj.str("command") == "evaluate") {
            // RustRover sends hovers and watches without a context; probe-rs only looks names up for one ("watch",
            // "hover"), otherwise it answers <invalid expression "led">.
            val arguments = obj.getAsJsonObject("arguments")
            if (arguments != null && !arguments.has("context")) arguments.addProperty("context", "watch")
        }
        stackCache.onRequest(obj)?.let { body ->
            if (obj.str("command") == "scopes") scopeTracker.onScopes(body)
            val response = JsonObject().apply {
                addProperty("seq", ownSeq.getAndIncrement())
                addProperty("type", "response")
                addProperty("request_seq", obj.get("seq").asInt)
                addProperty("success", true)
                addProperty("command", obj.str("command"))
                add("body", body)
            }.toString()
            trace("cache→ide", response)
            toClient.write(response)
            return null
        }
        val after = obj.toString()
        return if (after == before) raw else after.also { trace("→probe-rs", it) }
    }

    /** Forwards probe-rs → IDE, rewriting probe-rs extensions, until probe-rs closes its side. */
    fun pumpServerToClient() {
        while (true) {
            val msg = fromServer.read() ?: break
            trace("probe-rs→", msg)
            handleServerMessage(msg)?.let { trace("→ide", it); toClient.write(it) }
        }
    }

    /** Returns the message to forward to the IDE, or null to drop it. Visible for tests. */
    fun handleServerMessage(raw: String): String? {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
        when (obj.str("type")) {
            "response" -> {
                val requestSeq = obj.get("request_seq")?.asInt ?: return raw
                val failed = obj.get("success")?.asBoolean == false
                if (requestSeq >= OWN_SEQ_BASE) {
                    val commandId = pendingCommands.remove(requestSeq)
                    if (commandId != null) {
                        side.send(sideEvent("reply") {
                            addProperty("id", commandId)
                            addProperty("success", !failed)
                            obj.str("message")?.let { addProperty("message", errorText(obj)) }
                            obj.getAsJsonObject("body")?.let { add("body", it) }
                        })
                    } else if (failed) {
                        log("probe-rs rejected ${obj.str("command")}: ${obj.str("message")}")
                    }
                    return null
                }
                val command = obj.str("command")
                if (failed && (command == "launch" || command == "attach")) {
                    side.send(sideEvent("launch-failed") { addProperty("message", errorText(obj)) })
                }
                if (!failed && (command == "launch" || command == "attach")) {
                    val queued = synchronized(waiting) { targetReady = true; waiting.toList().also { waiting.clear() } }
                    queued.forEach(::handleCommand)
                }
                // Rewritten in place before the stack cache copies it, so cached answers carry the change too.
                val scopesChanged = command == "scopes" && !failed &&
                    obj.getAsJsonObject("body")?.let(scopeTracker::onScopes) == true
                return stackCache.onResponse(obj)?.toString() ?: if (scopesChanged) obj.toString() else raw
            }
            "event" -> {
                val event = obj.str("event") ?: return raw
                stackCache.onEvent(event)
                if (event == "stopped" || event == "continued") scopeTracker.onRunStateChanged()
                val body = obj.getAsJsonObject("body") ?: JsonObject()
                val seq = obj.get("seq")?.asInt ?: 0
                when (event) {
                    "progressStart", "progressUpdate", "progressEnd" -> {
                        side.send(sideEvent(PROGRESS_KINDS.getValue(event)) { copy(body, "progressId", "title", "message", "percentage") })
                        // The IDE didn't ask for progress events (the relay did): show them only through the side channel.
                        return null
                    }
                }
                if (!event.startsWith("probe-rs-")) return raw
                return when (event) {
                    "probe-rs-rtt-channel-config" -> onRttChannel(seq, body)
                    "probe-rs-rtt-data" -> onRttData(seq, body)
                    "probe-rs-show-message" -> onShowMessage(seq, body)
                    else -> null
                }
            }
            else -> return raw
        }
    }

    private fun onRttChannel(seq: Int, body: JsonObject): String {
        val channel = body.get("channelNumber")?.asInt ?: 0
        val name = body.str("channelName") ?: "channel $channel"
        val format = body.str("dataFormat") ?: "String"
        synchronized(rttChannels) { rttChannels[channel] = name }

        val ack = JsonObject().apply {
            addProperty("seq", ownSeq.getAndIncrement())
            addProperty("type", "request")
            addProperty("command", "rttWindowOpened")
            add("arguments", JsonObject().apply {
                addProperty("channelNumber", channel)
                addProperty("windowIsOpen", true)
            })
        }
        trace("relay→probe-rs", ack.toString())
        toServer.write(ack.toString())
        side.send(sideEvent("rtt-channel") {
            addProperty("channel", channel)
            addProperty("name", name)
            addProperty("format", format)
        })
        return output(seq, "console", "RTT channel $channel ($name, $format) connected\n")
    }

    private fun onRttData(seq: Int, body: JsonObject): String? {
        val channel = body.get("channelNumber")?.asInt ?: 0
        val data = stripAnsi(body.str("data") ?: "")
        val name = synchronized(rttChannels) { rttChannels[channel] } ?: "rtt$channel"
        val sent = side.send(sideEvent("rtt-data") {
            addProperty("channel", channel)
            addProperty("name", name)
            addProperty("data", data)
        })
        if (sent) return null

        val multiChannel = synchronized(rttChannels) { rttChannels.size > 1 }
        val text = if (multiChannel && channel != 0) {
            data.lineSequence().filter { it.isNotEmpty() }.joinToString("") { "[$name] $it\n" }
        } else data
        return output(seq, "stdout", text)
    }

    private fun onShowMessage(seq: Int, body: JsonObject): String {
        val severity = body.str("severity") ?: "information"
        val message = body.str("message") ?: ""
        side.send(sideEvent("message") {
            addProperty("severity", severity)
            addProperty("message", message)
        })
        val category = if (severity == "warning" || severity == "error") "important" else "console"
        return output(seq, category, "probe-rs $severity: $message\n")
    }

    private fun output(seq: Int, category: String, text: String): String = JsonObject().apply {
        addProperty("seq", seq)
        addProperty("type", "event")
        addProperty("event", "output")
        add("body", JsonObject().apply {
            addProperty("category", category)
            addProperty("output", text)
        })
    }.toString()

    private fun fail(id: Long, message: String) {
        side.send(sideEvent("reply") {
            addProperty("id", id)
            addProperty("success", false)
            addProperty("message", message)
        })
    }

    private fun sideEvent(kind: String, fill: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("kind", kind)
        fill()
    }

    private fun JsonObject.copy(from: JsonObject, vararg keys: String) =
        keys.forEach { k -> from.get(k)?.takeIf { !it.isJsonNull }?.let { add(k, it) } }

    private fun errorText(response: JsonObject): String {
        val format = response.getAsJsonObject("body")?.getAsJsonObject("error")?.str("format")
        return listOfNotNull(response.str("message"), format).distinct().joinToString(": ")
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        /** Requests the relay sends itself use sequence numbers from here, far above anything the IDE uses. */
        const val OWN_SEQ_BASE = 1_000_000

        /** IDE commands that leave the target as it is, so the stack cache stays valid. */
        private val READ_ONLY_COMMANDS = setOf("readMemory", "scopes", "variables")

        private val PROGRESS_KINDS = mapOf(
            "progressStart" to "progress-start",
            "progressUpdate" to "progress-update",
            "progressEnd" to "progress-end",
        )
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        fun stripAnsi(s: String): String = s.replace(ANSI, "")
    }
}
