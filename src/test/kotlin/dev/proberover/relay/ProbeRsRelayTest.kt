package dev.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.proberover.debugger.ProbeRsDriverConfiguration
import dev.proberover.session.LaunchChannel
import dev.proberover.session.SideEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

class ProbeRsRelayTest {

    private class CollectingSide : SideChannel {
        val events = mutableListOf<JsonObject>()
        override fun send(event: JsonObject): Boolean = events.add(event)
    }

    private fun relay(serverOut: ByteArrayOutputStream = ByteArrayOutputStream(), side: SideChannel = NoSideChannel) = ProbeRsRelay(
        clientIn = ByteArrayInputStream(ByteArray(0)),
        clientOut = ByteArrayOutputStream(),
        serverIn = ByteArrayInputStream(ByteArray(0)),
        serverOut = serverOut,
        side = side,
    )

    private fun json(s: String?) = JsonParser.parseString(s!!).asJsonObject

    @Test
    fun `standard messages pass through unchanged`() {
        val msg = """{"seq":7,"type":"event","event":"stopped","body":{"reason":"breakpoint","threadId":1}}"""
        assertEquals(msg, relay().handleServerMessage(msg))
        val resp = """{"seq":8,"type":"response","request_seq":3,"command":"next","success":true}"""
        assertEquals(resp, relay().handleServerMessage(resp))
    }

    @Test
    fun `rtt channel config is acknowledged to probe-rs and announced to the IDE`() {
        val toServer = ByteArrayOutputStream()
        val side = CollectingSide()
        val out = relay(toServer, side).handleServerMessage(
            """{"seq":12,"type":"event","event":"probe-rs-rtt-channel-config","body":{"channelNumber":0,"channelName":"defmt","dataFormat":"Defmt"}}""",
        )
        assertEquals("console", json(out)["body"].asJsonObject["category"].asString)

        val ack = json(DapReader(ByteArrayInputStream(toServer.toByteArray())).read())
        assertEquals("rttWindowOpened", ack["command"].asString)
        assertEquals(ProbeRsRelay.OWN_SEQ_BASE, ack["seq"].asInt)
        assertEquals(true, ack["arguments"].asJsonObject["windowIsOpen"].asBoolean)

        assertEquals("rtt-channel", side.events.single()["kind"].asString)
        assertEquals("defmt", side.events.single()["name"].asString)
    }

    @Test
    fun `without a side channel, rtt data becomes stdout output with ANSI removed`() {
        val r = relay()
        val out = json(r.handleServerMessage("""{"seq":13,"type":"event","event":"probe-rs-rtt-data","body":{"channelNumber":0,"data":"\u001b[32mINFO \u001b[0m blink\n"}}"""))
        assertEquals("stdout", out["body"].asJsonObject["category"].asString)
        assertEquals("INFO  blink\n", out["body"].asJsonObject["output"].asString)
        assertNull(r.handleServerMessage("""{"seq":14,"type":"response","request_seq":${ProbeRsRelay.OWN_SEQ_BASE},"command":"rttWindowOpened","success":true}"""))
    }

    @Test
    fun `with a side channel, rtt data goes there and not to the debug console`() {
        val side = CollectingSide()
        val out = relay(side = side).handleServerMessage("""{"seq":13,"type":"event","event":"probe-rs-rtt-data","body":{"channelNumber":0,"data":"INFO blink\n"}}""")
        assertNull(out)
        assertEquals("rtt-data", side.events.single()["kind"].asString)
        assertEquals("INFO blink\n", side.events.single()["data"].asString)
    }

    @Test
    fun `progress passes through and is copied, failed launches are copied`() {
        val side = CollectingSide()
        val r = relay(side = side)
        val progress = """{"seq":3,"type":"event","event":"progressUpdate","body":{"progressId":"1","percentage":42.0,"message":"Erasing"}}"""
        assertEquals(progress, r.handleServerMessage(progress))
        val failed = """{"seq":4,"type":"response","request_seq":2,"command":"launch","success":false,"message":"Cannot launch","body":{"error":{"id":1,"format":"No connected probes were found."}}}"""
        assertEquals(failed, r.handleServerMessage(failed))

        assertEquals(listOf("progress-update", "launch-failed"), side.events.map { it["kind"].asString })
        assertEquals(42.0, side.events[0]["percentage"].asDouble, 0.0)
        assertEquals("Cannot launch: No connected probes were found.", side.events[1]["message"].asString)
    }

    @Test
    fun `warnings are important, unknown probe-rs events are dropped`() {
        val warn = json(relay().handleServerMessage("""{"seq":1,"type":"event","event":"probe-rs-show-message","body":{"severity":"warning","message":"Probe is busy"}}"""))
        assertEquals("important", warn["body"].asJsonObject["category"].asString)
        assertNull(relay().handleServerMessage("""{"seq":2,"type":"event","event":"probe-rs-create-prompt","body":{}}"""))
    }

