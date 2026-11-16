package dev.markusmayer.proberover.detect

import dev.markusmayer.proberover.model.RttDataFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoConfigDetectorTest {

    @Test
    fun `embassy-style string runner`() {
        val d = CargoConfigDetector.detect(
            """
            [target.'cfg(all(target_arch = "arm", target_os = "none"))']
            runner = "probe-rs run --chip RP2040"   # flash and run

            [build]
            target = "thumbv6m-none-eabi"

            [env]
            DEFMT_LOG = "debug"
            """.trimIndent(),
        )
        assertEquals("thumbv6m-none-eabi", d.buildTarget)
        assertTrue(d.isEmbeddedTarget)
        assertEquals("RP2040", d.probeRs?.chip)
        assertEquals(RttDataFormat.STRING, d.probeRs?.rttFormat)
    }

    @Test
    fun `array runner with flags in both spellings`() {
        val d = CargoConfigDetector.detect(
            """
            [target.thumbv7em-none-eabihf]
            runner = ["probe-rs", "run", "--chip=STM32F411CEUx", "--probe", "0483:374b:0670FF", "--speed", "4000", "--protocol", "swd", "--connect-under-reset"]
            """.trimIndent(),
        )
        val t = d.probeRs!!
        assertEquals("STM32F411CEUx", t.chip)
        assertEquals("0483:374b:0670FF", t.probe)
        assertEquals(4000, t.speedKhz)
        assertEquals("Swd", t.wireProtocol)
        assertTrue(t.connectUnderReset)
    }

    @Test
    fun `runner for the build target wins over an earlier generic one`() {
        val d = CargoConfigDetector.detect(
            """
            [build]
            target = "riscv32imc-unknown-none-elf"

            [target.thumbv7em-none-eabihf]
            runner = "probe-rs run --chip nRF52840_xxAA"

            [target.riscv32imc-unknown-none-elf]
            runner = "probe-rs run --chip esp32c3"
            """.trimIndent(),
        )
        assertEquals("esp32c3", d.probeRs?.chip)
    }

    @Test
    fun `defmt dependency switches RTT to defmt decoding`() {
        val d = CargoConfigDetector.detect(
            "[target.thumbv7em-none-eabihf]\nrunner = \"probe-rs run --chip nRF52840_xxAA\"",
            cargoToml = "[dependencies]\ndefmt = \"1.0\"\ndefmt-rtt = \"1.0\"\n",
        )
        assertEquals(RttDataFormat.DEFMT, d.probeRs?.rttFormat)
    }

    @Test
    fun `espflash runner is embedded but not probe-rs`() {
        val d = CargoConfigDetector.detect(
            """
            [target.riscv32imc-unknown-none-elf]
            runner = "espflash flash --monitor"
            [build]
            target = "riscv32imc-unknown-none-elf"
            """.trimIndent(),
        )
        assertTrue(d.isEmbeddedTarget)
        assertNull(d.probeRs)
    }

    @Test
    fun `host project is left alone`() {
        val d = CargoConfigDetector.detect(null)
        assertNull(d.buildTarget)
        assertFalse(d.isEmbeddedTarget)
        assertNull(d.probeRs)
    }

    @Test
    fun `probe-rs runner without a chip is not usable`() {
        assertNull(CargoConfigDetector.parseProbeRsRun(listOf("probe-rs", "run")))
    }

    @Test
    fun `path to probe-rs and quoted arguments`() {
        assertEquals(
            listOf("/home/me/.cargo/bin/probe-rs", "run", "--chip", "STM32 H7 thing"),
            splitCommandLine("""/home/me/.cargo/bin/probe-rs run --chip "STM32 H7 thing""""),
        )
        val t = CargoConfigDetector.parseProbeRsRun(splitCommandLine("C:\\tools\\probe-rs.exe run --chip esp32s3"))
        assertEquals("esp32s3", t?.chip)
    }

    @Test
    fun `multi-line arrays, inline tables and quoted keys`() {
        val d = CargoConfigDetector.detect(
            """
            build = { target = "thumbv8m.main-none-eabihf" }

            [target."thumbv8m.main-none-eabihf"]
            runner = [
                "probe-rs", "run",
                "--chip", "RP235x",   # Raspberry Pi Pico 2
                "--speed", "8000",
            ]
            rustflags = ["-C", "link-arg=-Tlink.x"]
            """.trimIndent(),
        )
        assertEquals("thumbv8m.main-none-eabihf", d.buildTarget)
        assertEquals("RP235x", d.probeRs?.chip)
        assertEquals(8000, d.probeRs?.speedKhz)
    }

    @Test
    fun `defmt from workspace, target-specific and dotted dependencies`() {
        val config = "[target.thumbv7em-none-eabihf]\nrunner = \"probe-rs run --chip nRF52840_xxAA\""
        listOf(
            "[workspace.dependencies]\ndefmt = \"1\"",
            "[target.'cfg(target_os = \"none\")'.dependencies]\ndefmt-rtt = \"1\"",
            "[dependencies]\ndefmt.workspace = true",
        ).forEach { manifest ->
            assertEquals(manifest, RttDataFormat.DEFMT, CargoConfigDetector.detect(config, manifest).probeRs?.rttFormat)
        }
        assertEquals(RttDataFormat.STRING, CargoConfigDetector.detect(config, "[dependencies]\nembassy-executor = \"0.7\"\n# defmt = \"1\"").probeRs?.rttFormat)
    }

    @Test
    fun `invalid TOML falls back to the line reader`() {
        val d = CargoConfigDetector.detect("[target.thumbv6m-none-eabi]\nrunner = \"probe-rs run --chip RP2040\"\nbroken = [\n")
        assertEquals("RP2040", d.probeRs?.chip)
    }

    @Test
    fun `embedded-test harness is detected`() {
        val config = "[target.thumbv7em-none-eabihf]\nrunner = \"probe-rs run --chip nRF52840_xxAA\""
        assertTrue(CargoConfigDetector.detect(config, "[dev-dependencies]\nembedded-test = { version = \"0.6\", features = [\"defmt\"] }").usesEmbeddedTest)
        assertFalse(CargoConfigDetector.detect(config, "[dependencies]\ndefmt = \"1\"").usesEmbeddedTest)
    }
}
