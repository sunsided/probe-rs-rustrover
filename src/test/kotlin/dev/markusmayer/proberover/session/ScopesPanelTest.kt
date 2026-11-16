package dev.markusmayer.proberover.session

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopesPanelTest {
    @Test
    fun `CPU registers, SVD peripherals and statics, without RustRover's own locals`() {
        val body = JsonParser.parseString("""{"scopes":[
            {"name":"Peripherals","presentationHint":"peripherals","variablesReference":1},
            {"name":"Static","presentationHint":"statics","variablesReference":6727},
            {"name":"Registers","presentationHint":"registers","variablesReference":6729},
            {"name":"Variables","presentationHint":"locals","variablesReference":6728}]}""").asJsonObject
        assertEquals(
            listOf(
                ScopesPanel.Entry("CPU Registers", "", null, 6729, isScope = true),
                ScopesPanel.Entry("SVD Peripherals", "", null, 1, isScope = true),
                ScopesPanel.Entry("Statics", "", null, 6727, isScope = true),
            ),
            ScopesPanel.topLevel(body),
        )
    }

    @Test
    fun `variables become rows`() {
        val body = JsonParser.parseString("""{"variables":[
            {"name":"R0/a1/r1","value":"0x20007fe4","type":"u32","variablesReference":0},
            {"name":"PORT","value":"","variablesReference":12}]}""").asJsonObject
        assertEquals(
            listOf(ScopesPanel.Entry("R0/a1/r1", "0x20007fe4", "u32", 0), ScopesPanel.Entry("PORT", "", null, 12)),
            ScopesPanel.children(body),
        )
    }
}
