package dev.markusmayer.proberover

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.markusmayer.proberover.elf.ElfSymbol
import dev.markusmayer.proberover.peripherals.MemoryAccess
import dev.markusmayer.proberover.watch.LiveWatchPanel
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class LiveWatchInRustRoverTest : BasePlatformTestCase() {

    /** A target whose COUNTER goes up and whose TEMPERATURE wobbles, one step per read. */
    private class FakeTarget : MemoryAccess {
        var tick = 0
        override fun read(address: Long, count: Int): CompletableFuture<ByteArray> {
            val value = when (address) {
                0x20000010L -> tick
                0x20000020L -> java.lang.Float.floatToIntBits(21.5f + kotlin.math.sin(tick / 6.0).toFloat() * 2)
                else -> 0
            }
            val bytes = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            return CompletableFuture.completedFuture(bytes.copyOf(count))
        }
        override fun write(address: Long, data: ByteArray) = CompletableFuture.completedFuture(Unit)
    }

    private fun panel(): Pair<LiveWatchPanel, FakeTarget> {
        val target = FakeTarget()
        val panel = LiveWatchPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.memoryOverride = target
        panel.setSymbols(listOf(ElfSymbol("_ZN6blinky7COUNTER17h0123456789abcdefE", 0x20000010, 4), ElfSymbol("TEMPERATURE", 0x20000020, 4)))
        return panel to target
    }

    fun `test watches resolve, sample and show values`() {
        val (panel, target) = panel()
        assertNull(panel.addWatch("COUNTER"))
        assertNull(panel.addWatch("TEMPERATURE", dev.markusmayer.proberover.watch.WatchType.F32))
        assertNotNull(panel.addWatch("NOT_THERE"))
        repeat(3) {
            target.tick = it + 1
            panel.sample().get(5, TimeUnit.SECONDS)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertEquals("3", panel.model.getValueAt(0, 3))
        assertEquals("1 … 3", panel.model.getValueAt(0, 4))
        assertEquals(3, panel.watches[1].samples.size)
    }

    fun `test screenshot`() {
        val (panel, target) = panel()
        panel.addWatch("COUNTER"); panel.addWatch("TEMPERATURE", dev.markusmayer.proberover.watch.WatchType.F32)
        repeat(80) {
            target.tick = it
            panel.sample().get(5, TimeUnit.SECONDS)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        val c = panel.component
        c.setSize(760, 360)
        fun layout(x: java.awt.Component) { x.doLayout(); if (x is java.awt.Container) x.components.forEach(::layout) }
        layout(c)
        val image = BufferedImage(760, 360, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); c.printAll(g); g.dispose()
        ImageIO.write(image, "png", File(File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }, "live-watch.png"))
    }
}
