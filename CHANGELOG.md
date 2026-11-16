# Changelog

## 0.1.0

- Debug embedded Rust firmware with probe-rs from the normal Debug button, detected from the `probe-rs run --chip …` runner
- RTT tabs per channel with defmt colors, clickable source locations, a level filter and input to the target
- Peripherals tool window from the chip's SVD file, with live values and editable registers and fields
- Flashing progress, probe-rs errors with suggested fixes, probe picker, chip completion
- Attach to the running firmware, remote probe-rs server, per-run-configuration settings
- Install or update probe-rs, udev rules helper, diagnostics for bug reports
