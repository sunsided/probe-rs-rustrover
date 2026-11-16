# What's verified, and how

Status of every feature as of November 16, 2026: what ran on real hardware, what only ran against a fake probe-rs or a
headless RustRover, and what hasn't run at all.

## On hardware

Arduino MKR WiFi 1010 (ATSAMD21G18A, Cortex-M0+), a CMSIS-DAP v1 probe (HID only), probe-rs 0.32.0 and a licensed
RustRover 2026.3 (263.7307.214) on Linux. Firmware: `examples/blinky_basic.rs` of
[sunsided/arduino-mkr-wifi-1010](https://github.com/sunsided/arduino-mkr-wifi-1010), defmt over RTT.

- The plugin loads in the production IDE. The project is detected with the real TOML parser, so the production IDE gives
  the plugin access to the `visibility="internal"` modules it builds on.
- **Debug** on a Cargo example launches through the relay; the firmware is flashed with a progress bar.
- defmt logs show up decoded in the RTT tab; locations are links in the RTT tab and in the **Run** window.
- Breakpoints, stepping, local variables, hovering locals, RustRover's Registers node (CPU registers).
- The **probe-rs** tab: CPU registers, SVD peripherals with enumerated values and pin names, statics, updates in place
  after a step.
- SVD download (atsamd-rs and cmsis-svd-data), including the `<fpuPresent>` fix-up for probe-rs's parser.
- The Peripherals tool window with the downloaded SVD.

Timings with this probe: a stop takes about 0.75 s until RustRover has the stack, scopes and locals (each DAP request
costs 130 to 150 ms in USB round trips), plus up to 3 s when probe-rs's unwinder runs into its 500-frame limit (it does
on some stops of this Cortex-M0).

## Without hardware

| Feature | How it's verified |
|---|---|
| Detection from the `probe-rs run --chip …` runner, defmt in `[dependencies]` or `[dev-dependencies]` | Parser tests; headless RustRover: the driver provider loads and runs before the stock GDB/LLDB one |
| The relay (RTT handshake, progress, commands, stack cache, scope hints, evaluate context) | Unit tests, and end-to-end runs of the relay as a real child process against a scripted fake `probe-rs dap-server` |
| Peripherals: SVD tree (derivedFrom, clusters, arrays, enums), editing registers and fields | SVD parser and decoding tests; panel against fake memory |
| Live Watch: globals by name or address, chart | ELF reader (synthetic ELF), decoding, panel against a fake target |
| Error advice: no probe, several probes, busy, USB permissions, unknown chip, connect under reset, locked flash | Advisor tests against probe-rs's own error texts |
| Attach to Running Target | Driver configuration test |
| Settings, per-run-configuration overrides that follow renames | Headless: apply/reset, renames, fake `probe-rs` executable |
| Copy probe-rs Diagnostics (never includes the remote token) | Headless test, token check |
| Wokwi: Remote Debug (GDB) configuration from `wokwi.toml` | Headless: configuration created and serialized |
| RP2040 pin names | Against the SIO registers of rp-rs's `rp2040.svd`; no RP2040 at hand |
| embedded-test crates and the test tree | Detection test; probe-rs accepts IntelliJ's libtest flags (from its source) |

107 tests in total (`./gradlew test -PplatformTests=true`). The Plugin Verifier reports RustRover 2026.3 as compatible,
with 12 usages of internal API, all in `DapDriverConfiguration`.

## Not verified

Live Watch and RTT input with real silicon, disassembly, RustRover's Memory View (empty in the one session it was
opened), Evaluate Expression on statics, Attach, core dumps, installing probe-rs from the IDE, Wokwi with a running
simulation, the embedded-test tree, other chips and probes (in particular CMSIS-DAP v2, ST-Link and J-Link), macOS and
Windows. CI and Marketplace publishing are configured but haven't run.
