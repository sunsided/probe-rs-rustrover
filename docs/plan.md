# probe-rs for RustRover: plugin plan

*Status: draft, November 16, 2026. Checked against RustRover 2026.3 (build 263.7307.214) and probe-rs `master` at f42ee9b.*

## North star

Open an existing embedded Rust project (Embassy, RTIC or bare `cortex-m-rt`) and click the normal **Debug** gutter icon on `fn main`. Then:

1. Cargo builds the firmware.
2. probe-rs flashes it, and the debugger stops at `main`.
3. Breakpoints, stepping, variables, disassembly and memory work as they do on the host.
4. defmt/RTT logs stream into a console tab, with levels colored and `file:line` locations clickable.
5. MCU peripherals (from SVD) can be expanded in the Variables view.

You shouldn't have to write any JSON or change any configuration if the project already has a `probe-rs run --chip …` runner in `.cargo/config.toml`, which almost every embedded Rust template does.

Everything else in this plan supports that experience: detection, error messages, setup help.

---

## Prior art (what exists and what doesn't)

I searched the JetBrains Marketplace (API: probe-rs, embedded, openocd, DAP, defmt, RTT, SVD, stm32, esp32, rust embedded, …), the web, and RUST-12476.

**Nobody has built a probe-rs integration for any JetBrains IDE.** There's no defmt or RTT plugin either. What does exist:

