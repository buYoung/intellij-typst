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
import com.livteam.typninja.settings.TypstSettingsService

class TypstExportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        event.presentation.isEnabledAndVisible = project != null &&
            file?.fileType == TypstFileType &&
            document != null && !FileDocumentManager.getInstance().isDocumentUnsaved(document) &&
            TypstToolchainService.getInstance(project).currentCapability().isValid
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val source = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val format = TypstSettingsService.getInstance(project).state.defaultExportFormat.orEmpty().lowercase()
        if (format !in SUPPORTED_FORMATS) return
        val descriptor = FileSaverDescriptor("Export Typst", "Choose a Typst export destination", format)
        val destination = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(source.parent, source.nameWithoutExtension)
            ?.file
            ?.toPath()
            ?: return
        TypstPreviewService.getInstance(project).export(source, format, destination)
    }

    private companion object {
        val SUPPORTED_FORMATS = setOf("pdf", "png", "svg")
    }
}
