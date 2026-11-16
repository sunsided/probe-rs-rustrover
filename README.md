# probe-rs for RustRover: spike

Phase 0 spike for the plan in [`docs/plan.md`](docs/plan.md): click **Debug** on a Cargo configuration in an
embedded project, and RustRover debugs through `probe-rs dap-server` instead of GDB/LLDB.

Built and tested only against an unlicensed RustRover 2026.3 (263.7307.214) install, with no debug probe or board.

## What's here

| File | Role |
|---|---|
| `detect/CargoConfigDetector.kt` | Reads `.cargo/config.toml` (`build.target`, `probe-rs run --chip …` runner) and `Cargo.toml` (defmt) |
| `launch/LaunchConfigBuilder.kt` | Builds probe-rs `launch`/`attach` JSON, field names checked against probe-rs `master` |
| `debugger/ProbeRsDriverConfigurationProvider.kt` | `org.rust.debugger.driverConfigurationProvider`, `order="first"`; returns null for non-embedded projects |
| `debugger/ProbeRsDriverConfiguration.kt` | Subclass of RustRover's `DapDriverConfiguration`; starts the relay over stdio |
| `relay/ProbeRsRelay.kt`, `RelayMain.kt` | Child process between the IDE and probe-rs: answers `rttWindowOpened`, turns RTT/defmt data and messages into standard DAP `output` events |
| `settings/ProbeRsProjectService.kt` | Project settings (on/off, chip override, probe-rs path, SVD) and probe-rs lookup |
| `selfcheck/SelfCheckStarter.kt` | Spike-only headless load check (blocked: the real product needs a license) |

## Results

| Question | Answer | Evidence |
|---|---|---|
| Does it compile against RustRover's debugger and DAP classes? | **Yes** | `compileKotlin` against the local IDE |
| Can a third-party plugin implement the Rust plugin's `driverConfigurationProvider`, although its module is `visibility="internal"`? | **Yes, in test mode** | `PluginLoadsInRustRoverTest`: loaded as a custom plugin; provider order is `[ProbeRsDriverConfigurationProvider, RsDefaultDebuggerDriverConfigurationProvider]` |
| Does the relay handle probe-rs's RTT handshake and data? | **Yes, against a scripted fake** | `ProbeRsRelayTest` end-to-end: real child JVM, production command line, fake `dap-server` in Python |
| Does the launch JSON match what probe-rs deserializes? | **Matches the source** | Golden test; field names read from `configuration.rs`, `rtt.rs`, `rtt_config.rs` |
| Plugin Verifier | **Compatible. 13 usages of internal API** | All 13 are `DapDriverConfiguration` (`@ApiStatus.Internal`). The Rust extension point is not flagged |

**Not yet known (needs a board or a licensed IDE):**

- Real debugging: breakpoints, stepping, how Rust values look through probe-rs, the SVD peripheral scope, restart.
- Whether production mode enforces module visibility more strictly than test mode.
- Per-run-configuration settings (spike item S2). The spike uses project-level settings only.

**Main risk found:** `DapDriverConfiguration` is marked internal. It works today and the Verifier calls the plugin
compatible, but JetBrains may change that class without notice, and Marketplace review may object to internal API
use. Worth asking JetBrains (on RUST-12476) to make a small DAP-debugger extension public. The alternative is
implementing our own `DebuggerDriverConfiguration` and `DebuggerDriver`, which is far more work.

## Running

```bash
./gradlew test                          # detector, launch JSON, relay (incl. end-to-end with the fake server)
./gradlew test -PplatformTests=true     # adds the headless RustRover test
./gradlew verifyPlugin                  # Plugin Verifier against the local RustRover, offline
./gradlew buildPlugin                   # build/distributions/probe-rs-rustrover-0.0.1-spike.zip
```

By default the build downloads RustRover 2026.3 and a Java 25 toolchain (RustRover 2026.3 runs on Java 25). To use a
local install instead, set `rustRoverPath` in `~/.gradle/gradle.properties`, or pass `-PrustRoverPath=…`. With a local
install, `verifyPlugin` runs offline.

## Trying it on hardware

1. Install the zip from `buildPlugin` into RustRover 2026.3 (Settings → Plugins → ⚙ → Install from disk).
2. Open a project whose `.cargo/config.toml` has a runner like `probe-rs run --chip RP2040`.
3. Click **Debug** on the `fn main` gutter icon. RTT and defmt output appear in the debug console.
4. If something fails, run the relay by hand to see the DAP traffic:
   `java -cp <plugin jars> dev.proberover.relay.RelayMain -- probe-rs dap-server`.
