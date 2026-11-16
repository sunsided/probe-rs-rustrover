package dev.proberover.relay

import kotlin.system.exitProcess

/**
 * Entry point of the relay child process: `java -cp … dev.proberover.relay.RelayMain -- probe-rs dap-server`.
 *
 * The IDE's `DapDriver` talks DAP over this process's stdin/stdout; the relay starts probe-rs and talks to it over
 * probe-rs's stdin/stdout. probe-rs's stderr is passed through, so its log lines reach the IDE.
 */
object RelayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sep = args.indexOf("--")
        val server = if (sep >= 0) args.drop(sep + 1) else args.toList()
        if (server.isEmpty()) {
            System.err.println("usage: RelayMain -- <probe-rs> dap-server [args…]")
            exitProcess(2)
        }

        val process = ProcessBuilder(server).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val relay = ProbeRsRelay(
            clientIn = System.`in`,
            clientOut = System.out,
            serverIn = process.inputStream,
            serverOut = process.outputStream,
            log = { System.err.println("[probe-rs relay] $it") },
        )

        val upstream = Thread({
            runCatching { relay.pumpClientToServer() }
            runCatching { process.outputStream.close() }
        }, "dap-ide-to-probe-rs").apply { isDaemon = true; start() }

        runCatching { relay.pumpServerToClient() }.onFailure { System.err.println("[probe-rs relay] $it") }
        upstream.interrupt()
        exitProcess(process.waitFor())
    }
}
