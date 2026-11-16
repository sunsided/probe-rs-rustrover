package dev.proberover.relay

import kotlin.system.exitProcess

/**
 * Entry point of the relay child process:
 * `java -cp … dev.proberover.relay.RelayMain [--side-channel <port> <token>] [--trace-file <path>] -- probe-rs dap-server`.
 *
 * The IDE's `DapDriver` talks DAP over this process's stdin/stdout; the relay starts probe-rs and talks to it over
 * probe-rs's stdin/stdout. probe-rs's stderr is passed through, so its log lines reach the IDE. With
 * `--side-channel`, the relay also connects back to the IDE for RTT data, progress and messages (see [SideChannel]).
 */
object RelayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sep = args.indexOf("--")
        val options = if (sep >= 0) args.take(sep) else emptyList()
        val server = if (sep >= 0) args.drop(sep + 1) else args.toList()
        if (server.isEmpty()) {
            System.err.println("usage: RelayMain [--side-channel <port> <token>] -- <probe-rs> dap-server [args…]")
            exitProcess(2)
        }

        val side: SideChannel = options.indexOf("--side-channel").takeIf { it >= 0 }?.let { i ->
            val port = options.getOrNull(i + 1)?.toIntOrNull()
            val token = options.getOrNull(i + 2)
            if (port == null || token == null) null
            else runCatching { SocketSideChannel(port, token) }
                .onFailure { System.err.println("[probe-rs relay] side channel unavailable, RTT goes to the debug console: $it") }
                .getOrNull()
        } ?: NoSideChannel

        val traceLog = options.indexOf("--trace-file").takeIf { it >= 0 }?.let { options.getOrNull(it + 1) }
            ?.let { runCatching { TraceLog(java.io.File(it)) }.getOrNull() }

        val process = ProcessBuilder(server).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val relay = ProbeRsRelay(
            clientIn = System.`in`,
            clientOut = System.out,
            serverIn = process.inputStream,
            serverOut = process.outputStream,
            side = side,
            log = { System.err.println("[probe-rs relay] $it") },
            trace = { direction, message -> traceLog?.add(direction, message) },
        )

        val upstream = Thread({
            runCatching { relay.pumpClientToServer() }
            runCatching { process.outputStream.close() }
        }, "dap-ide-to-probe-rs").apply { isDaemon = true; start() }

        runCatching { relay.pumpServerToClient() }.onFailure { System.err.println("[probe-rs relay] $it") }
        upstream.interrupt()
        (side as? AutoCloseable)?.close()
        traceLog?.flush()
        exitProcess(process.waitFor())
    }
}
