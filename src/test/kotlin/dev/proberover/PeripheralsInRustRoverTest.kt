package dev.proberover

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.proberover.peripherals.MemoryAccess
import dev.proberover.peripherals.PeripheralsPanel
import dev.proberover.svd.SvdParser
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/** The Peripherals view against fake target memory, headless. */
class PeripheralsInRustRoverTest : BasePlatformTestCase() {

    private class FakeMemory : MemoryAccess {
        val bytes = HashMap<Long, Byte>().apply {
            byteArrayOf(0, 0, 0, 0xA8.toByte()).forEachIndexed { i, b -> put(0x40020000L + i, b) }  // GPIOA.MODER
            put(0x40020010L, 1)                                                                     // GPIOA.IDR
        }
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun read(address: Long, count: Int): CompletableFuture<ByteArray> {
            reads += address to count
            return CompletableFuture.completedFuture(ByteArray(count) { bytes[address + it] ?: 0 })
        }
        override fun write(address: Long, data: ByteArray): CompletableFuture<Unit> {
            data.forEachIndexed { i, b -> bytes[address + i] = b }
            return CompletableFuture.completedFuture(Unit)
        }
    }

    private fun panel(memory: FakeMemory): PeripheralsPanel {
        val panel = PeripheralsPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.memoryOverride = memory
        panel.setDevice(SvdParser.parse(javaClass.getResourceAsStream("/test-device.svd")!!), null)
        return panel
    }

    fun `test expanding a peripheral reads its whole register block once`() {
        val memory = FakeMemory()
        val panel = panel(memory)
        assertNull(panel.registerValue("GPIOA", "MODER"))
        panel.expand("GPIOA")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(0x40020000L to 0x52), memory.reads)
        assertEquals(0xA8000000L, panel.registerValue("GPIOA", "MODER"))
        assertEquals(1L, panel.registerValue("GPIOA", "IDR"))

        memory.bytes[0x40020010L] = 0
        panel.refreshExpanded()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0L, panel.registerValue("GPIOA", "IDR"))
        assertNull("collapsed peripherals aren't read", panel.registerValue("GPIOB", "IDR"))
    }

    fun `test screenshot`() {
        val panel = panel(FakeMemory())
        panel.expand("GPIOA")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Expand MODER to show its fields.
        val tree = panel.tree
        for (row in 0 until tree.rowCount) if (tree.getPathForRow(row).lastPathComponent.toString().isNotEmpty() && row == 1) tree.expandRow(row)
        val component = panel.component
        component.setSize(760, 330)
        fun layout(c: java.awt.Component) { c.doLayout(); if (c is java.awt.Container) c.components.forEach(::layout) }
        layout(component)
        val image = BufferedImage(760, 330, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); component.printAll(g); g.dispose()
        val out = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        ImageIO.write(image, "png", File(out, "peripherals.png"))
    }
}