| What | Who / where | Relevance |
|---|---|---|
| OpenOCD + STM32CubeMX plugin | Elmot (later absorbed into CLion) | Showed a third-party plugin on the CIDR debugger can work. C/C++ and GDB only. |
| OpenOCD + ESP32 (18760, 11284) | Forks of Elmot's plugin; 18760 still updated Oct 2026 ([source](https://github.com/ThexXTURBOXx/clion-embedded-esp32)) | CLion-only, GDB/OpenOCD, C focus. Worth reading for how they handle the run configuration and flashing. |
| **STM32 for CLion** (32117) | **JetBrains**, 2026 | ST-LINK gdbserver debug profile, live watches, SWO console. Shows JetBrains' own pattern: a vendor plugin built on the new *debug profiles* framework. CLion-only. |
| Waveform Plotter (30704) | szturin ([source](https://github.com/Szturin/Clion-Waveform-Plotter)) | CLion, OpenOCD telnet: live-watch plots and RTT streaming. Good idea for a later phase. |
| ITM Viewer (14163) | cosee | Abandoned in 2023. |
| Flexible For Arduino (31027) | ilscipio, paid | ESP-IDF/PlatformIO/Zephyr build+flash+JTAG. C/C++, not Rust. |
| Romsight (34943) | paid | Linker-map analyzer that understands Cargo. Adjacent, not overlapping. |
| **Wokwi Simulator** (23826) | Jozott00, community, MIT ([source](https://github.com/Jozott00/wokwi-intellij)); 16k downloads, v0.12.0 on Oct 30, 2026, since-build 262 | Runs the Wokwi simulator in an IDE tool window, reads `wokwi.toml`, has serial I/O and restart on rebuild, and bridges Wokwi's in-browser GDB stub to a localhost GDB port. **IDE debugging is "CLion only"** (its tests drive a CLion Remote Debug configuration). Natural partner; see the Wokwi section. |
| rust:embd:ide | nexoscp (GitHub, old) | Early IntelliJ Rust-embedded attempt; probe-rs support was "planned" and never shipped. |
| EmbeddedRover | lure23 (GitHub) | A project template for ESP32 + RustRover, not a plugin. |
| nvim-dap-probe-rs, Zed probe-rs extension, probe-rs VS Code extension | various | Reference implementations of probe-rs *client* behavior: launch JSON, RTT handshake, custom events. |
| LSP4IJ DAP, "Debug Adapter Protocol" (20934) | Red Hat / JetBrains | Generic DAP clients. LSP4IJ lacks ReadMemory and Rust-aware integration; 20934 is the Fleet-family one. Neither is connected to Cargo. |

YouTrack: RUST-12476 (probe-rs, 88 votes, open) and RUST-11880 (Embedded GDB Server, open). Users report that a DAP profile can be created in RustRover 2026.2, but Cargo configurations can't use it. That's the gap this plugin fills.

---

## What the platform gives us (checked in RustRover 2026.3 bytecode)

**1. A public extension point in the Rust plugin for swapping the debugger backend.**
```
<extensionPoint qualifiedName="org.rust.debugger.driverConfigurationProvider"
                interface="org.rust.debugger.RsDebuggerDriverConfigurationProvider" dynamic="true"/>

fun getDebuggerDriverConfiguration(project: Project, host: RsToolchainHost,
                                   isElevated: Boolean, emulateTerminal: Boolean): DebuggerDriverConfiguration?
```
The stock provider is registered with `order="last"`. The Cargo debug runner (`RsDebugRunParametersKt.chooseConfiguration(env, …)`) asks each provider in turn and falls back to LLDB if none answers. **Registering ours with `order="first"` and returning `null` for non-embedded configurations leaves everything else untouched.**

**2. A complete DAP backend for the CIDR debugger** (`nativeDebug-plugin/lib/modules/intellij.cidr.debugger.dap.jar`):
- `abstract class DapDriverConfiguration(project, adapterId, isElevated, emulateTerminal, startRequestType, useSingleThreadStepping, takeRequestTypeFromDapOptions) : DebuggerDriverConfiguration`
- abstract `getDapLaunchOptions(GeneralCommandLine): Map<String, Any>`. The command line is the **Cargo-built ELF**, so we get `programBinary` for free.
- `getCommunicationMethod()` returns `DapCommunicationMethod.Stdio` or `Tcp(port)`.
- `createDriverCommandLine(...)` for starting the adapter process.
- `DapDriver` already implements breakpoints, instruction breakpoints, disassembly, `readMemory`/memory view, registers and stepping on top of lsp4j.debug.

**3. A Cargo run-configuration extension point:** `org.rust.runConfigurationExtension` (`CargoCommandConfigurationExtension`), with `patchCommandLine`, `patchCommandLineState` and `attachToProcess`, plus settings-editor support from `RunConfigurationExtensionBase`. This is where per-configuration "Embedded" settings and launch context go.

**4. Debug profiles** (`cidr.debugger.debugProfileType`, `…debugProfilesEnabler`, …) exist and RustRover ships a DAP profile type, but in RustRover **nothing calls them** (only the CLion-only module `intellij.cidr.debugger.profiles.clion` does). We don't depend on them. We might add a probe-rs profile type later, for CLion users and for consistency with STM32 for CLion.

**5. probe-rs `dap-server` capabilities** (from source):
- Transport: **stdio by default**, `--port` for TCP, and `--host` for a remote probe-rs server.
- Requests: launch, attach, `restart`, `setBreakpoints`, `setInstructionBreakpoints`, `readMemory`, `writeMemory`, `disassemble`, `evaluate` (hover too), `setVariable`, `completions`, stepping granularity.
- Launch config: `chip`, `chipDescriptionPath`, `probe`, `speed`, `wireProtocol`, `connectUnderReset`, `allowEraseAll`, `flashingConfig{flashingEnabled, haltAfterReset, verifyBefore/After, fullChipErase, restoreUnwrittenBytes, formatOptions}`, `coreConfigs[{coreIndex, programBinary, svdFile, rttConfig, catchReset, catchHardfault}]`, `consoleLogLevel`, `cwd`.
- **SVD:** loaded per core and exposed as a variables scope, reloaded when the file changes.
- **Custom events:** `probe-rs-rtt-channel-config` (the client answers with the custom `rttWindowOpened` request), `probe-rs-rtt-data` (already decoded, defmt included), `probe-rs-show-message`, plus progress events. There are also REPL commands (`rtt`, `registers`, `embedded_test`, …).

**Gaps we have to fill ourselves:**
- `DapDriver`'s client is a private inner class, so **custom probe-rs events are dropped**. RTT/defmt and messages need a different path (see the DAP tap below).
- The provider receives only `(project, host, …)`, **not the run configuration or `ExecutionEnvironment`**. We need a way to pass per-launch context (spike item S2).
- `DapDriverConfiguration` and friends are internal and change with each release. Keep the code that touches them small and isolated.

---

## Architecture

```
Cargo Debug (gutter / run config)
  └─ RsDebugRunner ── chooseConfiguration(env)
        └─ ProbeRsDriverConfigurationProvider  (order="first")
              ├─ not embedded? → null → stock GDB/LLDB
              └─ embedded → ProbeRsDriverConfiguration : DapDriverConfiguration
                    ├─ communication: Stdio (or Tcp → our tap)
                    ├─ driver cmdline: probe-rs dap-server   (wrapped by DapTap)
                    └─ launch options ← EmbeddedSettings + ELF from GeneralCommandLine

DapTap (in-plugin DAP message tap, between DapDriver and probe-rs)
  ├─ forwards everything DapDriver understands
  ├─ swallows probe-rs-* events → plugin message bus
  │     rtt-channel-config → send rttWindowOpened back, open console tab
  │     rtt-data           → RTT/defmt console tab (per channel)
  │     show-message       → IDE notification
  │     progress*          → IDE progress indicator ("Flashing… 63%")
  └─ can inject custom requests (rttWindowOpened, REPL commands)

EmbeddedSettings  (resolved in this order, each level overriding the one before)
  1. auto-detect: .cargo/config.toml runner "probe-rs run --chip X …", build.target (thumbv*/riscv32*), Embed.toml
  2. project defaults (Settings | Build | Embedded Rust)
  3. per-config "Embedded" tab on Cargo run configs (CargoCommandConfigurationExtension)
```

**Why a DAP tap rather than subclassing `DapDriver`:** it works at the protocol level, so internal API changes in JetBrains' code mostly don't affect it. It's easy to test with recorded transcripts, and it gives us full control over the probe-rs extensions. Implementation: wrap the adapter process (`createDriverCommandLine` → our launcher), or point `getCommunicationMethod()` at a loopback TCP port the plugin serves, and use lsp4j's `StreamMessageProducer`/`MessageConsumer` (already on the classpath) to parse and forward messages. Roughly 300 lines of Kotlin.

---

## UX details

**First contact**
- On project open, when `build.target` is `thumbv*`, `riscv32*` or `xtensa*`, *or* the Cargo runner mentions `probe-rs`, show one notification: "Embedded target detected: debug with probe-rs?" with **Enable** / **Not now**. Never nag.
- If `probe-rs` isn't on PATH, offer **Install** (runs the official installer or `cargo binstall probe-rs-tools` in a terminal). Check the minimum version and explain what's missing.
- On Linux, when probe access is denied, show a notification that links to udev rules and offers to copy the rule file. This is the most common first-time failure.

**Everyday**
- **Debug** on any Cargo binary configuration does the right thing. **Run** keeps using the Cargo runner (`probe-rs run`), so behavior doesn't change.
- Chip field has completion from `probe-rs chip list`, cached and fuzzy-matched. If several probes are connected, a probe picker shows (from `probe-rs list`) and the choice is remembered per project.
- Flashing progress appears in the IDE progress bar. Errors show the probe-rs message plus a suggested fix (probe busy → "close other sessions / VS Code"; wrong chip; locked flash → offer `allowEraseAll` with a warning).
- RTT/defmt console: one tab per channel, colored by log level, `src/main.rs:42` turned into links, filter by level, timestamps if defmt provides them. The same console filter applies to **Run** output from `probe-rs run`, so log lines are clickable without debugging too.
- Peripherals: SVD path auto-filled from Embed.toml or settings. Show the probe-rs SVD scope first; add a dedicated **Peripherals** tool window later (pin registers, bit fields, hex/binary display), similar to CLion.
- Restart (⌘F5) is mapped to probe-rs `restart`, which reflashes only if the ELF changed.

**For power users**
- `.run`/project-level JSON escape hatch: any extra launch keys are merged into the probe-rs launch JSON as-is.
- Attach mode (don't flash, don't reset). Remote probe-rs server (`--host`). Multi-core selection once probe-rs supports it beyond core 0.

---

## Wokwi simulator as a second target

**Goal:** the same **Debug** button, with *Target: Simulator (Wokwi)* instead of *Target: Probe*. This is useful when there's no board at hand, for CI-style reproductions, and for teaching. probe-rs can't drive Wokwi (Wokwi exposes a GDB remote stub, not a debug probe), so this is a **GDB backend**, not DAP.

**What already exists:** the Wokwi Simulator plugin (`com.github.jozott00.wokwiintellij`) handles the hard part: running the simulator in the IDE and serving its GDB stub on `localhost:<gdbServerPort>` from `wokwi.toml`. What it lacks is RustRover debugging. Its debugging relies on CLion's remote-debug configurations.

**What we add:**
- A `WokwiTargetBackend` in our `driverConfigurationProvider`. For Cargo Debug with target = Wokwi, return a GDB driver configuration. RustRover has `RsUserBinariesGDBDriverConfiguration` (a custom GDB binary with Rust renderers), and the Remote Debug flow (`RsRemoteRunConfiguration` / `RemoteLauncher`) shows how to connect with `target remote` instead of launching. ELF path from the Cargo build, port from `wokwi.toml`.
- An **optional dependency** on the Wokwi plugin: start the simulation in "wait for debugger" mode, then attach. If the Wokwi plugin isn't installed, show a notification with an install link.
- GDB auto-discovery per target: `xtensa-esp32*-elf-gdb` / `riscv32-esp-elf-gdb` (installed by `espup`), `arm-none-eabi-gdb` or `gdb-multiarch` for RP2040/STM32. Check whether RustRover's bundled GDB is built multi-arch. If it is, ARM targets need no extra install.
- Reuse the defmt console: Wokwi gives serial, not RTT. Decode defmt-over-serial (the `esp-println` defmt / espflash format) by piping it through `defmt-print -e <elf>`. You get the same colored, clickable log view on the board and in the simulator.
- Auto-detection: a `wokwi.toml` + `diagram.json` in the project offers the Simulator target. If both a probe-rs runner and `wokwi.toml` are present, add a target toggle to the run toolbar (**Probe ▸ / Simulator ▸**).

**Better still: contribute upstream.** RustRover debugging belongs naturally in wokwi-intellij itself, and the maintainer already has GDB-bridge tests. Plan: build it in our plugin as a separate module, then offer it as a PR to wokwi-intellij once it's stable. We keep only the "same Debug button, choose target" glue.

**Limits:** no SVD peripheral scope (that comes from probe-rs; a GDB-based peripheral view could come later). Debugging behavior depends on how complete Wokwi's GDB stub is per chip (ESP32 Xtensa/RISC-V, RP2040, STM32 vary).

---

## Phases

### Phase 0: spike (2–4 days). The goal is to prove the risky parts on real hardware.
- **S1.** Hard-coded `ProbeRsDriverConfiguration` returned from a provider registered with `order="first"`. Debug a blinky on an RP2040, nRF52840 or STM32 board. Check: source breakpoints in Rust code, step over/into, Rust variable rendering *through probe-rs's DWARF reader* (enums, slices, `Option`, Embassy futures), disassembly, memory view, the register view (`supportsRegisters`).
- **S2.** Per-launch context. Is there a clean way to get the current `ExecutionEnvironment` or run configuration in the provider? Candidates, in order:
  1. Store context in `CargoCommandConfigurationExtension.patchCommandLineState` (keyed by project and execution ID) and consume it in the provider.
  2. Our own `ProgramRunner` for Cargo configurations, ranked above `RsDebugRunner`, that delegates to `RsDebugRunnerBase` and sets the context.
  3. Read project-level settings only (good enough for an MVP).
- **S3.** DAP tap: put it in the stream and confirm `DapDriver` keeps working. Get RTT data showing in a plain console.
- **S4.** Check how CIDR renders probe-rs's extra scopes (SVD peripherals, static variables).
- **Exit criterion:** a 2-minute screen recording of the north-star flow, even if rough.

### Phase 1: MVP, 0.1 on the Marketplace (2–3 weeks)
- Auto-detection, project settings, and the per-configuration "Embedded" tab.
- Provider, driver configuration and DAP tap, with RTT/defmt console tabs (text only).
- probe-rs discovery and version check, the install notification, the udev helper, and error-to-fix mapping for the 5 most common errors.
- Compatibility: RustRover 2026.3+ (`since-build 263`). Dependencies: `com.jetbrains.rust` and `com.intellij.nativeDebug`. That also covers CLion and IntelliJ IDEA Ultimate with the Rust plugin, since the Rust plugin requires Native Debugging Support starting with 2026.2. Test on those as well.
- Open source (Apache-2.0 or MIT/Apache, the same as probe-rs), free. Mention it in RUST-12476 and RUST-12499 and in the probe-rs Matrix channel.

### Phase 2: polish (3–4 weeks)
- defmt console: levels, links, filtering, clear on restart, the same filter for Run output.
- Chip completion, probe picker, flashing progress, restart mapping.
- macOS and Windows verification. The DAP route means **no cross-GDB is needed on macOS**, which is a real advantage over the GDB/OpenOCD approach.
- Peripherals tool window (Phase 2b if time allows).

### Phase 3: extras, ranked by impact
1. **embedded-test integration:** run `#[embedded_test::tests]` from the gutter, results in the IDE test tree (probe-rs has `embedded_test` support in the DAP REPL and `probe-rs-test` events).
2. Live watch / plotting while the target runs (non-halting memory reads; Waveform Plotter is the model).
3. Core-dump debugging (probe-rs can load core dumps), attach mode, remote probe-rs server.
4. **Wokwi target** (see the Wokwi section). It could move up to Phase 2 if you mostly work on ESP32: about 1–2 weeks with the Wokwi plugin as an optional dependency, the most work being GDB discovery and the remote-attach flow. It needs a short spike first: RustRover GDB driver plus the Wokwi plugin's GDB bridge, attached to an ESP32-C3 `esp-hal` blinky.
5. Optional debug profile type, so CLion users get the same feature in CMake-based projects with Rust components.

---

## Engineering hygiene

- **Keep internal-API use in one module** (`backend-cidr`): the provider, `ProbeRsDriverConfiguration`, and the tap launcher. Everything else uses only public platform APIs.
- **CI:** Gradle IntelliJ Platform Plugin 2.x. Run the Plugin Verifier against the current release, the latest EAP, CLion and IntelliJ IDEA Ultimate. Run it nightly against EAP so breakages show up weeks before a release.
- **Tests:**
  - Unit tests for detection (parsing `.cargo/config.toml` and `Embed.toml`) and for building the launch JSON (golden files).
  - DAP tap tests driven by **recorded probe-rs transcripts**, so no hardware is needed in CI.
  - An optional hardware-in-the-loop job on a self-hosted runner with a cheap probe (Raspberry Pi Debug Probe + Pico).
- **Telemetry:** none. Instead, add an opt-in "Copy diagnostics" action (probe-rs version, launch JSON, last 200 DAP messages) for bug reports.

## Risks and open questions

| Risk | Mitigation |
|---|---|
| `DapDriverConfiguration` signature changes between releases | Isolated module, nightly verifier against EAP, a small surface |
| The provider can't see the run configuration (S2) | Three fallbacks listed above; project-level settings are enough for an MVP |
| probe-rs variable rendering is weaker than LLDB's Rust formatters | Measure in S1. Report gaps upstream to probe-rs (it benefits every editor) |
| `DapDriver` misbehaves with probe-rs specifics (e.g. `restart`, thread model, instruction-breakpoint adjustment) | Override the relevant hooks (`enableInstructionBreakpointAdjustment`, `useSingleThreadStepping`), or fix it in the tap |
| JetBrains ships their own integration | Ignored by request. If it happens, the tap, defmt console and peripherals still add value on top |

## Sources
- [RUST-12499](https://youtrack.jetbrains.com/issue/RUST-12499), [RUST-12476](https://youtrack.jetbrains.com/issue/RUST-12476), [RUST-11880](https://youtrack.jetbrains.com/issue/RUST-11880)
- JetBrains Marketplace API search (November 16, 2026); plugin pages for 32117, 18760, 30704, 14163, 31027, 20934
- RustRover 2026.3 (263.7307.214): plugin descriptors and bytecode (`javap`) of `intellij-rust` and `nativeDebug-plugin`
- probe-rs `master` f42ee9b: `probe-rs-tools/src/bin/probe-rs/cmd/dap_server/**`
- [CLion DAP support](https://www.jetbrains.com.cn/en-us/help/clion/2025.3/dap-support.html), [nvim-dap-probe-rs](https://github.com/abayomi185/nvim-dap-probe-rs), [probe-rs VS Code extension](https://github.com/probe-rs/vscode)
