package dev.markusmayer.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * A second connection between the relay and the IDE, next to the DAP stream the IDE's debugger owns.
 *
 * Relay → IDE: what the IDE's DAP client would drop. One JSON object per line, each with a `kind`: `rtt-channel`,
 * `rtt-data`, `progress-start`, `progress-update`, `progress-end`, `message`, `launch-failed`, `reply`.
 *
 * IDE → relay: commands the relay turns into DAP requests to probe-rs, each with an `id` and an `op`:
 * `readMemory` (`address`, `count`), `writeMemory` (`address`, `data` as base64), `evaluate` (`expression`),
 * `rtt-write` (`channel`, `data`). The answer comes back as a `reply` event with the same `id`.
 *
 * The relay connects to `127.0.0.1:<port>` and sends the token on the first line.
 */
interface SideChannel {
    /** Sends one event. Returns false if the channel isn't usable, so the caller can fall back. */
    fun send(event: JsonObject): Boolean

    /** Starts delivering IDE commands to [handler], on a background thread. */
    fun onCommand(handler: (JsonObject) -> Unit) {}
}

object NoSideChannel : SideChannel {
    override fun send(event: JsonObject) = false
}

class SocketSideChannel(port: Int, token: String) : SideChannel, AutoCloseable {
    private val socket = Socket(InetAddress.getLoopbackAddress(), port)
    private val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    @Volatile private var broken = false

    init {
        out.write(token)
        out.write("\n")
        out.flush()
    }

    @Synchronized
    override fun send(event: JsonObject): Boolean {
        if (broken) return false
        return try {
            out.write(event.toString())
            out.write("\n")
            out.flush()
            true
        } catch (_: Exception) {
            broken = true
            false
        }
    }

    override fun onCommand(handler: (JsonObject) -> Unit) {
        Thread({
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                val command = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                runCatching { handler(command) }
            }
        }, "probe-rs relay side channel commands").apply { isDaemon = true; start() }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
