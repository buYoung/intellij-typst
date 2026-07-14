package com.livteam.typninja.language.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager
import com.livteam.typninja.execution.TypstToolchainService
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.preview.TypstPreviewService

class TypstPreviewAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        event.presentation.isEnabledAndVisible = project != null &&
            file?.fileType == TypstFileType &&
            document != null &&
            TypstToolchainService.getInstance(project).currentCapability().isValid
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val currentText = FileDocumentManager.getInstance().getDocument(file)?.text
        TypstPreviewService.getInstance(project).preview(file, currentText)
        ToolWindowManager.getInstance(project).getToolWindow("Typst Preview")?.show()
    }
}
