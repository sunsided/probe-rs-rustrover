package dev.proberover.relay

import com.google.gson.JsonObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * A second connection from the relay back into the IDE, next to the DAP stream the IDE's debugger owns.
 * It carries what the IDE's DAP client would drop: RTT data, flashing progress and probe-rs messages.
 *
 * Protocol: the relay connects to `127.0.0.1:<port>`, sends the token on the first line, then one JSON object per
 * line. Every object has a `kind`: `rtt-channel`, `rtt-data`, `progress-start`, `progress-update`, `progress-end`,
 * `message`, `launch-failed`.
 */
interface SideChannel {
    /** Sends one event. Returns false if the channel isn't usable, so the caller can fall back. */
    fun send(event: JsonObject): Boolean
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

    override fun close() {
        runCatching { socket.close() }
    }
}
