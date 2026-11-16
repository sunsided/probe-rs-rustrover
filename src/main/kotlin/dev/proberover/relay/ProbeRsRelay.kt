package dev.proberover.relay

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
 * - `probe-rs-rtt-channel-config`: answered with the `rttWindowOpened` request probe-rs waits for before it streams.
 * - `probe-rs-rtt-data`: turned into a standard `output` event, so RTT/defmt text shows in the debug console.
 * - `probe-rs-show-message`: turned into an `output` event (`important` for warnings and errors).
 * - other `probe-rs-*` events: dropped.
 * Responses to the requests the relay injects itself are swallowed.
 */
class ProbeRsRelay(
    clientIn: InputStream,
    clientOut: OutputStream,
    serverIn: InputStream,
    serverOut: OutputStream,
    private val log: (String) -> Unit = {},
) {
    private val fromClient = DapReader(clientIn)
    private val toClient = DapWriter(clientOut)
    private val fromServer = DapReader(serverIn)
    private val toServer = DapWriter(serverOut)

    private val ownSeq = AtomicInteger(OWN_SEQ_BASE)
    private val rttChannels = mutableMapOf<Int, String>()

    /** Forwards IDE → probe-rs until the IDE closes its side. */
    fun pumpClientToServer() {
        while (true) {
            val msg = fromClient.read() ?: break
            toServer.write(msg)
        }
    }

    /** Forwards probe-rs → IDE, rewriting probe-rs extensions, until probe-rs closes its side. */
    fun pumpServerToClient() {
        while (true) {
            val msg = fromServer.read() ?: break
            handleServerMessage(msg)?.let(toClient::write)
        }
    }

    /** Returns the message to forward to the IDE, or null to drop it. Visible for tests. */
    fun handleServerMessage(raw: String): String? {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
        when (obj.str("type")) {
            "response" -> {
                val requestSeq = obj.get("request_seq")?.asInt ?: return raw
                if (requestSeq < OWN_SEQ_BASE) return raw
                if (obj.get("success")?.asBoolean == false) {
                    log("probe-rs rejected ${obj.str("command")}: ${obj.str("message")}")
                }
                return null
            }
            "event" -> {
                val event = obj.str("event") ?: return raw
                if (!event.startsWith("probe-rs-")) return raw
                val body = obj.getAsJsonObject("body") ?: JsonObject()
                val seq = obj.get("seq")?.asInt ?: 0
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
        toServer.write(ack.toString())
        return output(seq, "console", "RTT channel $channel ($name, $format) connected\n")
    }

    private fun onRttData(seq: Int, body: JsonObject): String {
        val channel = body.get("channelNumber")?.asInt ?: 0
        val data = body.str("data") ?: ""
        val multiChannel = synchronized(rttChannels) { rttChannels.size > 1 }
        val text = if (multiChannel && channel != 0) {
            val name = synchronized(rttChannels) { rttChannels[channel] } ?: "rtt$channel"
            data.lineSequence().filter { it.isNotEmpty() }.joinToString("") { "[$name] $it\n" }
        } else data
        return output(seq, "stdout", text)
    }

    private fun onShowMessage(seq: Int, body: JsonObject): String {
        val severity = body.str("severity") ?: "information"
        val message = body.str("message") ?: ""
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

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        /** Requests the relay sends itself use sequence numbers from here, far above anything the IDE uses. */
        const val OWN_SEQ_BASE = 1_000_000
    }
}
