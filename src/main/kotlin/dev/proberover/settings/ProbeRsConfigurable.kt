package dev.proberover.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.proberover.cli.ProbeInfo
import dev.proberover.cli.ProbeRsTools
import dev.proberover.detect.DetectedProject
import javax.swing.DefaultComboBoxModel

/** Settings | Build, Execution, Deployment | probe-rs */
class ProbeRsConfigurable(private val project: Project) : BoundConfigurable("probe-rs") {

    /** Visible for tests. */
    internal val perConfigurationTable by lazy { PerConfigurationTable(project) }

    /** Visible for tests. */
    internal val chipField by lazy {
        TextFieldWithAutoCompletion.create(project, emptyList<String>(), true, "").apply { setPreferredWidth(JBUI.scale(320)) }
    }

    private val probeModel = DefaultComboBoxModel<String>()
    private val probeField = ComboBox(probeModel).apply {
        isEditable = true
        setMinimumAndPreferredWidth(JBUI.scale(320))
    }
    private val tokenField = JBPasswordField()

    override fun createPanel(): DialogPanel {
        val service = project.service<ProbeRsProjectService>()
        val settings = service.getState()
        loadChipsAndProbes(service)
        return panel {
            row {
                checkBox("Debug embedded Cargo targets with probe-rs").bindSelected(settings::enabled)
            }
            group("Detected in this project") {
                row { label(describe(service.detect())) }
            }
            group("Overrides") {
                row("Chip:") {
                    cell(chipField)
                        .comment("Leave empty to use <code>--chip</code> from the Cargo runner. Start typing for chip names.")
                }
                row("Probe:") {
                    cell(probeField)
                        .comment("Leave empty when only one probe is connected. Lists probes currently connected.")
                    button("Refresh") { loadChipsAndProbes(service) }.applyToComponent { icon = AllIcons.Actions.Refresh }
                }
                row("SVD file:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor("svd"), project)
                        .align(AlignX.FILL)
                        .bindText({ settings.svdFile.orEmpty() }, { settings.svdFile = it.trim().ifEmpty { null } })
                        .comment("For the Peripherals view and the debugger's Peripherals scope." +
                            (service.findSvdFiles().takeIf { it.isNotEmpty() }?.let { files -> " Found in the project: " + files.take(3).joinToString { "<code>${it.name}</code>" } } ?: ""))
                }
                row {
                    checkBox("Only reprogram flash that changed").bindSelected(settings::skipUnchangedFlash)
                        .comment("probe-rs compares the flash contents first. Faster for small changes.")
                }
                row("probe-rs executable:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(), project)
                        .align(AlignX.FILL)
                        .bindText({ settings.probeRsPath.orEmpty() }, { settings.probeRsPath = it.trim().ifEmpty { null } })
                        .comment(
                            "Leave empty to search <code>PATH</code> and <code>~/.cargo/bin</code>. " +
                                (service.probeRsExecutable()?.let { "Currently using <code>$it</code>." }
                                    ?: "probe-rs isn't installed there. Install it with Tools → probe-rs → Install or Update probe-rs."),
                        )
                }
            }
            collapsibleGroup("Remote probe-rs server") {
                row("Host:") {
                    textField().columns(28)
                        .bindText({ settings.remoteHost.orEmpty() }, { settings.remoteHost = it.trim().ifEmpty { null } })
                        .comment("A machine running <code>probe-rs serve</code> with the probe attached, e.g. <code>ws://pi.local:3000</code>. Leave empty for a local probe.")
                }
                row("Token:") {
                    cell(tokenField).columns(28).comment("Stored in the IDE's password safe, not in the project.")
                }
            }.expanded = !settings.remoteHost.isNullOrBlank()
            group("Per run configuration") {
                row {
                    cell(perConfigurationTable.component)
                        .align(Align.FILL)
                        .comment("Used when you debug that Cargo run configuration. Empty cells use the settings above. " +
                            "Turn off Flash to debug the firmware already on the chip.")
                }.resizableRow()
            }
            onReset {
                perConfigurationTable.reset(settings.perConfiguration)
                chipField.text = settings.chipOverride.orEmpty()
                probeField.selectedItem = settings.probe.orEmpty()
                tokenField.text = service.remoteToken.orEmpty()
            }
            onIsModified {
                perConfigurationTable.current() != settings.perConfiguration ||
                    chipField.text.trim() != settings.chipOverride.orEmpty() ||
                    selectedProbe() != settings.probe.orEmpty() ||
                    String(tokenField.password) != service.remoteToken.orEmpty()
            }
            onApply {
                settings.perConfiguration = perConfigurationTable.current()
                settings.chipOverride = chipField.text.trim().ifEmpty { null }
                settings.probe = selectedProbe().ifEmpty { null }
                val token = String(tokenField.password)
                if (token != service.remoteToken.orEmpty()) service.remoteToken = token
            }
        }
    }

    /** The probe selector: the part before " – " when a listed probe was picked, or what was typed. */
    private fun selectedProbe(): String =
        (probeField.editor.item?.toString() ?: probeField.selectedItem?.toString()).orEmpty().substringBefore("  ").trim()

    private fun loadChipsAndProbes(service: ProbeRsProjectService) {
        val probeRs = service.probeRsExecutable() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val chips = runCatching { ProbeRsTools.get().chips(probeRs) }.getOrDefault(emptyList())
            val probes = runCatching { ProbeRsTools.get().probes(probeRs) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                chipField.setVariants(chips)
                val current = selectedProbe()
                probeModel.removeAllElements()
                probeModel.addElement("")
                probes.forEach { probeModel.addElement(probeItem(it)) }
                probeField.selectedItem = probes.firstOrNull { it.selector == current }?.let(::probeItem) ?: current
            }, ModalityState.any())
        }
    }

    private fun probeItem(p: ProbeInfo) = "${p.selector}  ${p.name}" + if (p.accessible) "" else " (no permission)"

    companion object {
        fun describe(d: DetectedProject?): String {
            if (d == null) return "No Cargo project found."
            val target = d.buildTarget ?: "no build.target"
            return when {
                d.probeRs != null -> "probe-rs runner for chip ${d.probeRs.chip} ($target)" +
                    (d.probeRs.probe?.let { ", probe $it" } ?: "") + ", RTT as ${d.probeRs.rttFormat.wireName}" +
                    (if (d.usesEmbeddedTest) ". embedded-test: run tests from the gutter; results appear in the test tree" else "")
                d.isEmbeddedTarget -> "Embedded target $target, but the runner isn't probe-rs. Set a chip below to debug with probe-rs."
                else -> "Not an embedded project ($target). Debugging uses the normal debugger."
            }
        }
    }
}
