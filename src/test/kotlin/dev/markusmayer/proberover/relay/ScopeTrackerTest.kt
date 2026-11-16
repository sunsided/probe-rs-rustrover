package dev.markusmayer.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeTrackerTest {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // What probe-rs 0.32 sent for blinky_basic with an SVD file.
    private fun scopes() = json("""{"scopes":[
        {"expensive":true,"name":"Peripherals","presentationHint":"registers","variablesReference":1},
        {"expensive":true,"name":"Static","presentationHint":"statics","variablesReference":6727},
        {"expensive":true,"name":"Registers","presentationHint":"registers","variablesReference":6729},
        {"expensive":false,"name":"Variables","presentationHint":"locals","variablesReference":6728}]}""")

    @Test
    fun `RustRover's Registers node gets the CPU registers, not the SVD peripherals`() {
        val body = scopes()
        assertTrue(ScopeTracker().onScopes(body))
        val hints = body.getAsJsonArray("scopes").associate { it.asJsonObject["name"].asString to it.asJsonObject["presentationHint"].asString }
        assertEquals("peripherals", hints["Peripherals"])
        assertEquals("registers", hints["Registers"])
        assertFalse("already rewritten (e.g. from the cache): nothing to change", ScopeTracker().onScopes(body))
    }

    @Test
    fun `the frame RustRover shows is the one of its last scopes request, until the target runs`() {
        val tracker = ScopeTracker()
        assertNull(tracker.frameId)
        tracker.onScopesRequest(json("""{"seq":7,"type":"request","command":"scopes","arguments":{"frameId":6729}}"""))
        assertEquals(6729, tracker.frameId)
        tracker.onRunStateChanged()
        assertNull(tracker.frameId)
    }
}
