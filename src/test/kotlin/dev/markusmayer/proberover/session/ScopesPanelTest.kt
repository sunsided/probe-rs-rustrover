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

class LiveWatchTargetTest {
    private fun row(name: String, value: String, type: String?, scope: Boolean = false) = ScopeRows.row(name, value, type, scope)
    private fun svd(name: String, value: String, type: String?) = ScopeRows.row(name, value, type, fromSvd = true)

    @Test
    fun `a static by its Rust path, as the firmware's symbols name it`() {
        val path = listOf(row("Statics", "", null, true), row("blinky_basic", "", "namespace"), row("COUNTER", "AtomicU32 @ 0x20000038", "AtomicU32"))
        assertEquals(ScopesPanel.LiveWatchTarget("blinky_basic::COUNTER", null, null), ScopesPanel.liveWatchTarget(path) { null })
    }

    @Test
    fun `an SVD register by address, sized from the SVD, same-name group and peripheral named once`() {
        val path = listOf(row("SVD Peripherals", "", null, true), row("PORT", "Port Module", "Peripheral Group"),
            row("PORT", "Port Module", "Peripheral"), svd("OUT0", "0x00100000 @ 0x41004410", "Data Output Value"))
        assertEquals(
            ScopesPanel.LiveWatchTarget("0x41004410", dev.markusmayer.proberover.watch.WatchType.U32, "PORT.OUT0"),
            ScopesPanel.liveWatchTarget(path) { if (it == 0x41004410L) 4 else null },
        )
    }

    @Test
    fun `not CPU registers, fields or parts of a static`() {
        val cpu = listOf(row("CPU Registers", "", null, true), row("R0/a1/r1", "0x20007fd4", "Platform Register"))
        val field = listOf(row("SVD Peripherals", "", null, true), row("PORT", "Port Module", "Peripheral Group"),
            svd("PMUX0_6", "0x06 @ 0x41004436", "Peripheral Multiplexing"), svd("PMUXE", "0110 @ 0x41004436:0..4", "Peripheral Multiplexing Even"))
        val member = listOf(row("Statics", "", null, true), row("blinky_basic", "", "namespace"), row("STATE", "State", "State"), row("count", "3", "u32"))
        listOf(cpu, field, member).forEach { assertEquals(null, ScopesPanel.liveWatchTarget(it) { 4 }) }
    }
}
