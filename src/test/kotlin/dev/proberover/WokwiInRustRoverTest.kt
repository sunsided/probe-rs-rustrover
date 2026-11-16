package dev.proberover

import com.intellij.execution.RunManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.proberover.wokwi.WokwiConfig
import dev.proberover.wokwi.WokwiConfigReader
import dev.proberover.wokwi.WokwiDebugConfiguration
import org.jdom.Element

class WokwiInRustRoverTest : BasePlatformTestCase() {

    fun `test wokwi toml`() {
        val config = WokwiConfigReader.parse(
            """
            [wokwi]
            version = 1
            elf = "target/riscv32imc-unknown-none-elf/debug/blinky"
            firmware = "target/riscv32imc-unknown-none-elf/debug/blinky"
            gdbServerPort = 3334
            """.trimIndent(),
        )
        assertEquals(WokwiConfig("target/riscv32imc-unknown-none-elf/debug/blinky", "target/riscv32imc-unknown-none-elf/debug/blinky", 3334), config)
        assertNull(WokwiConfigReader.parse("[package]\nname = \"x\""))
    }

    fun `test creates a Remote Debug configuration attached to the Wokwi GDB server`() {
        assertNotNull("RustRover's Remote Debug type", WokwiDebugConfiguration.remoteDebugType())
        val settings = WokwiDebugConfiguration.createOrUpdate(project, WokwiConfig("/fw/blinky", null, 3334))!!
        try {
            val xml = Element("configuration").also(settings.configuration::writeExternal)
            assertEquals("GDB", xml.getAttributeValue("debuggerKind"))
            assertEquals("localhost:3334", xml.getAttributeValue("remoteCommand"))
            assertEquals("/fw/blinky", xml.getAttributeValue("symbolFile"))
            assertSame(settings, RunManager.getInstance(project).selectedConfiguration)
            // Running it again updates the same configuration.
            assertSame(settings, WokwiDebugConfiguration.createOrUpdate(project, WokwiConfig("/fw/blinky", null, null)))
            assertEquals("localhost:3333", Element("c").also(settings.configuration::writeExternal).getAttributeValue("remoteCommand"))
        } finally {
            RunManager.getInstance(project).removeConfiguration(settings)
        }
    }
}
