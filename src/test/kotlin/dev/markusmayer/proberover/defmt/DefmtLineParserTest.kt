package dev.markusmayer.proberover.defmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefmtLineParserTest {

    @Test
    fun `probe-rs 0_32 run output, path relative to the crate's parent`() {
        val line = "[INFO ] on (blinky_basic arduino-mkr-wifi-1010/examples/blinky_basic.rs:36)"
        val p = DefmtLineParser.parse(line)!!
        assertEquals(DefmtLevel.INFO, p.level)
        assertEquals("arduino-mkr-wifi-1010/examples/blinky_basic.rs", p.path)
        assertEquals(36, p.lineNumber)
        assertEquals(
            listOf("arduino-mkr-wifi-1010/examples/blinky_basic.rs", "examples/blinky_basic.rs", "blinky_basic.rs"),
            DefmtConsoleFilter.relativeCandidates(p.path!!),
        )
    }

    @Test
    fun `full format, level line`() {
        val line = "0.000123 INFO  Hello from the board"
        val p = DefmtLineParser.parse(line)!!
        assertEquals(DefmtLevel.INFO, p.level)
        assertEquals("INFO", line.substring(p.levelRange!!))
        assertNull(p.path)
    }

    @Test
    fun `full format, location line`() {
        val line = "└─ blinky::____embassy_main_task::{async_fn#0} @ src/main.rs:42"
        val p = DefmtLineParser.parse(line)!!
        assertNull(p.level)
        assertEquals("src/main.rs", p.path)
        assertEquals(42, p.lineNumber)
        assertEquals("src/main.rs:42", line.substring(p.locationRange!!))
    }

    @Test
    fun `one-line format with brackets and location`() {
        val line = "0.001953 [WARN ] Button bounced (blinky src/bin/button.rs:17)\n"
        val p = DefmtLineParser.parse(line)!!
        assertEquals(DefmtLevel.WARN, p.level)
        assertEquals("src/bin/button.rs", p.path)
        assertEquals(17, p.lineNumber)
    }

    @Test
    fun `no timestamp, uptime-style timestamp, channel prefix`() {
        assertEquals(DefmtLevel.ERROR, DefmtLineParser.parse("ERROR panicked at 'oops'")?.level)
        assertEquals(DefmtLevel.DEBUG, DefmtLineParser.parse("00:00:01.250 DEBUG tick")?.level)
        assertEquals(DefmtLevel.TRACE, DefmtLineParser.parse("[defmt] 0.5 TRACE poll")?.level)
    }

    @Test
    fun `absolute and windows paths, column suffix`() {
        assertEquals(
            "/home/me/.cargo/registry/src/index/embassy-executor-0.7.0/src/raw/mod.rs",
            DefmtLineParser.parse("└─ embassy_executor @ /home/me/.cargo/registry/src/index/embassy-executor-0.7.0/src/raw/mod.rs:123")?.path,
        )
        val win = DefmtLineParser.parse("└─ app @ C:\\work\\app\\src\\main.rs:9:5")!!
        assertEquals("C:\\work\\app\\src\\main.rs", win.path)
        assertEquals(9, win.lineNumber)
    }

    @Test
    fun `ordinary text is left alone`() {
        assertNull(DefmtLineParser.parse("The INFO level is the default"))
        assertNull(DefmtLineParser.parse("Flashing complete"))
        assertNull(DefmtLineParser.parse("main.rs: no line number"))
    }
}
