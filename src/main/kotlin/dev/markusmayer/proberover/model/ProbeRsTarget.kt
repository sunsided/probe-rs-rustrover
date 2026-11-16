package dev.markusmayer.proberover.model

/** RTT data formats understood by probe-rs (`probe_rs_rpc::rtt_config::DataFormat`). */
enum class RttDataFormat(val wireName: String) { STRING("String"), BINARY_LE("BinaryLE"), DEFMT("Defmt") }

/** Everything the plugin needs to start a probe-rs session for one firmware binary. */
data class ProbeRsTarget(
    val chip: String,
    /** probe-rs probe selector, `VID:PID[:SERIAL]`. */
    val probe: String? = null,
    val speedKhz: Int? = null,
    /** `Swd` or `Jtag`, as probe-rs spells it. */
    val wireProtocol: String? = null,
    val connectUnderReset: Boolean = false,
    val allowEraseAll: Boolean = false,
    val flash: Boolean = true,
    /** Compare flash contents first and only reprogram what changed. */
    val skipUnchangedFlash: Boolean = true,
    /** The core to debug. probe-rs debugs one core per session. */
    val coreIndex: Int = 0,
    val haltAfterReset: Boolean = false,
    val catchReset: Boolean = false,
    val catchHardfault: Boolean = true,
    val svdFile: String? = null,
    val rttEnabled: Boolean = true,
    val rttFormat: RttDataFormat = RttDataFormat.STRING,
)
