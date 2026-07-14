package com.livteam.typninja.language.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.livteam.typninja.execution.TypstToolchainService
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.preview.TypstPreviewService
import com.livteam.typninja.preview.TypstExportSettings
import com.livteam.typninja.settings.TypstSettingsService

class TypstExportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        val format = project?.let { TypstExportSettings.format(TypstSettingsService.getInstance(it).state) }
        event.presentation.isEnabledAndVisible = project != null &&
            file?.fileType == TypstFileType &&
            document != null && format in SUPPORTED_FORMATS &&
            TypstToolchainService.getInstance(project).currentCapability().isValid
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val source = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val format = TypstExportSettings.format(TypstSettingsService.getInstance(project).state)
        if (format !in SUPPORTED_FORMATS) return
        val extension = FILE_EXTENSIONS.getValue(format)
        val descriptor = FileSaverDescriptor("Export Typst", "Choose a Typst export destination", extension)
        val destination = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(source.parent, source.nameWithoutExtension)
            ?.file
            ?.toPath()
            ?: return
        val currentText = FileDocumentManager.getInstance().getDocument(source)?.text
        TypstPreviewService.getInstance(project).export(source, format, destination, currentText)
    }

    private companion object {
        val SUPPORTED_FORMATS = setOf("pdf", "png", "svg", "html")
        val FILE_EXTENSIONS = mapOf(
            "pdf" to "pdf", "png" to "png", "svg" to "svg", "html" to "html",
        )
    }
}
