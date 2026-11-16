package dev.proberover.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64

/** Receives the relay's side-channel events (see [dev.proberover.relay.SideChannel]). Called on a background thread. */
interface SideEventListener {
    fun onEvent(kind: String, event: JsonObject)

    /** The relay disconnected, or never connected. */
    fun onClosed() {}
}

/**
 * The IDE end of one debug session's side channel: a loopback server socket that accepts exactly one connection,
 * checks its token and hands every JSON line to the listener. Plain JDK code, so it's testable without the IDE.
 */
class LaunchChannel(
    private val listener: SideEventListener,
    acceptTimeoutMs: Int = 120_000,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = acceptTimeoutMs }
    val port: Int get() = server.localPort
    val token: String = ByteArray(18).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private val thread = Thread(::serve, "probe-rs side channel :$port").apply { isDaemon = true; start() }

    private fun serve() {
        try {
            server.accept().use { socket ->
                server.close()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                if (reader.readLine() != token) return
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    val kind = event.get("kind")?.asString ?: continue
                    runCatching { listener.onEvent(kind, event) }
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            runCatching { server.close() }
            listener.onClosed()
        }
    }

    /** Waits until the relay has disconnected and every event has been delivered. For tests. */
    fun awaitClosed(timeoutMs: Long) = thread.join(timeoutMs)

    override fun close() {
        runCatching { server.close() }
    }
}
