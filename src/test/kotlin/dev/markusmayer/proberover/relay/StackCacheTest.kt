package dev.markusmayer.proberover.relay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StackCacheTest {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun stackRequest(seq: Int, start: Int, levels: Int) =
        json("""{"seq":$seq,"type":"request","command":"stackTrace","arguments":{"threadId":0,"startFrame":$start,"levels":$levels}}""")

    private fun stackResponse(requestSeq: Int, names: List<String>, total: Int = names.size) = json(
        """{"seq":1,"type":"response","request_seq":$requestSeq,"success":true,"command":"stackTrace","body":{"stackFrames":[""" +
            names.mapIndexed { i, n -> """{"id":${100 + i},"name":"$n"}""" }.joinToString(",") + """],"totalFrames":$total}}""",
    )

    private fun scopes(seq: Int, frameId: Int) = json("""{"seq":$seq,"type":"request","command":"scopes","arguments":{"frameId":$frameId}}""")
    private fun names(body: JsonObject) = body.getAsJsonArray("stackFrames").map { it.asJsonObject["name"].asString }

    private val stack = listOf("__cortex_m_rt_main", "__cortex_m_rt_main_trampoline", "Reset")

    @Test
    fun `one stop as RustRover asks for it - second stackTrace and scopes come from the cache`() {
        val cache = StackCache()
        cache.onEvent("stopped")

        val first = stackRequest(5, 0, 1)
        assertNull(cache.onRequest(first))
        assertEquals("fetches more than the IDE asked for", StackCache.FETCH_LEVELS, first.getAsJsonObject("arguments")["levels"].asInt)
        val trimmed = cache.onResponse(stackResponse(5, stack))!!
        assertEquals(listOf("__cortex_m_rt_main"), names(trimmed.getAsJsonObject("body")))
        assertEquals(3, trimmed.getAsJsonObject("body")["totalFrames"].asInt)

        assertNull(cache.onRequest(scopes(6, 100)))
        assertNull(cache.onResponse(json("""{"seq":2,"type":"response","request_seq":6,"success":true,"command":"scopes","body":{"scopes":[{"name":"Locals"}]}}""")))

        assertEquals(stack, names(cache.onRequest(stackRequest(7, 0, 100))!!))
        assertEquals("Locals", cache.onRequest(scopes(8, 100))!!.getAsJsonArray("scopes")[0].asJsonObject["name"].asString)
        assertEquals("pages past the end are empty", emptyList<String>(), names(cache.onRequest(stackRequest(9, 100, 100))!!))
    }

    @Test
    fun `running the core or changing state drops the cache`() {
        val cache = StackCache()
        assertNull(cache.onRequest(stackRequest(1, 0, 1)))
        cache.onResponse(stackResponse(1, stack))
        assertNotNull(cache.onRequest(stackRequest(2, 0, 100)))

        assertNull(cache.onRequest(json("""{"seq":3,"type":"request","command":"next","arguments":{"threadId":0}}""")))
        assertNull(cache.onRequest(stackRequest(4, 0, 100)))

        cache.onResponse(stackResponse(4, stack))
        cache.onEvent("continued")
        assertNull(cache.onRequest(stackRequest(5, 0, 100)))

        cache.onResponse(stackResponse(5, stack))
        cache.invalidate() // e.g. a register written from the Peripherals view
        assertNull(cache.onRequest(stackRequest(6, 0, 100)))
    }

    @Test
    fun `a response to a request sent before the target ran again is not cached`() {
        val cache = StackCache()
        assertNull(cache.onRequest(stackRequest(1, 0, 100)))
        cache.onEvent("continued")
        cache.onResponse(stackResponse(1, stack))
        assertNull(cache.onRequest(stackRequest(2, 0, 100)))
    }

    @Test
    fun `when probe-rs gives up unwinding, only the frames it returned are served from the cache`() {
        val cache = StackCache()
        val many = (0 until 100).map { "frame$it" }
        assertNull(cache.onRequest(stackRequest(1, 0, 1)))
        cache.onResponse(stackResponse(1, many, total = 500))

        assertEquals(many.take(50), names(cache.onRequest(stackRequest(2, 0, 50))!!))
        assertNull("frames beyond the cached ones go to probe-rs", cache.onRequest(stackRequest(3, 100, 100)))
    }

    @Test
    fun `failed responses are forwarded unchanged and not cached`() {
        val cache = StackCache()
        assertNull(cache.onRequest(stackRequest(1, 0, 1)))
        assertNull(cache.onResponse(json("""{"seq":1,"type":"response","request_seq":1,"success":false,"command":"stackTrace","message":"busy"}""")))
        assertNull(cache.onRequest(stackRequest(2, 0, 100)))
    }
}
