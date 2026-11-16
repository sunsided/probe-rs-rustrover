package dev.markusmayer.proberover.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Receives the relay's side-channel events (see [dev.markusmayer.proberover.relay.SideChannel]). Called on a background thread. */
interface SideEventListener {
    fun onEvent(kind: String, event: JsonObject)

    /** The relay connected; [LaunchChannel.request] works from now on. */
    fun onConnected(channel: LaunchChannel) {}

    /** The relay disconnected, or never connected. */
    fun onClosed() {}
}

/** probe-rs or the relay refused a command. */
class ProbeRsCommandException(message: String) : Exception(message)

/**
 * The IDE end of one debug session's side channel: a loopback server socket that accepts exactly one connection and
 * checks its token. Events go to the listener; [request] sends commands back to the relay, which runs them as DAP
 * requests against probe-rs. Plain JDK code, so it's testable without the IDE.
 */
class LaunchChannel(
    private val listener: SideEventListener,
    acceptTimeoutMs: Int = 120_000,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = acceptTimeoutMs }
    val port: Int get() = server.localPort
    val token: String = ByteArray(18).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()

    private val thread = Thread(::serve, "probe-rs side channel :$port").apply { isDaemon = true; start() }

    val isConnected: Boolean get() = writer != null && socket?.isClosed == false

    /**
     * Sends a command (`readMemory`, `writeMemory`, `evaluate`, `rtt-write`) and completes with probe-rs's response
     * body, or exceptionally with [ProbeRsCommandException].
     */
    fun request(op: String, fill: JsonObject.() -> Unit = {}): CompletableFuture<JsonObject> {
        val out = writer ?: return CompletableFuture.failedFuture(ProbeRsCommandException("No probe-rs session"))
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val command = JsonObject().apply {
            addProperty("id", id)
            addProperty("op", op)
            fill()
        }
        try {
            synchronized(out) {
                out.write(command.toString())
                out.write("\n")
                out.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(ProbeRsCommandException("The probe-rs session has ended"))
        }
        return future
    }

    private fun serve() {
        try {
            server.accept().use { socket ->
                server.close()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                if (reader.readLine() != token) return
                this.socket = socket
                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                runCatching { listener.onConnected(this) }
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    val kind = event.get("kind")?.asString ?: continue
                    if (kind == "reply") {
                        completeReply(event)
                    } else {
                        runCatching { listener.onEvent(kind, event) }
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            writer = null
            runCatching { server.close() }
            pending.values.forEach { it.completeExceptionally(ProbeRsCommandException("The probe-rs session has ended")) }
            pending.clear()
            listener.onClosed()
        }
    }

    private fun completeReply(event: JsonObject) {
        val future = pending.remove(event.get("id")?.asLong ?: return) ?: return
        if (event.get("success")?.asBoolean == true) {
            future.complete(event.getAsJsonObject("body") ?: JsonObject())
        } else {
            future.completeExceptionally(ProbeRsCommandException(event.get("message")?.asString ?: "probe-rs refused the request"))
        }
    }

    /** Waits until the relay has disconnected and every event has been delivered. For tests. */
    fun awaitClosed(timeoutMs: Long) = thread.join(timeoutMs)

    override fun close() {
        runCatching { server.close() }
        runCatching { socket?.close() }
    }
}
