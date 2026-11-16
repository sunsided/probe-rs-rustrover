# Changelog

## 0.1.0

- Debug embedded Rust firmware with probe-rs from the normal Debug button, detected from the `probe-rs run --chip …` runner
- RTT tabs per channel with defmt colors, clickable source locations, a level filter and input to the target
- A probe-rs tab in the debug session: CPU registers, SVD peripherals with decoded field values and pin names, statics
- SVD files found and downloaded on request (atsamd-rs, rp-rs, cmsis-svd-data)
- Peripherals tool window from the chip's SVD file, with live values and editable registers and fields
- Flashing progress, probe-rs errors with suggested fixes, probe picker, chip completion
- Live Watch: globals by name or address, sampled while the target runs, with a chart
- Attach to the running firmware, remote probe-rs server, per-run-configuration settings
- Install or update probe-rs, udev rules helper, core dumps, diagnostics for bug reports
- Wokwi simulator: a Remote Debug configuration from `wokwi.toml`
