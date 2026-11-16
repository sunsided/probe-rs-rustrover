package dev.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.proberover.debugger.ProbeRsDriverConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class ProbeRsRelayTest {

    private fun relay(serverOut: ByteArrayOutputStream = ByteArrayOutputStream()) = ProbeRsRelay(
        clientIn = ByteArrayInputStream(ByteArray(0)),
        clientOut = ByteArrayOutputStream(),
        serverIn = ByteArrayInputStream(ByteArray(0)),
        serverOut = serverOut,
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
        val out = relay(toServer).handleServerMessage(
            """{"seq":12,"type":"event","event":"probe-rs-rtt-channel-config","body":{"channelNumber":0,"channelName":"defmt","dataFormat":"Defmt"}}""",
        )
        val event = json(out)
        assertEquals("output", event["event"].asString)
        assertEquals("console", event["body"].asJsonObject["category"].asString)

        val ack = json(DapReader(ByteArrayInputStream(toServer.toByteArray())).read())
        assertEquals("rttWindowOpened", ack["command"].asString)
        assertEquals(ProbeRsRelay.OWN_SEQ_BASE, ack["seq"].asInt)
        assertEquals(true, ack["arguments"].asJsonObject["windowIsOpen"].asBoolean)
    }

    @Test
    fun `rtt data becomes stdout output and the ack response is swallowed`() {
        val r = relay()
        val out = json(r.handleServerMessage("""{"seq":13,"type":"event","event":"probe-rs-rtt-data","body":{"channelNumber":0,"data":"INFO  blink\n"}}"""))
        assertEquals("stdout", out["body"].asJsonObject["category"].asString)
        assertEquals("INFO  blink\n", out["body"].asJsonObject["output"].asString)
        assertNull(r.handleServerMessage("""{"seq":14,"type":"response","request_seq":${ProbeRsRelay.OWN_SEQ_BASE},"command":"rttWindowOpened","success":true}"""))
    }

    @Test
    fun `warnings are important, unknown probe-rs events are dropped`() {
        val warn = json(relay().handleServerMessage("""{"seq":1,"type":"event","event":"probe-rs-show-message","body":{"severity":"warning","message":"Probe is busy"}}"""))
        assertEquals("important", warn["body"].asJsonObject["category"].asString)
        assertNull(relay().handleServerMessage("""{"seq":2,"type":"event","event":"probe-rs-create-prompt","body":{}}"""))
    }

    /**
     * Runs RelayMain as a real child process against a scripted fake of `probe-rs dap-server`
     * (src/test/resources/fake_probe_rs_dap.py), and drives it like the IDE would.
     */
    @Test(timeout = 60_000)
    fun `end to end through a child process`() {
        val script = File(javaClass.getResource("/fake_probe_rs_dap.py")!!.toURI())
        // Same command line the plugin hands to the IDE's DAP driver, with the fake server in place of probe-rs.
        val cmd = ProbeRsDriverConfiguration.relayCommandLine(listOf("python3", "-u", script.path))
        println("relay command: " + cmd.commandLineString)
        val proc = ProcessBuilder(cmd.getCommandLineList(null)).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        val ide = DapWriter(proc.outputStream)
        val fromRelay = DapReader(proc.inputStream)
        fun next(): JsonObject = json(fromRelay.read())

        ide.write("""{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"probe-rs"}}""")
        assertEquals("initialize", next()["command"].asString)
        assertEquals("initialized", next()["event"].asString)

        ide.write("""{"seq":2,"type":"request","command":"launch","arguments":{"chip":"RP2040"}}""")
        assertEquals("launch", next()["command"].asString)

        val seen = (1..3).map { next() }
        val outputs = seen.map { it["body"].asJsonObject["category"].asString to it["body"].asJsonObject["output"].asString }
        assertEquals("console", outputs[0].first)
        assertTrue(outputs[0].second.startsWith("RTT channel 0 (defmt, Defmt)"))
        assertEquals("stdout" to "0.000123 INFO  Hello from the board\n", outputs[1])
        assertEquals("important", outputs[2].first)

        ide.write("""{"seq":3,"type":"request","command":"disconnect","arguments":{}}""")
        assertEquals("disconnect", next()["command"].asString)
        proc.outputStream.close()
        assertTrue(proc.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, proc.exitValue())
    }
}
