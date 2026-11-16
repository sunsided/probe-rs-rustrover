package dev.proberover.advice

/** A suggested fix for a probe-rs error. */
data class Advice(val title: String, val hint: String, val link: String? = null)

/**
 * Maps probe-rs error messages to an explanation and a fix. Patterns are based on the `#[error(...)]` texts in
 * probe-rs (`probe-rs/src`, `probe-rs-tools/src`) at the time of writing; anything unmatched gets no advice.
 */
object ProbeRsErrorAdvisor {
    private val os = System.getProperty("os.name").orEmpty()

    fun advise(message: String, osName: String = os): Advice? {
        val m = message.lowercase()
        return when {
            "no connected probes were found" in m || "probe not found" in m -> Advice(
                "No debug probe found",
                "Check the USB cable and that the probe has power. Run <code>probe-rs list</code> in a terminal to see what probe-rs detects.",
            )
            "probes were found" in m -> Advice(
                "Several debug probes are connected",
                "Pick one with <code>--probe VID:PID[:SERIAL]</code> in the runner in <code>.cargo/config.toml</code>. <code>probe-rs list</code> shows the selectors.",
            )
            "already in use" in m || "resource busy" in m -> Advice(
                "The debug probe is busy",
                "Another program has it open: a running <code>probe-rs run</code> or <code>cargo embed</code>, VS Code, or another debug session. Close it and try again.",
            )
            "lacked the permission" in m || "permission denied" in m || "access denied" in m ||
                "unable to open probe" in m || "failed to open the debug probe" in m || "failed to open probe" in m ->
                usbAccessAdvice(osName)
            "was not found in the database" in m || "unsupported chip" in m -> Advice(
                "Unknown chip name",
                "Check the <code>--chip</code> name in <code>.cargo/config.toml</code>. <code>probe-rs chip list</code> lists the names probe-rs knows.",
            )
            "connecting to the chip was unsuccessful" in m -> Advice(
                "Couldn't connect to the chip",
                "If the firmware puts the chip to sleep or reconfigures the debug pins, add <code>--connect-under-reset</code> to the runner. " +
                    "Lowering the speed (<code>--speed 1000</code>) can help with long wires.",
            )
            "the probe has no reset line" in m -> Advice(
                "The probe can't reset the chip",
                "Connect the probe's reset pin, or remove <code>--connect-under-reset</code>.",
            )
            "erase" in m && ("failed" in m || "not supported" in m) -> Advice(
                "Flash erase failed",
                "The flash may be write-protected or read-out protected. Unlocking usually needs a full chip erase " +
                    "(<code>--allow-erase-all</code>), which deletes everything on the chip.",
            )
            "usb transport error" in m || "out of sync" in m -> Advice(
                "The connection to the probe dropped",
                "Unplug and reconnect the probe, then debug again. Avoid USB hubs if it keeps happening.",
            )
            else -> null
        }
    }

    private fun usbAccessAdvice(osName: String) = when {
        osName.startsWith("Linux") -> Advice(
            "No permission to use the debug probe",
            "Install the probe-rs udev rules, reload them with <code>sudo udevadm control --reload &amp;&amp; sudo udevadm trigger</code>, and reconnect the probe.",
            "https://probe.rs/docs/getting-started/probe-setup/",
        )
        osName.startsWith("Windows") -> Advice(
            "The debug probe can't be opened",
            "Some probes need the WinUSB driver on Windows. Install it with Zadig, then reconnect the probe.",
            "https://probe.rs/docs/getting-started/probe-setup/",
        )
        else -> Advice(
            "The debug probe can't be opened",
            "Reconnect the probe and make sure no other program is using it.",
            "https://probe.rs/docs/getting-started/probe-setup/",
        )
    }
}
