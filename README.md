# probe-rs for RustRover

Click **Debug** on a Cargo configuration in an embedded Rust project, and RustRover debugs through
`probe-rs dap-server` instead of GDB/LLDB: the firmware is flashed, defmt/RTT logs get their own tab, and probe-rs
errors come with a suggested fix. Research and roadmap: [`docs/plan.md`](docs/plan.md).

**Status: untested on hardware.** Everything here was built and tested against an unlicensed RustRover 2026.3
(263.7307.214) install, with no debug probe or board. See [What's verified](#whats-verified).

## Features

| Feature | Where | Verified how |
|---|---|---|
| probe-rs debugging for Cargo configurations, detected from the `probe-rs run --chip …` runner | Debug button / gutter | Headless RustRover: provider loads and runs before the stock GDB/LLDB one |
| Breakpoints, stepping, variables, disassembly, memory, registers | RustRover's debugger UI, fed by probe-rs | **Not verified** (needs a board) |
| SVD peripherals | "Peripherals" scope in Variables (probe-rs) | **Not verified** |
| **RTT tab per channel** in the debug session, defmt levels in your color scheme's log colors, `src/main.rs:42` clickable | Debug tool window | Relay and side channel end to end; console rendering headless (below) |
| defmt colors and links in **Run** output too (`probe-rs run`) | Run tool window | Console filter, headless |
| Flashing progress in the IDE progress bar | Status bar | Relay and side channel end to end; progress UI not seen |
| probe-rs errors as notifications with a fix (no probe, busy probe, USB permissions incl. udev, unknown chip, connect under reset, locked flash, …) | Notifications | Advisor unit tests against probe-rs's own error texts |
| Settings: on/off, chip, SVD file, probe-rs path, **per-run-configuration overrides** (chip, probe, SVD, flash on/off, RTT mode) | Settings → Build, Execution, Deployment → probe-rs | Headless: apply/reset round trip |

<p>
  <img src="docs/screenshots/rtt-console.png" alt="RTT console with colored defmt levels and clickable source locations" width="640">
</p>
<p>
  <img src="docs/screenshots/settings.png" alt="probe-rs settings page with per-run-configuration overrides" width="640">
</p>

Rendered headlessly by `ScreenshotsInRustRoverTest`. The offscreen renderer leaves out the table's column header and
the +/- toolbar; they should appear in the real IDE.

## How it works

```
Cargo Debug ─▶ RsDebugRunner ─▶ ProbeRsDriverConfigurationProvider (org.rust.debugger.driverConfigurationProvider, first)
                                   └─ ProbeRsDriverConfiguration (RustRover's DapDriverConfiguration)
                                        └─ RelayMain child process ──stdio──▶ probe-rs dap-server
                                              └─ side channel (loopback socket + token) ─▶ ProbeRsSessionUi:
                                                   RTT tabs · flashing progress · error notifications
```

RustRover's DAP client drops probe-rs's custom events and standard progress events, so the relay answers probe-rs's
RTT handshake itself and sends RTT data, progress and messages to the IDE over a side channel. Without the side
channel, RTT falls back to the debug console.

Per-run-configuration settings live in the project's probe-rs settings, keyed by configuration name: Cargo run
configurations don't persist plugin data, and their editor has no room for extension tabs. The Rust plugin's debugger
extension point isn't told which configuration it is debugging, so `ProbeRsExecutionListener` records the launch.
Renaming a run configuration loses its overrides.

## What's verified

- **Compiles and loads** against RustRover 2026.3, including the `visibility="internal"` modules it builds on (test mode).
- **44 tests:** detection, launch JSON (field names from probe-rs's source), defmt parsing, error advice, relay routing,
  three end-to-end runs of the relay as a real child process against a scripted fake `probe-rs dap-server`, and headless
  RustRover tests for plugin loading, the console filter, the settings page, the launch tracker and the screenshots.
- **Plugin Verifier:** compatible with RustRover 2026.3, 12 usages of internal API, all in `DapDriverConfiguration`.

**Not verified:** anything that needs a board or a licensed IDE: real debugging, how probe-rs's values, registers and
peripherals look in RustRover's UI, the RTT tab inside a real debug session, the progress bar, whether the production IDE
enforces module visibility more strictly than test mode.

**Main risk:** `DapDriverConfiguration` is marked internal. JetBrains may change it without notice, and Marketplace
review may object. Worth asking on RUST-12476 for a small public DAP-debugger extension point.

## Running

```bash
./gradlew test                          # unit and end-to-end relay tests
./gradlew test -PplatformTests=true     # adds the headless RustRover tests and screenshots (build/screenshots)
./gradlew verifyPlugin                  # Plugin Verifier
./gradlew buildPlugin                   # build/distributions/probe-rs-rustrover-0.0.1-spike.zip
```

By default the build downloads RustRover 2026.3 and a Java 25 toolchain (RustRover 2026.3 runs on Java 25). To use a
local install instead, set `rustRoverPath` in `~/.gradle/gradle.properties`, or pass `-PrustRoverPath=…`. With a local
install, `verifyPlugin` runs offline. The end-to-end tests need `python3`.

## Trying it on hardware

1. Install the zip from `buildPlugin` into RustRover 2026.3 (Settings → Plugins → ⚙ → Install Plugin from Disk).
2. Open a project whose `.cargo/config.toml` has a runner like `probe-rs run --chip RP2040`.
3. Click **Debug** on the `fn main` gutter icon. Expect a "Flashing device" progress bar, then an **RTT** tab next to
   the debugger's Console.
4. If something fails, run the relay by hand to see the DAP traffic:
   `java -cp <plugin jars> dev.proberover.relay.RelayMain -- probe-rs dap-server`.
