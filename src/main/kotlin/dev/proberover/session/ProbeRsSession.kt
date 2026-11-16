package dev.proberover.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.xdebugger.XDebugSession
import dev.proberover.model.ProbeRsTarget
import java.util.Base64
import java.util.concurrent.CompletableFuture

/** One running probe-rs debug session, as seen from the IDE. */
class ProbeRsSession(
    val project: Project,
    val target: ProbeRsTarget,
    /** The Cargo run configuration being debugged, if known. */
    val configurationName: String?,
) {
    @Volatile var channel: LaunchChannel? = null
        internal set

    @Volatile var debugSession: XDebugSession? = null
        internal set

    val isConnected: Boolean get() = channel?.isConnected == true

    /** Reads target memory through probe-rs. Works while halted and, for most cores, while running. */
    fun readMemory(address: Long, count: Int): CompletableFuture<ByteArray> =
        request("readMemory") {
            addProperty("address", "0x" + address.toString(16))
            addProperty("count", count)
        }.thenApply { body -> body.get("data")?.asString?.let(Base64.getDecoder()::decode) ?: ByteArray(0) }

    fun writeMemory(address: Long, data: ByteArray): CompletableFuture<Unit> =
        request("writeMemory") {
            addProperty("address", "0x" + address.toString(16))
            addProperty("data", Base64.getEncoder().encodeToString(data))
        }.thenApply { }

    /** Sends a line of text to the target's RTT down channel. probe-rs appends the newline. */
    fun rttWrite(channel: Int, text: String): CompletableFuture<Unit> =
        request("rtt-write") {
            addProperty("channel", channel)
            addProperty("data", text)
        }.thenApply { }

    private fun request(op: String, fill: com.google.gson.JsonObject.() -> Unit) =
        channel?.request(op, fill) ?: CompletableFuture.failedFuture(ProbeRsCommandException("The probe-rs session isn't connected yet"))
}

/** Notifications about probe-rs sessions in a project. Delivered on the EDT. */
interface ProbeRsSessionListener {
    fun sessionStarted(session: ProbeRsSession) {}
    fun sessionEnded(session: ProbeRsSession) {}
    /** The target stopped at a breakpoint, after a step, or because the user paused it. */
    fun targetHalted(session: ProbeRsSession) {}
    fun targetResumed(session: ProbeRsSession) {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(ProbeRsSessionListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** The project's probe-rs sessions. Usually zero or one. */
@Service(Service.Level.PROJECT)
class ProbeRsSessions(private val project: Project) {
    private val sessions = mutableListOf<ProbeRsSession>()

    /** The most recently started session that is still connected. */
    val active: ProbeRsSession? get() = synchronized(sessions) { sessions.lastOrNull { it.isConnected } }

    internal fun started(session: ProbeRsSession) {
        synchronized(sessions) { sessions += session }
        publisher().sessionStarted(session)
    }

    internal fun ended(session: ProbeRsSession) {
        synchronized(sessions) { sessions -= session }
        publisher().sessionEnded(session)
    }

    internal fun publisher(): ProbeRsSessionListener = project.messageBus.syncPublisher(ProbeRsSessionListener.TOPIC)

    companion object {
        fun get(project: Project): ProbeRsSessions = project.service()
    }
}
