package dev.markusmayer.proberover.launch

import dev.markusmayer.proberover.model.ProbeRsTarget
import dev.markusmayer.proberover.model.RttDataFormat

/**
 * Builds the `launch`/`attach` arguments for `probe-rs dap-server`.
 *
 * Field names follow `probe-rs-tools/src/bin/probe-rs/cmd/dap_server/server/configuration.rs` (`SessionConfig`,
 * `FlashingConfig`, `CoreConfig`, all `camelCase`) and `RttConfig` (`rttEnabled`, `rttChannelFormats`, flattened into
 * the core config). Null values are left out so probe-rs applies its own defaults.
 */
object LaunchConfigBuilder {

    fun launch(target: ProbeRsTarget, programBinary: String, cwd: String?): Map<String, Any> =
        session(target, cwd, programBinary, flashing = target.flash)

    /** Attach to a running target: same session, but never flash. */
    fun attach(target: ProbeRsTarget, programBinary: String?, cwd: String?): Map<String, Any> =
        session(target, cwd, programBinary, flashing = false)

    private fun session(target: ProbeRsTarget, cwd: String?, programBinary: String?, flashing: Boolean): Map<String, Any> {
        val core = buildMap<String, Any> {
            put("coreIndex", target.coreIndex)
            programBinary?.let { put("programBinary", it) }
            target.svdFile?.let { put("svdFile", it) }
            put("catchReset", target.catchReset)
            put("catchHardfault", target.catchHardfault)
            put("rttEnabled", target.rttEnabled)
            if (target.rttEnabled) {
                put(
                    "rttChannelFormats",
                    listOf(
                        buildMap<String, Any> {
                            put("channelNumber", 0)
                            put("dataFormat", target.rttFormat.wireName)
                            put("showTimestamps", true)
                            if (target.rttFormat == RttDataFormat.DEFMT) put("showLocation", true)
                        },
                    ),
                )
            }
        }

        return buildMap {
            cwd?.let { put("cwd", it) }
            put("chip", target.chip)
            target.probe?.let { put("probe", it) }
            target.speedKhz?.let { put("speed", it) }
            target.wireProtocol?.let { put("wireProtocol", it) }
            put("connectUnderReset", target.connectUnderReset)
            put("allowEraseAll", target.allowEraseAll)
            put("consoleLogLevel", "Console")
            put(
                "flashingConfig",
                mapOf(
                    "flashingEnabled" to flashing,
                    "haltAfterReset" to target.haltAfterReset,
                    "verifyBeforeFlashing" to target.skipUnchangedFlash,
                ),
            )
            put("coreConfigs", listOf(core))
        }
    }
}
