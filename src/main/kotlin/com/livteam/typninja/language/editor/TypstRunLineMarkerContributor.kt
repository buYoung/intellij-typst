package com.livteam.typninja.language.editor

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.preview.TypstOutputPathResolver
import com.livteam.typninja.preview.TypstPreviewService
import com.livteam.typninja.settings.TypstSettingsService
import java.nio.file.Path

/** Adds preview/export actions to the first visible line of a Typst file. */
class TypstRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        val file = element.containingFile as? TypstFile ?: return null
        val firstVisibleLeaf = generateSequence(PsiTreeUtil.getDeepestFirst(file)) { PsiTreeUtil.nextLeaf(it) }
            .firstOrNull { it.node.elementType != TokenType.WHITE_SPACE }
            ?: return null
        if (element !== firstVisibleLeaf) return null
        val virtualFile = file.virtualFile ?: return null
        val preview = object : AnAction("Preview Typst", "Refresh the Typst preview", AllIcons.Actions.Execute) {
            override fun actionPerformed(event: AnActionEvent) {
                val currentText = FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                TypstPreviewService.getInstance(file.project).preview(virtualFile, currentText)
                ToolWindowManager.getInstance(file.project).getToolWindow("Typst Preview")?.show()
            }
        }
        val export = object : AnAction("Export Typst", "Export using the configured format and output path", AllIcons.Actions.Download) {
            override fun actionPerformed(event: AnActionEvent) {
                val settings = TypstSettingsService.getInstance(file.project)
                val format = settings.state.defaultExportFormat.orEmpty().ifBlank { "pdf" }
                val sourcePath = Path.of(virtualFile.path).toAbsolutePath().normalize()
                val output = TypstOutputPathResolver.resolve(settings, settings.mainFile(sourcePath), format)
                val currentText = FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                TypstPreviewService.getInstance(file.project).export(virtualFile, format, output, currentText)
            }
        }
        return Info(AllIcons.Actions.Execute, arrayOf(preview, export)) { "Preview or export this Typst document" }
    }
}
