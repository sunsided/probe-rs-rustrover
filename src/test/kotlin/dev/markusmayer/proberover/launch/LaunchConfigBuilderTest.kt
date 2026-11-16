package dev.markusmayer.proberover.launch

import com.google.gson.GsonBuilder
import dev.markusmayer.proberover.model.ProbeRsTarget
import dev.markusmayer.proberover.model.RttDataFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchConfigBuilderTest {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `launch JSON matches probe-rs SessionConfig`() {
        val target = ProbeRsTarget(
            chip = "nRF52840_xxAA",
            probe = "1366:1051",
            wireProtocol = "Swd",
            svdFile = "nrf52840.svd",
            rttFormat = RttDataFormat.DEFMT,
        )
        val json = gson.toJson(LaunchConfigBuilder.launch(target, "/fw/target/thumbv7em-none-eabihf/debug/blinky", "/fw"))
        assertEquals(
            """
            {
              "cwd": "/fw",
              "chip": "nRF52840_xxAA",
              "probe": "1366:1051",
              "wireProtocol": "Swd",
              "connectUnderReset": false,
              "allowEraseAll": false,
              "consoleLogLevel": "Console",
              "flashingConfig": {
                "flashingEnabled": true,
                "haltAfterReset": false,
                "verifyBeforeFlashing": true
              },
              "coreConfigs": [
                {
                  "coreIndex": 0,
                  "programBinary": "/fw/target/thumbv7em-none-eabihf/debug/blinky",
                  "svdFile": "nrf52840.svd",
                  "catchReset": false,
                  "catchHardfault": true,
                  "rttEnabled": true,
                  "rttChannelFormats": [
                    {
                      "channelNumber": 0,
                      "dataFormat": "Defmt",
                      "showTimestamps": true,
                      "showLocation": true
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            json,
        )
    }

    @Test
    fun `attach never flashes`() {
        @Suppress("UNCHECKED_CAST")
        val flashing = LaunchConfigBuilder.attach(ProbeRsTarget(chip = "RP2040"), null, null)["flashingConfig"] as Map<String, Any>
        assertEquals(false, flashing["flashingEnabled"])
    }
}
