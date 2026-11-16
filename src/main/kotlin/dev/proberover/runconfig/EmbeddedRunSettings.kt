package dev.proberover.runconfig

import dev.proberover.model.ProbeRsTarget
import dev.proberover.model.RttDataFormat

/** How RTT channel 0 is decoded. AUTO follows detection (defmt if the crate depends on defmt). */
enum class RttMode { AUTO, DEFMT, STRING, DISABLED }

/**
 * probe-rs overrides for one Cargo run configuration, stored in the project's probe-rs settings (Cargo run
 * configurations don't persist plugin data themselves). Empty / null fields mean "use the project setting or what was
 * detected in `.cargo/config.toml`". A bean for IntelliJ's XmlSerializer: no-arg constructor, mutable properties.
 */
data class EmbeddedRunSettings(
    var chip: String? = null,
    var probe: String? = null,
    var svdFile: String? = null,
    var flash: Boolean = true,
    var haltAfterReset: Boolean = false,
    var connectUnderReset: Boolean = false,
    var rtt: RttMode = RttMode.AUTO,
    var coreIndex: Int = 0,
) {
    val isDefault: Boolean get() = this == EmbeddedRunSettings()

    /** Applies these settings on top of a project-level target. */
    fun applyTo(base: ProbeRsTarget): ProbeRsTarget = base.copy(
        chip = chip?.takeIf { it.isNotBlank() } ?: base.chip,
        probe = probe?.takeIf { it.isNotBlank() } ?: base.probe,
        svdFile = svdFile?.takeIf { it.isNotBlank() } ?: base.svdFile,
        flash = flash,
        haltAfterReset = haltAfterReset,
        connectUnderReset = connectUnderReset || base.connectUnderReset,
        coreIndex = coreIndex,
        rttEnabled = rtt != RttMode.DISABLED && base.rttEnabled,
        rttFormat = when (rtt) {
            RttMode.DEFMT -> RttDataFormat.DEFMT
            RttMode.STRING -> RttDataFormat.STRING
            RttMode.AUTO, RttMode.DISABLED -> base.rttFormat
        },
    )
}
