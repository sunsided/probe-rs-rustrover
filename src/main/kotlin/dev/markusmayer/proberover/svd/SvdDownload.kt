package dev.markusmayer.proberover.svd

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.io.HttpRequests
import dev.markusmayer.proberover.actions.ProbeRsNotifications
import dev.markusmayer.proberover.settings.ProbeRsProjectService
import java.io.File

/** Finds the chip's SVD file online, saves it into the project and selects it in the probe-rs settings. */
object SvdDownload {
    const val DEFAULT_FOLDER = ".idea/probe-rs"

    /** Offers to download an SVD file when a debug session starts without one. */
    fun offerIfMissing(project: Project, chip: String) {
        val service = project.service<ProbeRsProjectService>()
        val state = service.getState()
        if (state.svdOfferDismissed || !state.svdFile.isNullOrBlank() || service.findSvdFiles().isNotEmpty()) return
        ProbeRsNotifications.group().createNotification(
            "No SVD file for $chip",
            "The Peripherals view and the debugger's register names need the chip's SVD file. " +
                "Download it from GitHub (atsamd-rs, rp-rs or cmsis-svd-data)?",
            NotificationType.INFORMATION,
        )
            .addAction(NotificationAction.createSimpleExpiring("Download SVD…") { run(project, chip) })
            .addAction(NotificationAction.createSimpleExpiring("Choose file…") { chooseLocal(project) })
            .addAction(NotificationAction.createSimpleExpiring("Don't ask again") { state.svdOfferDismissed = true })
            .notify(project)
    }

    /**
     * Looks [chip] up in all [SvdCatalog.sources], lets the user pick one of the matches (the Rust PAC's first), and
     * downloads it, all in the background. [onDone] runs after the setting changed.
     */
    fun run(project: Project, chip: String, onDone: () -> Unit = {}) {
        object : Task.Backgroundable(project, "Finding SVD files for $chip", true) {
            override fun run(indicator: ProgressIndicator) {
                val candidates = findAll(chip, indicator)
                if (candidates.isEmpty()) {
                    notify(project, "No SVD file found for $chip", "None of atsamd-rs, rp-rs and cmsis-svd-data has one. " +
                        "Download it from the chip vendor and choose it with Choose file….", NotificationType.WARNING, chooser = true)
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
                        .setTitle("SVD File for $chip")
                        .setRenderer(com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { "${it?.name}  (${it?.source})" })
                        .setItemChosenCallback { download(project, chip, it, onDone) }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }
            }

            override fun onThrowable(error: Throwable) = failed(project, chip, error)
        }.queue()
    }

    private fun download(project: Project, chip: String, candidate: SvdCandidate, onDone: () -> Unit) {
        object : Task.Backgroundable(project, "Downloading ${candidate.name} from ${candidate.source}", true) {
            override fun run(indicator: ProgressIndicator) {
                val fixed = SvdCatalog.forProbeRs(HttpRequests.request(candidate.url).readString(indicator))
                val target = save(project, candidate.name, fixed.xml)
                use(project, target)
                val where = relativeToProject(project, target)
                if (fixed.problem == null) {
                    notify(project, "SVD file for $chip downloaded", "${candidate.name} from ${candidate.source}, saved as " +
                        "<code>$where</code>. Debug sessions from now on use it.", NotificationType.INFORMATION, chooser = true, chip = chip)
                } else {
                    notify(project, "probe-rs may not read ${candidate.name}", "Saved as <code>$where</code> and used by the " +
                        "Peripherals view, but probe-rs rejects it: ${fixed.problem}. Try another one.",
                        NotificationType.WARNING, chooser = true, chip = chip)
                }
                onDone()
            }

            override fun onThrowable(error: Throwable) = failed(project, chip, error)
        }.queue()
    }

    /** Lets the user pick an SVD file from disk. */
    fun chooseLocal(project: Project, onDone: () -> Unit = {}) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("svd").withTitle("Choose the Chip's SVD File")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            use(project, File(file.path))
            onDone()
        }
    }

    private fun use(project: Project, file: File) {
        project.service<ProbeRsProjectService>().getState().svdFile = relativeToProject(project, file)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun findAll(chip: String, indicator: ProgressIndicator): List<SvdCandidate> =
        SvdCatalog.sources.filter { it.appliesTo(chip) }.flatMap { source ->
            indicator.checkCanceled()
            indicator.text = "Looking in ${source.name}"
            val listing = source.listUrl?.let {
                HttpRequests.request(it).accept("application/vnd.github+json").readString(indicator)
            }
            val files = source.parse(listing)
            SvdCatalog.matches(chip, files.keys).map { SvdCandidate(source.name, it, files.getValue(it)) }
        }

    private fun save(project: Project, name: String, xml: String): File {
        val configured = project.service<ProbeRsProjectService>().getState().svdDownloadFolder?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER
        val folder = File(configured).let { if (it.isAbsolute) it else File(project.basePath ?: ".", configured) }
        folder.mkdirs()
        return File(folder, name).also { it.writeText(xml) }
    }

    private fun relativeToProject(project: Project, file: File): String {
        val base = project.basePath?.let(::File) ?: return file.path
        return if (file.startsWith(base)) file.relativeTo(base).path else file.path
    }

    private fun failed(project: Project, chip: String, error: Throwable) = notify(project,
        "Couldn't download the SVD file for $chip", error.message ?: error.toString(), NotificationType.ERROR, chooser = true)

    private fun notify(project: Project, title: String, content: String, type: NotificationType, chooser: Boolean = false, chip: String? = null) =
        ProbeRsNotifications.group().createNotification(title, content, type)
            .also { n ->
                if (chip != null) n.addAction(NotificationAction.createSimpleExpiring("Choose another…") { run(project, chip) })
                if (chooser) n.addAction(NotificationAction.createSimpleExpiring("Choose file…") { chooseLocal(project) })
            }
            .notify(project)
}

/** Tools → probe-rs → Download SVD File: for the chip the project debugs. */
class DownloadSvdAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val chip = project.service<ProbeRsProjectService>().resolveTarget()?.chip ?: return
        SvdDownload.run(project, chip)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && project.service<ProbeRsProjectService>().resolveTarget() != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