    // End to end: RelayMain as a real child process, the plugin's own command line, a scripted fake of
    // `probe-rs dap-server` (src/test/resources/fake_probe_rs_dap.py), driven like the IDE would.

    private val script get() = File(javaClass.getResource("/fake_probe_rs_dap.py")!!.toURI())

    private class Ide(cmd: List<String>) {
        val proc: Process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val writer = DapWriter(proc.outputStream)
        private val reader = DapReader(proc.inputStream)
        private var seq = 0
        fun request(command: String, args: String = "{}") = writer.write("""{"seq":${++seq},"type":"request","command":"$command","arguments":$args}""")
        /** Next message, skipping progress events (the IDE's DAP client ignores them). */
        fun next(): JsonObject {
            while (true) {
                val msg = JsonParser.parseString(reader.read()!!).asJsonObject
                if (msg["event"]?.asString?.startsWith("progress") != true) return msg
            }
        }
        fun finish(): Int {
            request("disconnect")
            assertEquals("disconnect", next()["command"].asString)
            proc.outputStream.close()
            assertTrue(proc.waitFor(20, TimeUnit.SECONDS))
            return proc.exitValue()
        }
    }

    @Test(timeout = 60_000)
    fun `end to end without side channel - RTT lands in the debug console`() {
        val ide = Ide(ProbeRsDriverConfiguration.relayCommandLine(listOf("python3", "-u", script.path)).getCommandLineList(null))
        ide.request("initialize", """{"adapterID":"probe-rs"}""")
        assertEquals("initialize", ide.next()["command"].asString)
        assertEquals("initialized", ide.next()["event"].asString)

        ide.request("launch", """{"chip":"RP2040"}""")
        assertEquals("launch", ide.next()["command"].asString)
        val outputs = (1..3).map { ide.next()["body"].asJsonObject }.map { it["category"].asString to it["output"].asString }
        assertEquals("console", outputs[0].first)
        assertTrue(outputs[0].second.startsWith("RTT channel 0 (defmt, Defmt)"))
        assertEquals("stdout" to "0.000123 INFO  Hello from the board\n", outputs[1])
        assertEquals("important", outputs[2].first)
        assertEquals(0, ide.finish())
    }

    @Test(timeout = 60_000)
    fun `end to end with side channel - RTT, progress and messages reach the IDE listener`() {
        val events = Collections.synchronizedList(mutableListOf<JsonObject>())
        val channel = LaunchChannel(object : SideEventListener {
            override fun onEvent(kind: String, event: JsonObject) { events += event }
        })
        val cmd = ProbeRsDriverConfiguration.relayCommandLine(listOf("python3", "-u", script.path), channel.port to channel.token)
        val ide = Ide(cmd.getCommandLineList(null))
        ide.request("initialize")
        ide.next(); ide.next()
        ide.request("launch", """{"chip":"RP2040"}""")
        assertEquals("launch", ide.next()["command"].asString)
        assertEquals("console", ide.next()["body"].asJsonObject["category"].asString) // RTT channel announcement
        assertEquals("important", ide.next()["body"].asJsonObject["category"].asString) // the warning; RTT data went to the side channel
        assertEquals(0, ide.finish())
        channel.awaitClosed(10_000)

        assertEquals(
            listOf("progress-start", "progress-update", "progress-end", "rtt-channel", "rtt-data", "message"),
            events.map { it["kind"].asString },
        )
        assertEquals("0.000123 INFO  Hello from the board\n", events[4]["data"].asString)
    }

    @Test(timeout = 60_000)
    fun `end to end failed launch is reported on the side channel`() {
        val events = Collections.synchronizedList(mutableListOf<JsonObject>())
        val channel = LaunchChannel(object : SideEventListener {
            override fun onEvent(kind: String, event: JsonObject) { events += event }
        })
        val ide = Ide(ProbeRsDriverConfiguration.relayCommandLine(listOf("python3", "-u", script.path), channel.port to channel.token).getCommandLineList(null))
        ide.request("initialize"); ide.next(); ide.next()
        ide.request("launch", """{"chip":"BROKEN"}""")
        assertEquals(false, ide.next()["success"].asBoolean)
        assertEquals(0, ide.finish())
        channel.awaitClosed(10_000)
        assertEquals("launch-failed", events.single()["kind"].asString)
    }

    @Test(timeout = 30_000)
    fun `side channel rejects a wrong token`() {
        val events = Collections.synchronizedList(mutableListOf<JsonObject>())
        val channel = LaunchChannel(object : SideEventListener {
            override fun onEvent(kind: String, event: JsonObject) { events += event }
        })
        SocketSideChannel(channel.port, "not-the-token").use { it.send(JsonObject().apply { addProperty("kind", "message") }) }
        channel.awaitClosed(10_000)
        assertTrue(events.isEmpty())
    }
}
