package dev.proberover.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.proberover.detect.DetectedProject

/** Settings | Build, Execution, Deployment | probe-rs */
class ProbeRsConfigurable(private val project: Project) : BoundConfigurable("probe-rs") {

    /** Visible for tests. */
    internal val perConfigurationTable by lazy { PerConfigurationTable(project) }

    override fun createPanel(): DialogPanel {
        val service = project.service<ProbeRsProjectService>()
        val settings = service.getState()
        return panel {
            row {
                checkBox("Debug embedded Cargo targets with probe-rs").bindSelected(settings::enabled)
            }
            group("Detected in this project") {
                row { label(describe(service.detect())) }
            }
            group("Overrides") {
                row("Chip:") {
                    textField()
                        .bindText({ settings.chipOverride.orEmpty() }, { settings.chipOverride = it.trim().ifEmpty { null } })
                        .comment("Leave empty to use <code>--chip</code> from the Cargo runner. <code>probe-rs chip list</code> shows valid names.")
                }
                row("SVD file:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor("svd"), project)
                        .align(AlignX.FILL)
                        .bindText({ settings.svdFile.orEmpty() }, { settings.svdFile = it.trim().ifEmpty { null } })
                        .comment("Shows the chip's peripheral registers in the debugger's Variables view.")
                }
                row("probe-rs executable:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(), project)
                        .align(AlignX.FILL)
                        .bindText({ settings.probeRsPath.orEmpty() }, { settings.probeRsPath = it.trim().ifEmpty { null } })
                        .comment(
                            "Leave empty to search <code>PATH</code> and <code>~/.cargo/bin</code>. " +
                                (service.probeRsExecutable()?.let { "Currently using <code>$it</code>." }
                                    ?: "probe-rs isn't installed there. Install it with <code>cargo binstall probe-rs-tools</code>."),
                        )
                }
            }
            group("Per run configuration") {
                row {
                    cell(perConfigurationTable.component)
                        .align(Align.FILL)
                        .comment("Used when you debug that Cargo run configuration. Empty cells use the settings above. " +
                            "Turn off Flash to debug the firmware already on the chip.")
                }.resizableRow()
            }
            onReset { perConfigurationTable.reset(settings.perConfiguration) }
            onIsModified { perConfigurationTable.current() != settings.perConfiguration }
            onApply { settings.perConfiguration = perConfigurationTable.current() }
        }
    }

    companion object {
        fun describe(d: DetectedProject?): String {
            if (d == null) return "No Cargo project found."
            val target = d.buildTarget ?: "no build.target"
            return when {
                d.probeRs != null -> "probe-rs runner for chip ${d.probeRs.chip} ($target)" +
                    (d.probeRs.probe?.let { ", probe $it" } ?: "") + ", RTT as ${d.probeRs.rttFormat.wireName}"
                d.isEmbeddedTarget -> "Embedded target $target, but the runner isn't probe-rs. Set a chip below to debug with probe-rs."
                else -> "Not an embedded project ($target). Debugging uses the normal debugger."
            }
        }
    }
}
